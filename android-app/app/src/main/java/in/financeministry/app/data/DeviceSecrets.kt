package `in`.financeministry.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keys never leave Android Keystore; only the wrapped database secret is persisted. */
class DeviceSecrets(private val context: Context, private val namespace: String = "finance") {
    private val prefs get() = context.getSharedPreferences("${namespace}_secrets", Context.MODE_PRIVATE)
    private val wrapAlias = "${namespace}_db_wrap_v1"
    private val hmacAlias = "${namespace}_source_hmac_v1"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun databasePassphrase(databaseExists: Boolean): ByteArray = synchronized(lock) {
        val ks = store()
        val iv = prefs.getString("iv", null)
        val wrapped = prefs.getString("wrapped", null)
        if (iv != null && wrapped != null && ks.containsAlias(wrapAlias)) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, ks.getKey(wrapAlias, null), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                return@synchronized cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP)).also { check(it.size == 32) }
            } catch (_: Exception) { error("Local encryption key cannot be opened. Existing data was preserved.") }
        }
        check(!databaseExists && iv == null && wrapped == null && !ks.containsAlias(wrapAlias)) {
            "Local encryption key is missing or incomplete. Existing data was preserved."
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(wrapAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, generator.generateKey())
            check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("wrapped", Base64.encodeToString(cipher.doFinal(secret), Base64.NO_WRAP)).commit())
            secret.copyOf()
        } finally { secret.fill(0) }
    }

    fun hmacSource(sender: String, timestamp: Long, body: String): ByteArray = synchronized(lock) {
        val ks = store()
        val key = if (ks.containsAlias(hmacAlias)) ks.getKey(hmacAlias, null) as SecretKey else {
            // A lost fingerprint key must not silently change the identity of existing sources.
            check(!prefs.getBoolean("hmac_created", false)) { "Source key is missing. Existing data was preserved." }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(hmacAlias, KeyProperties.PURPOSE_SIGN).build())
                generateKey().also { check(prefs.edit().putBoolean("hmac_created", true).commit()) }
            }
        }
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        for (part in listOf(sender.toByteArray(Charsets.UTF_8), ByteBuffer.allocate(8).putLong(timestamp).array(), body.toByteArray(Charsets.UTF_8))) {
            try { mac.update(ByteBuffer.allocate(4).putInt(part.size).array()); mac.update(part) } finally { part.fill(0) }
        }
        mac.doFinal()
    }

    fun erase() = synchronized(lock) {
        check(prefs.edit().clear().commit())
        store().run { deleteEntry(wrapAlias); deleteEntry(hmacAlias) }
    }

    companion object { private val lock = Any() }
}

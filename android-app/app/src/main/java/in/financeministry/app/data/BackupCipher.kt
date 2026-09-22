package `in`.financeministry.app.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Fixed-version, bounded, authenticated envelope. No user-controlled KDF parameters. */
object BackupCipher {
    const val MAX_BYTES = 32 * 1024 * 1024
    private val magic = "FMBAK001".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 36
    private const val ITERATIONS = 600_000

    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size <= MAX_BYTES) { "Backup is too large." }
        require(password.size in 12..1024) { "Use a password between 12 and 1024 characters." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val header = magic + salt + nonce
        return header + crypt(Cipher.ENCRYPT_MODE, payload, password, salt, nonce, header)
    }

    fun decrypt(encrypted: ByteArray, password: CharArray): ByteArray {
        require(encrypted.size in (HEADER + 16)..(MAX_BYTES + HEADER + 16) && password.size in 1..1024 &&
            encrypted.copyOfRange(0, 8).contentEquals(magic)) { "Unsupported or damaged backup." }
        return try {
            crypt(Cipher.DECRYPT_MODE, encrypted.copyOfRange(HEADER, encrypted.size), password,
                encrypted.copyOfRange(8, 24), encrypted.copyOfRange(24, HEADER), encrypted.copyOfRange(0, HEADER))
        } catch (_: Exception) {
            throw IllegalArgumentException("Unable to validate this backup. Check the password and file. Your ledger was not changed.")
        }
    }

    private fun crypt(mode: Int, input: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray, header: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val derived = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(header)
                doFinal(input)
            }
        } finally { derived.fill(0) }
    }
}

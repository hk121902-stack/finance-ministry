package `in`.financeministry.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import `in`.financeministry.app.sms.TransactionNotifications
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun namespace() = "test_${UUID.randomUUID().toString().replace("-", "") }"
    private fun input() = ManualInput("250.50", Direction.Debit, System.currentTimeMillis(), TransactionType.Other)

    @Test fun encrypted_roundtrip_wrong_key_and_missing_key_preserve_data() = runBlocking {
        val name = namespace(); val secrets = DeviceSecrets(context, name); val file = context.getDatabasePath("$name.db")
        val repository = TransactionRepository(context, name)
        try {
            val id = repository.save(input())
            repository.close()
            assertFalse(file.inputStream().use { it.readNBytes(16) }.contentEquals("SQLite format 3\u0000".toByteArray()))
            assertEquals(25050L, repository.get(id)!!.amountMinor)
            repository.close()
            assertThrows(Exception::class.java) { FinanceDatabase.open(context, ByteArray(32) { 42 }, "$name.db") }
            assertTrue(file.exists())
            assertEquals(25050L, repository.get(id)!!.amountMinor)
            repository.close()
            val prefs = context.getSharedPreferences("${name}_secrets", Context.MODE_PRIVATE)
            val iv = prefs.getString("iv", null); val wrapped = prefs.getString("wrapped", null)
            prefs.edit().remove("iv").commit()
            assertThrows(IllegalStateException::class.java) { secrets.databasePassphrase(true) }
            assertTrue(file.exists())
            prefs.edit().putString("iv", iv).putString("wrapped", wrapped).commit()
            assertEquals(25050L, repository.get(id)!!.amountMinor)
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertNull(ks.getKey("${name}_db_wrap_v1", null).encoded)
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun manual_edit_audit_totals_delete_and_nullable_fingerprints() = runBlocking {
        val name = namespace(); val repository = TransactionRepository(context, name)
        try {
            val id = repository.save(input()); val other = repository.save(input().copy(direction = Direction.Transfer))
            assertEquals(2, repository.snapshot().rows.size)
            assertEquals("25050", repository.snapshot().debit.toString())
            repository.save(input().copy(status = TransactionStatus.Failed), id)
            assertTrue(repository.get(id)!!.isUserCorrected)
            assertEquals("0", repository.snapshot().debit.toString())
            repository.close()
            val secrets = DeviceSecrets(context, name)
            val db = FinanceDatabase.open(context, secrets.databasePassphrase(true), "$name.db")
            assertTrue(db.transactions().corrections(id).any { it.fieldName == "status" && it.newValue == "Failed" })
            db.close()
            repository.delete(id)
            assertNull(repository.get(id)); assertNotNull(repository.get(other))
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun capture_requires_consent_deduplicates_and_notifies_after_commit() = runBlocking {
        val name = namespace(); val repository = TransactionRepository(context, name)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECEIVE_SMS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        try {
            val sms = IncomingSms("SYNTHETIC", 1700000000000, "INR 250.50 debited via UPI")
            assertFalse(repository.ingest(sms))
            repository.preferences.edit().putBoolean("sms_disclosure", true).commit()
            var notified: String? = null
            assertTrue(repository.ingest(sms) { row ->
                notified = row.id
                TransactionNotifications.post(context, row, true)
            })
            assertNotNull(notified); assertFalse(repository.ingest(sms))
            assertEquals(1, repository.snapshot().rows.size)
            val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue(notifications.any { it.tag == notified })
            context.getSystemService(NotificationManager::class.java).cancel(notified, 1)
            assertFalse(repository.ingest(IncomingSms("SYNTHETIC", 1700000000001, "OTP 123456 for INR 250 payment")))
            assertTrue(repository.ingest(IncomingSms("SYNTHETIC", 1700000000002, "Your account debited by 250")))
            assertEquals("NeedsReview", repository.snapshot().rows.first().reviewState)
            val secrets = DeviceSecrets(context, name)
            assertArrayEquals(secrets.hmacSource("a", 1, "bc"), secrets.hmacSource("a", 1, "bc"))
            assertFalse(secrets.hmacSource("a", 1, "bc").contentEquals(secrets.hmacSource("ab", 1, "c")))
            repository.eraseAll()
            assertFalse(repository.ingest(sms))
            assertTrue(repository.snapshot().rows.isEmpty())
            assertFalse(context.getDatabasePath("$name.db").exists())
        } finally {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { notification -> runBlocking { repository.snapshot().rows.any { it.id == notification.tag } } }
                .forEach { context.getSystemService(NotificationManager::class.java).cancel(it.tag, it.id) }
            repository.eraseAll(); repository.close()
            // Runtime revocation kills the instrumentation process. Consent remains off;
            // these grants apply only to the explicitly authorized synthetic test emulator.
        }
    }

    @Test fun manifest_and_packaged_backup_rules_enforce_local_only() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS)
        val permissions = info.requestedPermissions.orEmpty().toSet()
        listOf(Manifest.permission.INTERNET, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS).forEach { assertFalse(permissions.contains(it)) }
        assertTrue(permissions.contains(Manifest.permission.RECEIVE_SMS))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals("android.permission.BROADCAST_SMS", info.receivers.orEmpty().single { it.name.endsWith("SmsReceiver") }.permission)
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val xml = context.resources.getXml(resource); val domains = mutableListOf<String>()
            while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "exclude") domains.add(xml.getAttributeValue(null, "domain"))
                xml.next()
            }
            xml.close()
            listOf("database", "sharedpref", "file", "root", "device_database", "device_sharedpref").forEach { assertTrue(domains.contains(it)) }
        }
    }
}

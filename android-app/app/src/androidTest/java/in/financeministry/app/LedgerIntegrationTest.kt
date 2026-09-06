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

    @Test fun notification_failure_or_disabled_notifications_never_undo_saved_capture() = runBlocking {
        val repository = TransactionRepository(context, namespace())
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECEIVE_SMS)
        try {
            assertNotNull(repository.save(input()))
            assertFalse(repository.ingest(IncomingSms("SYNTHETIC", 1700000000000, "INR 42 debited via UPI")))
            repository.preferences.edit().putBoolean("sms_disclosure", true).commit()
            assertTrue(repository.ingest(IncomingSms("SYNTHETIC", 1700000000001, "INR 42 debited via UPI")) { error("Synthetic notification failure") })
            var id: String? = null
            assertTrue(repository.ingest(IncomingSms("SYNTHETIC", 1700000000002, "INR 43 debited via UPI")) {
                id = it.id; TransactionNotifications.post(context, it, false)
            })
            assertNotNull(repository.get(id!!))
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == id })
            assertEquals(3, repository.snapshot().rows.size)
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun version_one_encrypted_ledger_migrates_without_losing_records_or_corrections() = runBlocking {
        val name = namespace()
        val repository = TransactionRepository(context, name)
        val password = DeviceSecrets(context, name).databasePassphrase(false)
        System.loadLibrary("sqlcipher")
        val schema = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("in.financeministry.app.data.FinanceDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(password).create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name("$name.db")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                            val indices = entity.getJSONArray("indices")
                            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql")
                                .replace("\${TABLE_NAME}", entity.getString("tableName")))
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected legacy upgrade")
                }).build())
        try {
            try {
                val old = helper.writableDatabase
                old.execSQL("INSERT INTO transactions (id, sourceType, sourceTimestamp, effectiveTimestamp, amountMinor, currency, direction, status, channel, transactionType, confidence, reviewState, parserVersion, isUserCorrected, createdAt, updatedAt) VALUES ('legacy', 'Manual', 1, 1, 4200, 'INR', 'Debit', 'Successful', 'CashManual', 'Other', 0, 'Confirmed', 0, 1, 1, 1)")
                old.execSQL("INSERT INTO corrections VALUES ('audit', 'legacy', 1, 'amountMinor', '4100', '4200')")
            } finally { helper.close(); password.fill(0) }
            val migrated = repository.get("legacy")!!
            assertEquals(4200L, migrated.amountMinor)
            assertTrue(migrated.isUserCorrected)
            assertNull(migrated.referenceHash)
            assertNull(migrated.linkedOriginalId)
            repository.close()
            val db = FinanceDatabase.open(context, DeviceSecrets(context, name).databasePassphrase(true), "$name.db")
            try { assertEquals("4200", db.transactions().corrections("legacy").single().newValue) }
            finally { db.close() }
        } finally { helper.close(); password.fill(0); repository.eraseAll(); repository.close() }
    }

    @Test fun reversal_links_require_strong_evidence_and_corrections_break_links() = runBlocking {
        val repository = TransactionRepository(context, namespace())
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECEIVE_SMS)
        try {
            repository.preferences.edit().putBoolean("sms_disclosure", true).commit()
            val now = System.currentTimeMillis()
            assertTrue(repository.ingest(IncomingSms("SYNTHETIC", now, "INR 42 debited via UPI account XX0000 Ref 000000000001")))
            val original = repository.snapshot().rows.single()
            assertNotNull(original.referenceHash)
            assertEquals(32, original.referenceHash!!.size)
            assertTrue(repository.ingest(IncomingSms("SYNTHETIC", now + 1, "INR 42 debit transaction reversed via UPI account XX0000 Ref 000000000001")))
            val reversal = repository.snapshot().rows.first()
            assertEquals(original.id, reversal.linkedOriginalId)
            assertEquals("0", repository.snapshot().debit.toString())
            repository.close()
            assertEquals(original.id, repository.get(reversal.id)!!.linkedOriginalId)
            repository.save(input().copy(amount = "43.00"), original.id)
            assertNull(repository.get(reversal.id)!!.linkedOriginalId)
            assertEquals("4300", repository.snapshot().debit.toString())
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun refunds_without_unique_reference_account_and_sender_remain_reviewable() = runBlocking {
        val repository = TransactionRepository(context, namespace())
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECEIVE_SMS)
        try {
            repository.preferences.edit().putBoolean("sms_disclosure", true).commit()
            val now = System.currentTimeMillis()
            repository.ingest(IncomingSms("SYNTHETIC", now, "INR 42 debited via UPI account XX0000 Ref 000000000001"))
            val original = repository.snapshot().rows.single()
            listOf(
                "INR 42 refund credited via UPI account XX0000",
                "INR 42 refund credited via UPI account XX9999 Ref 000000000001",
                "INR 41 refund credited via UPI account XX0000 Ref 000000000001"
            ).forEachIndexed { i, text ->
                repository.ingest(IncomingSms("SYNTHETIC", now + i + 1, text))
                assertNull(repository.snapshot().rows.first().linkedOriginalId)
                assertEquals("NeedsReview", repository.snapshot().rows.first().reviewState)
            }
            repository.ingest(IncomingSms("OTHER-SYNTHETIC", now + 4, "INR 42 refund credited via UPI account XX0000 Ref 000000000001"))
            assertNull(repository.snapshot().rows.first().linkedOriginalId)
            repository.ingest(IncomingSms("SYNTHETIC", now + 5, "INR 42 refund credited via UPI account XX0000 Ref 000000000001"))
            val refund = repository.snapshot().rows.first()
            assertEquals(original.id, refund.linkedOriginalId)
            assertEquals("4200", repository.snapshot().credit.toString())
            repository.delete(original.id)
            assertNull(repository.get(refund.id)!!.linkedOriginalId)
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun history_pages_reach_old_records_and_filter_before_pagination() = runBlocking {
        val name = namespace()
        val repository = TransactionRepository(context, name)
        try {
            val now = System.currentTimeMillis()
            val db = FinanceDatabase.open(context, DeviceSecrets(context, name).databasePassphrase(false), "$name.db")
            try {
                db.runInTransaction {
                    repeat(505) { index -> db.transactions().insert(TransactionEntity(
                        id = "row-${index.toString().padStart(4, '0')}", sourceType = "Manual",
                        sourceTimestamp = now, effectiveTimestamp = now, amountMinor = 100,
                        direction = "Debit", status = "Successful", channel = "CashManual", transactionType = "Other",
                        reviewState = "Confirmed", isUserCorrected = index == 0, createdAt = now, updatedAt = now)) }
                }
            } finally { db.close() }
            val ids = mutableListOf<String>()
            for (offset in listOf(0, 100, 200, 300, 400, 500)) {
                val page = repository.snapshot(offset)
                ids += page.rows.map { it.id }
                assertEquals(offset < 500, page.hasOlder)
                assertEquals("50500", page.debit.toString())
                assertEquals("50500", page.dailyDebit.toString())
            }
            assertEquals(505, ids.size)
            assertEquals(505, ids.distinct().size)
            assertEquals("row-0000", repository.snapshot(filter = "Edited").rows.single().id)
            assertTrue(repository.snapshot(filter = "Review").rows.isEmpty())
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun daily_totals_respect_dates_status_transfers_and_edits() = runBlocking {
        val repository = TransactionRepository(context, namespace())
        try {
            val today = java.time.LocalDate.of(2026, 9, 6)
            val zone = java.time.ZoneId.systemDefault()
            fun at(day: java.time.LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val base = input().copy(amount = "10.00", timestamp = at(today))
            val id = repository.save(base)
            repository.save(base.copy(timestamp = at(today.minusDays(1)), amount = "20.00"))
            repository.save(base.copy(timestamp = at(today.plusDays(1)), amount = "30.00"))
            repository.save(base.copy(timestamp = at(today.withDayOfMonth(1).minusDays(1)), amount = "40.00"))
            repository.save(base.copy(direction = Direction.Credit, amount = "5.00"))
            repository.save(base.copy(direction = Direction.Transfer))
            repository.save(base.copy(type = TransactionType.SelfTransfer))
            for (status in listOf(TransactionStatus.Failed, TransactionStatus.Reversed, TransactionStatus.Pending, TransactionStatus.Unknown)) {
                repository.save(base.copy(status = status))
            }
            val snapshot = repository.snapshot(today = today)
            assertEquals("1000", snapshot.dailyDebit.toString())
            assertEquals("500", snapshot.dailyCredit.toString())
            assertEquals("6000", snapshot.debit.toString())
            repository.save(base.copy(amount = "12.00"), id)
            assertEquals("1200", repository.snapshot(today = today).dailyDebit.toString())
            repository.delete(id)
            assertEquals("0", repository.snapshot(today = today).dailyDebit.toString())
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun parsed_fields_survive_encrypted_storage_and_manual_correction() = runBlocking {
        val repository = TransactionRepository(context, namespace())
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECEIVE_SMS)
        try {
            repository.preferences.edit().putBoolean("sms_disclosure", true).commit()
            val sms = IncomingSms("SYNTHETIC", 1700000000000,
                "Sent Rs.42.00\nFrom TEST Bank A/C *0000\nTo TEST PERSON\nOn 01/01/26")
            assertTrue(repository.ingest(sms))
            val row = repository.snapshot().rows.single()
            repository.close()
            val stored = repository.get(row.id)!!
            assertEquals(4200L, stored.amountMinor)
            assertEquals("••••0000", stored.maskedAccountHint)
            assertEquals("TEST PERSON", stored.counterpartyLabel)
            repository.save(input().copy(amount = "43.00"), row.id)
            assertFalse(repository.ingest(sms))
            assertEquals(4300L, repository.get(row.id)!!.amountMinor)
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

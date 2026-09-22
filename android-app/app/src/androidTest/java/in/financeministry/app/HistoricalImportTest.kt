package `in`.financeministry.app

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HistoricalImportTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun repository() = TransactionRepository(context, "import_test_${UUID.randomUUID()}")
    private fun grant() = InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_SMS)
    private fun source(vararg rows: HistoricalSms) = HistoricalSmsSource { _, emit -> rows.forEach { emit(it) } }

    @Test fun portable_restore_requires_review_before_reimport_can_change_totals() = runBlocking {
        grant()
        val first = repository()
        val second = repository()
        val now = System.currentTimeMillis()
        val sms = HistoricalSms("TEST", now - 1000, 0, "INR 42 debited via UPI")
        val password = "test-only-password-long".toCharArray()
        try {
            first.commitImport(first.previewImport(source(sms), now))
            val backup = first.createEncryptedBackup(password)
            second.restoreBackup(second.previewBackup(backup, password), protectCurrent = false)
            assertTrue(second.restoredHistoryNeedsReview())
            val preview = second.previewImport(source(sms), now)
            assertEquals(0, preview.ready)
            assertEquals(1, preview.needsReview)
            second.commitImport(preview)
            assertEquals("4200", second.snapshot().debit.toString())
            assertEquals(1, second.previewImport(source(sms), now).duplicates)
            val onward = BackupCipher.decrypt(second.createEncryptedBackup(password), password)
            assertTrue(org.json.JSONObject(onward.toString(Charsets.UTF_8)).getBoolean("fingerprintHistoryRequiresReview"))
            onward.fill(0)
        } finally { password.fill('\u0000'); first.eraseAll(); first.close(); second.eraseAll(); second.close() }
    }

    @Test fun undo_import_keeps_an_incoming_payment_used_by_repayment_history() = runBlocking {
        grant()
        val r = repository()
        val now = System.currentTimeMillis()
        try {
            val result = r.commitImport(r.previewImport(source(HistoricalSms("TEST", now - 1000, 0, "INR 500 credited via UPI")), now))
            val credit = r.snapshot().rows.single()
            val expense = r.save(ManualInput("1000", Direction.Debit, now, TransactionType.Other, ownership = SpendingOwnership.ForOther))
            r.recordRepayment(expense, "250", credit.effectiveTimestamp, "Dinner", credit.id)
            assertEquals(0, r.undoImport(result.batchId))
            assertNotNull(r.get(credit.id))
            assertEquals(25000L, r.get(expense)!!.repaidMinor)
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun permission_denial_never_reads_source() = runBlocking {
        val denied = object : android.content.ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int = android.content.pm.PackageManager.PERMISSION_DENIED
        }
        val repository = TransactionRepository(denied, "denied_import_${UUID.randomUUID()}")
        try {
            try {
                repository.previewImport(HistoricalSmsSource { _, _ -> fail("Read without permission") })
                fail("Permission denial accepted")
            } catch (_: IllegalStateException) { }
            assertTrue(repository.snapshot().rows.isEmpty())
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun actual_inbox_provider_imports_host_injected_sms() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("runImportProvider") == "true")
        grant()
        val repository = repository()
        try {
            val preview = repository.previewImport()
            assertTrue("Expected synthetic inbox SMS", preview.rows.any { it.amountMinor == 876543L })
            repository.commitImport(preview)
            assertEquals(1, repository.snapshot().rows.count { it.amountMinor == 876543L })
            assertEquals(0, repository.previewImport().rows.size)
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun preview_repeat_import_original_dates_and_undo_preserve_edits() = runBlocking {
        grant()
        val repository = repository()
        val now = System.currentTimeMillis()
        val dayAgo = now - 86400000
        val first = HistoricalSms("TEST", dayAgo, dayAgo - 2000, "INR 42 debited via UPI")
        val second = HistoricalSms("TEST", dayAgo + 1, 0, "INR 43 credited via UPI")
        try {
            val preview = repository.previewImport(source(first, first, second, HistoricalSms("TEST", now, 0, "OTP 000000")), now)
            assertEquals(2, preview.ready)
            assertEquals(1, preview.duplicates)
            assertTrue(repository.snapshot().rows.isEmpty())
            val result = repository.commitImport(preview)
            assertEquals(2, result.inserted)
            assertEquals(2, repository.snapshot().rows.size)
            val row = repository.snapshot().rows.single { it.amountMinor == 4200L }
            assertEquals(dayAgo, row.effectiveTimestamp)
            repository.save(ManualInput("44", Direction.Debit, dayAgo, TransactionType.Other), row.id)
            val again = repository.previewImport(source(first, second), now)
            assertEquals(0, again.ready)
            assertEquals(2, again.duplicates)
            assertEquals(1, repository.undoImport(result.batchId))
            assertEquals(4400L, repository.get(row.id)!!.amountMinor)
            assertEquals(1, repository.snapshot().rows.size)
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun stale_preview_after_erasure_cannot_recreate_the_ledger() = runBlocking {
        grant()
        val repository = repository()
        try {
            val now = System.currentTimeMillis()
            val preview = repository.previewImport(source(HistoricalSms("TEST", now, 0, "INR 42 debited")), now)
            repository.eraseAll()
            try { repository.commitImport(preview); fail("Stale preview accepted") } catch (_: IllegalStateException) { }
            assertTrue(repository.snapshot().rows.isEmpty())
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun cancellation_during_scan_has_no_partial_import() = runBlocking {
        grant()
        val repository = repository()
        val ready = CompletableDeferred<Unit>()
        try {
            val now = System.currentTimeMillis()
            val scan = launch {
                repository.previewImport(HistoricalSmsSource { _, emit ->
                    emit(HistoricalSms("TEST", now, 0, "INR 42 debited"))
                    ready.complete(Unit)
                    awaitCancellation()
                }, now)
            }
            ready.await(); scan.cancel(); scan.join()
            assertTrue(repository.snapshot().rows.isEmpty())
            assertNull(repository.latestImport())
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun live_capture_between_preview_and_commit_is_not_duplicated() = runBlocking {
        grant()
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECEIVE_SMS)
        val repository = repository()
        try {
            val now = System.currentTimeMillis()
            val sent = now - 2000
            val body = "INR 42 debited via UPI"
            val preview = repository.previewImport(source(HistoricalSms("TEST", now, sent, body)), now)
            repository.preferences.edit().putBoolean("sms_disclosure", true).commit()
            assertTrue(repository.ingest(IncomingSms("TEST", sent, body)))
            val existing = repository.snapshot().rows.single()
            repository.save(ManualInput("45", Direction.Debit, sent, TransactionType.Other), existing.id)
            assertEquals(0, repository.commitImport(preview).inserted)
            assertEquals(4500L, repository.snapshot().rows.single().amountMinor)
            assertNull(repository.latestImport())
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun repeated_commit_reopen_and_undo_are_idempotent() = runBlocking {
        grant()
        val repository = repository()
        try {
            val now = System.currentTimeMillis()
            val preview = repository.previewImport(source(HistoricalSms("TEST", now, 0, "INR 42 debited")), now)
            val result = repository.commitImport(preview)
            assertEquals(0, repository.commitImport(preview).inserted)
            repository.close()
            assertEquals(result.batchId, repository.latestImport()!!.id)
            assertEquals(1, repository.undoImport(result.batchId))
            assertEquals(0, repository.undoImport(result.batchId))
            assertTrue(repository.snapshot().rows.isEmpty())
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun imported_and_live_messages_use_the_same_conservative_source_resolution() = runBlocking {
        grant()
        val repository = repository()
        val now = System.currentTimeMillis()
        try {
            val source = repository.addPaymentSource("Personal UPI", "Bank account", Channel.UPI, "HDFC", "7111")
            val body = "INR 42 debited via UPI account XX7111"
            val preview = repository.previewImport(source(HistoricalSms("HDFCBK", now, 0, body)), now)
            repository.commitImport(preview)
            assertEquals(source.id, repository.snapshot().rows.single().paymentSourceId)
        } finally { repository.eraseAll(); repository.close() }
    }
}

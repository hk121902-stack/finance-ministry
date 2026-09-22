package `in`.financeministry.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NextChapterIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val sep = LocalDate.of(2026, 9, 16).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun expense() = ManualInput("3000", Direction.Debit, sep, TransactionType.Other,
        channel = Channel.UPI, ownership = SpendingOwnership.Group, groupLabel = "Dinner", personalShare = "1000")

    @Test fun source_only_ledger_requires_current_completed_backup_before_replacement() = runBlocking {
        val source = TransactionRepository(context, "next_${UUID.randomUUID()}")
        val target = TransactionRepository(context, "next_${UUID.randomUUID()}")
        val password = "test-only-password-long".toCharArray()
        try {
            val sourceId = target.addPaymentSource("Daily account", "Bank account", Channel.UPI).id
            val preview = target.previewBackup(source.createEncryptedBackup(password), password)
            assertTrue(target.hasBackupData())
            try { target.restoreBackup(preview, protectCurrent = true); fail("Sources also need protection") }
            catch (_: IllegalArgumentException) { }
            assertEquals(sourceId, target.paymentSources().single().id)
            val output = java.io.ByteArrayOutputStream()
            target.writePreparedBackup(target.prepareBackup(password)) { output }
            assertEquals(1, target.previewBackup(output.toByteArray(), password).sourceCount)
            target.restoreBackup(preview, protectCurrent = true)
            assertTrue(target.paymentSources().isEmpty())
        } finally { password.fill('\u0000'); source.eraseAll(); source.close(); target.eraseAll(); target.close() }
    }

    @Test fun older_backup_cannot_certify_newer_snapshot_at_same_revision() = runBlocking {
        val r = TransactionRepository(context, "next_${UUID.randomUUID()}")
        val password = "test-only-password-long".toCharArray()
        try {
            r.save(expense())
            val older = r.prepareBackup(password)
            r.preferences.edit().putString("preferred_name", "Updated name").commit()
            val newer = r.prepareBackup(password)
            assertEquals(older.revision, newer.revision)
            try {
                r.writePreparedBackup(older) { java.io.ByteArrayOutputStream() }
                fail("An older file must not certify the newer snapshot")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0L, r.preferences.getLong("last_backup_at", 0))
            r.writePreparedBackup(newer) { java.io.ByteArrayOutputStream() }
            assertTrue(r.preferences.getLong("last_backup_at", 0) > 0)
        } finally { password.fill('\u0000'); r.eraseAll(); r.close() }
    }

    @Test fun backup_is_not_completed_when_output_close_fails() = runBlocking {
        val r = TransactionRepository(context, "next_${UUID.randomUUID()}")
        val password = "test-only-password-long".toCharArray()
        try {
            r.save(expense())
            val prepared = r.prepareBackup(password)
            try { r.writePreparedBackup(prepared) { object : java.io.ByteArrayOutputStream() {
                override fun close() { throw java.io.IOException("Simulated destination close failure") }
            } }; fail("Close failure must propagate") } catch (_: java.io.IOException) { }
            assertEquals(0L, r.preferences.getLong("last_backup_at", 0))
            val output = java.io.ByteArrayOutputStream()
            r.writePreparedBackup(prepared) { output }
            assertTrue(r.preferences.getLong("last_backup_at", 0) > 0)
            assertEquals(1, r.previewBackup(output.toByteArray(), password).transactionCount)
        } finally { password.fill('\u0000'); r.eraseAll(); r.close() }
    }

    @Test fun invalid_backup_flags_and_receipt_dates_cannot_replace_ledger() = runBlocking {
        val r = TransactionRepository(context, "next_${UUID.randomUUID()}")
        val password = "test-only-password-long".toCharArray()
        try {
            val id = r.save(expense())
            r.recordRepayment(id, "100", sep, "Dinner")
            val encrypted = r.createEncryptedBackup(password)
            val plain = BackupCipher.decrypt(encrypted, password)
            val root = org.json.JSONObject(plain.toString(Charsets.UTF_8))
            val transactions = root.getJSONObject("data").getJSONObject("tables").getJSONArray("transactions")
            transactions.getJSONObject(0).put("repaymentExpected", 2)
            try { r.previewBackup(BackupCipher.encrypt(root.toString().toByteArray(), password), password); fail("Invalid boolean") } catch (_: IllegalArgumentException) { }
            val other = org.json.JSONObject(plain.toString(Charsets.UTF_8))
            other.getJSONObject("data").getJSONObject("tables").getJSONArray("repayments").getJSONObject(0).put("receivedAt", sep + 86400000)
            try { r.previewBackup(BackupCipher.encrypt(other.toString().toByteArray(), password), password); fail("Receipt date mismatch") } catch (_: IllegalArgumentException) { }
            assertEquals(10000L, r.get(id)!!.repaidMinor)
            plain.fill(0)
        } finally { password.fill('\u0000'); r.eraseAll(); r.close() }
    }

    @Test fun batch_preview_is_read_only_and_stale_preview_is_rejected() = runBlocking {
        val r = TransactionRepository(context, "next_${UUID.randomUUID()}")
        try {
            val id = r.save(expense())
            val preview = r.previewBatch(setOf(id), category = "Food")
            assertEquals(1, preview.changeCount)
            assertEquals("Other", r.get(id)!!.category)
            r.classify(id, "Travel", SpendingOwnership.Group, "Dinner", "1000")
            try { r.applyBatch(preview); fail("Preview must be refreshed after ledger changes") } catch (_: IllegalArgumentException) { }
            val refreshed = r.previewBatch(setOf(id), category = "Food")
            val undo = r.applyBatch(refreshed)
            assertEquals("Food", r.get(id)!!.category)
            r.undoBatch(undo)
            assertEquals("Travel", r.get(id)!!.category)
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun remembered_rule_is_saved_atomically_and_conflicts_require_category_choice() = runBlocking {
        val r = TransactionRepository(context, "next_${UUID.randomUUID()}")
        try {
            val id = r.save(expense().copy(label = "Fresh Basket", category = "Food"),
                rememberedRule = RememberCategoryRule("Fresh Basket", null))
            assertEquals("Food", r.categoryRules().single().category)
            r.saveCategoryRule("Fresh Basket", null, "Travel")
            assertEquals(2, r.categoryRules().size)
            try { r.save(expense(), rememberedRule = RememberCategoryRule("x", null)); fail("Invalid rule must not save expense") } catch (_: IllegalArgumentException) { }
            assertEquals(1L, r.snapshot(today = LocalDate.of(2026, 9, 1)).resultCount)
            assertEquals("Food", r.get(id)!!.category)
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun repayment_history_edits_preserve_receipts_and_legacy_dates() = runBlocking {
        val r = TransactionRepository(context, "next_${UUID.randomUUID()}")
        try {
            val expenseId = r.save(expense())
            val receipt = r.recordRepayment(expenseId, "500", sep, "Aarav")
            r.editRepayment(receipt.id, "250", sep, "Aarav")
            assertEquals(25000L, r.get(expenseId)!!.repaidMinor)
            assertEquals(50000L, r.get(receipt.incomingId!!)!!.amountMinor)
            try { r.editRepayment(receipt.id, "501", sep, "Aarav"); fail("Cannot allocate above receipt") } catch (_: IllegalArgumentException) { }
            try { r.editRepayment(receipt.id, "250", sep + 86400000, "Aarav"); fail("Linked date must match receipt") } catch (_: IllegalArgumentException) { }
            val legacyId = r.save(expense().copy(repaid = "100"))
            val legacy = r.repaymentsFor(legacyId).single()
            assertNull(legacy.receivedAt)
            r.editRepayment(legacy.id, "100", sep, "Dinner")
            r.classify(legacyId, "Food", SpendingOwnership.Group, "Dinner", "1000", "100")
            assertEquals(sep, r.repaymentsFor(legacyId).single().receivedAt)
            assertEquals(3L, r.snapshot(today = LocalDate.of(2026, 9, 1)).resultCount)
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun batch_updates_only_selected_compatible_records_and_undo_preserves_later_edits() = runBlocking {
        val r = TransactionRepository(context, "next_${UUID.randomUUID()}")
        try {
            val first = r.save(expense())
            val second = r.save(expense().copy(channel = Channel.Card))
            val untouched = r.save(expense())
            val source = r.addPaymentSource("Daily UPI", "Bank account", Channel.UPI)
            val token = r.batchUpdate(setOf(first, second), sourceId = source.id)
            assertEquals(listOf(first), token.changedIds)
            assertEquals(listOf(second), token.skippedIds)
            assertEquals(source.id, r.get(first)!!.paymentSourceId)
            assertNull(r.get(second)!!.paymentSourceId)
            assertNull(r.get(untouched)!!.paymentSourceId)
            r.undoBatch(token)
            assertNull(r.get(first)!!.paymentSourceId)
            val categoryToken = r.batchUpdate(setOf(first), category = "Food")
            r.classify(first, "Travel", SpendingOwnership.Group, "Dinner", "1000")
            try { r.undoBatch(categoryToken); fail("Must not undo a later change") } catch (_: IllegalArgumentException) { }
            assertEquals("Travel", r.get(first)!!.category)
            assertEquals("Other", r.get(untouched)!!.category)
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun portable_backup_restores_metadata_and_rejects_damage_without_changing_ledger() = runBlocking {
        val first = TransactionRepository(context, "next_${UUID.randomUUID()}")
        val second = TransactionRepository(context, "next_${UUID.randomUUID()}")
        val password = "test-only-long-password".toCharArray()
        try {
            val source = first.addPaymentSource("Daily UPI", "Bank account", Channel.UPI)
            val id = first.save(expense().copy(paymentSourceId = source.id))
            first.recordRepayment(id, "250", sep, "Aarav")
            first.saveCategoryRule("Fresh Basket", source.id, "Food")
            val bytes = first.createEncryptedBackup(password)
            val original = second.save(ManualInput("42", Direction.Debit, sep, TransactionType.Other))
            val preview = second.previewBackup(bytes, password)
            assertEquals(2, preview.transactionCount)
            assertEquals(1, preview.repaymentCount)
            try { second.previewBackup(bytes, "incorrect".toCharArray()); fail("Wrong password") } catch (_: IllegalArgumentException) { }
            assertNotNull(second.get(original))
            try { second.restoreBackup(preview, protectCurrent = false); fail("Must protect current ledger") } catch (_: IllegalArgumentException) { }
            second.writePreparedBackup(second.prepareBackup(password)) { java.io.ByteArrayOutputStream() }
            second.restoreBackup(preview, protectCurrent = true)
            assertNull(second.get(original))
            assertEquals(25000L, second.get(id)!!.repaidMinor)
            assertEquals("Daily UPI", second.paymentSources().single().nickname)
            assertEquals("Food", second.categoryRules().single().category)
            assertFalse(second.captureAllowed())
            assertTrue(second.csvReport(sep - 1, sep + 10000).contains("Dinner"))
            second.close()
            assertEquals(25000L, second.get(id)!!.repaidMinor)
        } finally { first.eraseAll(); first.close(); second.eraseAll(); second.close(); password.fill('\u0000') }
    }

    @Test fun linked_and_cash_repayments_have_dates_and_cannot_double_allocate() = runBlocking {
        val r = TransactionRepository(context, "next_${UUID.randomUUID()}")
        try {
            val id = r.save(expense())
            val credit = r.save(ManualInput("1000", Direction.Credit, sep + 1000, TransactionType.Other, channel = Channel.UPI))
            val linked = r.recordRepayment(id, "500", sep + 1000, "Aarav", credit)
            assertEquals(2, r.snapshot(today = LocalDate.of(2026, 9, 1)).resultCount.toInt())
            assertEquals(50000L, r.get(id)!!.repaidMinor)
            try { r.recordRepayment(id, "501", sep + 1000, "Aarav", credit); fail("Credit must not be over-allocated") } catch (_: IllegalArgumentException) { }
            val cash = r.recordRepayment(id, "250", sep + 2000, "Aarav")
            assertEquals(3, r.snapshot(today = LocalDate.of(2026, 9, 1)).resultCount.toInt())
            val totals = r.repaymentSummary(LocalDate.of(2026, 9, 1))
            assertEquals("200000", totals.newlyOwed.toString())
            assertEquals("75000", totals.received.toString())
            assertEquals("125000", totals.outstanding.toString())
            r.removeRepayment(linked.id)
            assertNotNull(r.get(credit))
            assertEquals(25000L, r.get(id)!!.repaidMinor)
            r.removeRepayment(cash.id)
            assertNotNull(r.get(cash.incomingId!!))
            assertEquals(0L, r.get(id)!!.repaidMinor)
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun gifts_count_as_spending_and_linked_financial_edits_require_unlinking() = runBlocking {
        val r = TransactionRepository(context, "next_${UUID.randomUUID()}")
        try {
            val id = r.save(expense().copy(repaymentExpected = false))
            assertEquals("300000", r.snapshot(today = LocalDate.of(2026, 9, 1)).personalSpend.toString())
            assertEquals("0", r.repaymentSummary(LocalDate.of(2026, 9, 1)).outstanding.toString())
            r.save(expense(), id)
            val allocation = r.recordRepayment(id, "100", sep, "Aarav")
            try { r.save(expense().copy(repaymentExpected = false), id); fail("Must preserve linked allocation") } catch (_: IllegalArgumentException) { }
            try { r.delete(allocation.incomingId!!); fail("Must unlink incoming credit before deletion") } catch (_: IllegalArgumentException) { }
            assertEquals(10000L, r.get(id)!!.repaidMinor)
        } finally { r.eraseAll(); r.close() }
    }
}

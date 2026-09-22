package `in`.financeministry.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class MatchingIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val month = LocalDate.of(2026, 9, 1)
    private val time = month.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun input(source: String, direction: Direction = Direction.Debit) = ManualInput("450", direction, time,
        TransactionType.Other, channel = Channel.UPI, label = "Corner Cafe", paymentSourceId = source)

    @Test fun duplicate_resolution_keeps_both_records_and_selected_labels_with_durable_undo() = runBlocking {
        val r = TransactionRepository(context, "matches_${UUID.randomUUID()}")
        try {
            val source = r.addPaymentSource("Daily account", "Bank account", Channel.UPI).id
            val a = r.save(input(source).copy(category = "Food", notes = "My receipt"))
            val b = r.save(input(source).copy(category = "Other", notes = "Second entry"))
            val suggestion = r.matchSuggestions().single()
            val decision = r.resolveMatch(suggestion, MatchAction.SamePayment, survivorId = b, categoryFromId = a, notesFromId = a)
            assertEquals(b, r.get(a)!!.duplicateOfId)
            assertEquals("Food", r.get(b)!!.category)
            assertEquals("My receipt", r.get(b)!!.userNotes)
            val totals = r.snapshot(today = month)
            assertEquals("45000", totals.debit.toString())
            assertEquals(totals.debit, totals.resultDebit)
            assertEquals(2L, totals.resultCount)
            assertTrue(r.matchSuggestions().isEmpty())
            try { r.delete(b); fail("Resolve match before deleting its surviving record") } catch (_: IllegalArgumentException) { }
            try { r.save(input(source).copy(amount = "900"), b); fail("Undo before changing a matched amount") } catch (_: IllegalArgumentException) { }
            try { r.classify(b, "Travel", SpendingOwnership.Personal); fail("Undo before changing matched classification") } catch (_: IllegalArgumentException) { }
            val batch = r.previewBatch(setOf(a, b), category = "Travel")
            assertEquals(0, batch.changeCount)
            assertEquals(2, batch.skipped.size)
            assertTrue(r.batchUpdate(setOf(a, b), category = "Travel").changedIds.isEmpty())
            r.close()
            r.undoMatch(decision.id)
            assertNull(r.get(a)!!.duplicateOfId)
            assertEquals("Other", r.get(b)!!.category)
            assertEquals("Second entry", r.get(b)!!.userNotes)
            assertEquals("90000", r.snapshot(today = month).debit.toString())
            assertEquals(1, r.matchSuggestions().size)
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun confirmed_transfer_keeps_two_legs_excludes_all_totals_and_can_be_undone() = runBlocking {
        val r = TransactionRepository(context, "matches_${UUID.randomUUID()}")
        try {
            val from = r.addPaymentSource("Daily account", "Bank account", Channel.UPI).id
            val to = r.addPaymentSource("Savings account", "Bank account", Channel.UPI).id
            val debit = r.save(input(from))
            val credit = r.save(input(to, Direction.Credit))
            val decision = r.resolveMatch(r.matchSuggestions().single(), MatchAction.SelfTransfer)
            assertEquals("SelfTransfer", r.get(debit)!!.ownership)
            assertEquals("SelfTransfer", r.get(credit)!!.ownership)
            val totals = r.snapshot(today = month)
            assertEquals("0", totals.debit.toString()); assertEquals("0", totals.credit.toString())
            assertEquals("0", totals.resultDebit.toString()); assertEquals("0", totals.resultCredit.toString())
            assertEquals(2L, totals.resultCount)
            r.undoMatch(decision.id)
            assertEquals("45000", r.snapshot(today = month).credit.toString())
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun keep_both_is_reversible_and_stale_or_allocated_pairs_are_not_resolved() = runBlocking {
        val r = TransactionRepository(context, "matches_${UUID.randomUUID()}")
        try {
            val source = r.addPaymentSource("Daily account", "Bank account", Channel.UPI).id
            val a = r.save(input(source))
            val b = r.save(input(source))
            val old = r.matchSuggestions().single()
            r.save(input(source).copy(notes = "Changed after preview"), b)
            try { r.resolveMatch(old, MatchAction.SamePayment, a); fail("Stale preview") } catch (_: IllegalArgumentException) { }
            val kept = r.resolveMatch(r.matchSuggestions().single(), MatchAction.KeepBoth)
            assertTrue(r.matchSuggestions().isEmpty())
            assertEquals("90000", r.snapshot(today = month).debit.toString())
            r.undoMatch(kept.id)
            r.save(input(source).copy(ownership = SpendingOwnership.ForOther, groupLabel = "Friend"), a)
            r.recordRepayment(a, "100", time, "Friend")
            val pair = r.matchSuggestions().first { setOf(it.first.id, it.second.id) == setOf(a, b) }
            try { r.resolveMatch(pair, MatchAction.SamePayment, a); fail("Existing allocations") } catch (_: IllegalArgumentException) { }
            assertNull(r.get(b)!!.duplicateOfId)
            r.resolveMatch(pair, MatchAction.KeepBoth)
            val password = "test-only-password-long".toCharArray()
            assertEquals(3, r.previewBackup(r.createEncryptedBackup(password), password).transactionCount)
        } finally { r.eraseAll(); r.close() }
    }

    @Test fun match_history_and_undo_survive_portable_backup() = runBlocking {
        val r = TransactionRepository(context, "matches_${UUID.randomUUID()}")
        val restored = TransactionRepository(context, "matches_${UUID.randomUUID()}")
        val password = "test-only-password-long".toCharArray()
        try {
            val source = r.addPaymentSource("Daily account", "Bank account", Channel.UPI).id
            val first = r.save(input(source))
            r.save(input(source))
            val decision = r.resolveMatch(r.matchSuggestions().single(), MatchAction.SamePayment, first)
            restored.restoreBackup(restored.previewBackup(r.createEncryptedBackup(password), password), protectCurrent = false)
            assertEquals(1, restored.matchDecisions().size)
            assertEquals("45000", restored.snapshot(today = month).debit.toString())
            restored.undoMatch(decision.id)
            assertEquals("90000", restored.snapshot(today = month).debit.toString())
        } finally { password.fill('\u0000'); r.eraseAll(); r.close(); restored.eraseAll(); restored.close() }
    }
}

package `in`.financeministry.app

import `in`.financeministry.app.data.*
import org.junit.Assert.*
import org.junit.Test

class TransactionMatchingTest {
    private fun row(id: String, source: String? = "daily", origin: String = "SMS", time: Long = 1_000_000) = TransactionEntity(
        id = id, sourceType = origin, sourceTimestamp = time, effectiveTimestamp = time, amountMinor = 45000,
        direction = "Debit", status = "Successful", channel = "UPI", transactionType = "Other",
        reviewState = "Confirmed", counterpartyLabel = "Corner Cafe", paymentSourceId = source,
        createdAt = time, updatedAt = time)
    private val sources = listOf(
        PaymentSourceEntity("daily", "Daily account", "Bank account", "UPI", createdAt = 1),
        PaymentSourceEntity("savings", "Savings account", "Bank account", "UPI", createdAt = 1))

    @Test fun manual_and_sms_with_same_source_amount_and_close_time_are_only_suggested() {
        val manual = row("manual", origin = "Manual")
        val sms = row("sms", time = 1_060_000)
        val found = TransactionMatching.suggest(listOf(manual, sms), sources).single()
        assertEquals(MatchKind.Duplicate, found.kind)
        assertEquals(setOf("manual", "sms"), setOf(found.first.id, found.second.id))
        assertTrue(found.evidence.contains("Same registered payment source"))
        assertNull(manual.duplicateOfId)
        assertNull(sms.duplicateOfId)
    }

    @Test fun amount_alone_or_unknown_instrument_never_suggests_a_match() {
        assertTrue(TransactionMatching.suggest(listOf(row("a", null), row("b", null)), sources).isEmpty())
        assertTrue(TransactionMatching.suggest(listOf(row("a"), row("b", "savings")), sources).isEmpty())
        assertTrue(TransactionMatching.suggest(listOf(row("a"), row("b", time = 1_300_001)), sources).isEmpty())
        assertTrue(TransactionMatching.suggest(listOf(row("a"), row("b").copy(amountMinor = 45001)), sources).isEmpty())
    }

    @Test fun distinct_references_and_unrelated_same_source_merchants_are_not_duplicates() {
        val first = row("a").copy(referenceHash = ByteArray(32) { 1 })
        val second = row("b").copy(referenceHash = ByteArray(32) { 2 })
        assertTrue(TransactionMatching.suggest(listOf(first, second), sources).isEmpty())
        assertTrue(TransactionMatching.suggest(listOf(row("a"), row("b").copy(counterpartyLabel = "Bus")), sources).isEmpty())
    }

    @Test fun transfer_requires_opposite_flows_on_two_registered_non_card_sources() {
        val debit = row("debit")
        val credit = row("credit", "savings", time = 1_060_000).copy(direction = "Credit", counterpartyLabel = null)
        assertEquals(MatchKind.OwnTransfer, TransactionMatching.suggest(listOf(debit, credit), sources).single().kind)
        assertTrue(TransactionMatching.suggest(listOf(debit, credit.copy(paymentSourceId = null)), sources).isEmpty())
        assertTrue(TransactionMatching.suggest(listOf(debit, credit.copy(paymentSourceId = "daily")), sources).isEmpty())
        assertTrue(TransactionMatching.suggest(listOf(debit, credit), sources.map { it.copy(kind = "Credit card") }).isEmpty())
    }

    @Test fun aliases_for_same_known_bank_account_are_not_own_transfers() {
        val aliases = sources.map { it.copy(bankName = "Example Bank", last4 = "1234") }
        assertTrue(TransactionMatching.suggest(listOf(row("a"), row("b", "savings").copy(direction = "Credit")), aliases).isEmpty())
    }

    @Test fun uncertain_failed_reversed_adjustments_and_already_resolved_rows_are_excluded() {
        val base = row("a")
        val variants = listOf(base.copy(reviewState = "NeedsReview"), base.copy(status = "Failed"),
            base.copy(currency = "USD"), base.copy(duplicateOfId = "older"), base.copy(transactionType = "Refund"),
            base.copy(ownership = "SelfTransfer"))
        variants.forEach { assertTrue(TransactionMatching.suggest(listOf(it, row("b")), sources).isEmpty()) }
        assertTrue(TransactionMatching.suggest(listOf(base, row("b")), sources, reversed = setOf("a")).isEmpty())
    }

    @Test fun suggestions_are_stable_dismissible_and_bounded_without_changing_rows() {
        val records = listOf(row("a"), row("b"), row("c"))
        val pairs = TransactionMatching.suggest(records, sources)
        assertEquals(3, pairs.size)
        assertEquals(pairs.map { it.key }, TransactionMatching.suggest(records.reversed(), sources).map { it.key })
        assertEquals(2, TransactionMatching.suggest(records, sources, excludedPairs = setOf(pairs.first().key)).size)
        assertEquals(1, TransactionMatching.suggest(records, sources, limit = 1).size)
    }

    @Test fun dense_equal_amount_history_has_a_bounded_honest_scan() {
        val rows = (1..100).map { row("dense-$it", source = null, time = 1_000_000L - it) }
        val result = TransactionMatching.scan(rows, sources, maxComparisons = 100)
        assertTrue(result.truncated)
        assertTrue(result.suggestions.isEmpty())
        assertEquals(100, result.comparisons)
    }
}

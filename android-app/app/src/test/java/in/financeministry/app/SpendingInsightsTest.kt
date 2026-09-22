package `in`.financeministry.app

import `in`.financeministry.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SpendingInsightsTest {
    private fun time(value: String) = LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun row(id: String, day: String, amount: Long, category: String = "Food") = TransactionEntity(
        id, sourceType = "Manual", sourceTimestamp = time(day), effectiveTimestamp = time(day), amountMinor = amount,
        direction = "Debit", status = "Successful", channel = "CashManual", transactionType = "Other",
        reviewState = "Confirmed", category = category, createdAt = time(day), updatedAt = time(day))

    @Test fun partial_month_compares_same_elapsed_days_and_personal_shares() {
        val rows = listOf(row("start", "2026-07-01", 1), row("aug", "2026-08-21", 10000),
            row("late", "2026-08-25", 99999), row("group", "2026-09-15", 30000).copy(ownership = "Group", personalShareMinor = 10000),
            row("gift", "2026-09-16", 5000).copy(ownership = "ForOther", repaymentExpected = false),
            row("review", "2026-09-16", 80000).copy(reviewState = "NeedsReview"))
        val result = SpendingInsights.calculate(rows, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 22))
        assertEquals("15000", result.total.toString())
        assertEquals("10000", result.previousTotal.toString())
        assertEquals("15000", result.categories.single().amount.toString())
        assertEquals(LocalDate.of(2026, 8, 22), result.previousLastDay)
        assertTrue(result.canCompare)
    }
    @Test fun insufficient_history_does_not_claim_savings_and_full_months_use_calendar_bounds() {
        val result = SpendingInsights.calculate(listOf(row("only", "2026-09-01", 20000)),
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 22))
        assertFalse(result.canCompare)
        val full = SpendingInsights.calculate(emptyList(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 22))
        assertEquals(LocalDate.of(2026, 3, 31), full.lastDay)
        assertEquals(LocalDate.of(2026, 2, 28), full.previousLastDay)
    }

    @Test fun refunds_received_are_reported_separately_without_reducing_spending() {
        val spend = row("spend", "2026-09-05", 10000)
        val refund = row("refund", "2026-09-06", 3000).copy(direction = "Credit", transactionType = "Refund")
        val result = SpendingInsights.calculate(listOf(spend, refund),
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 22))
        assertEquals("10000", result.total.toString())
        assertEquals("3000", result.refundsReceived.toString())
    }
}

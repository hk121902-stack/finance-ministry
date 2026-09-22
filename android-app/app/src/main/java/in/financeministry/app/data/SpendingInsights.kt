package `in`.financeministry.app.data

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CategorySpending(val category: String, val amount: BigInteger, val transactionIds: List<String>)
data class SpendingInsight(val total: BigInteger, val previousTotal: BigInteger,
    val refundsReceived: BigInteger,
    val categories: List<CategorySpending>, val firstDay: LocalDate, val lastDay: LocalDate,
    val previousFirstDay: LocalDate, val previousLastDay: LocalDate, val canCompare: Boolean,
    val transactions: List<TransactionEntity>)

/** Recorded personal shares, not bank balances or a guarantee of complete history. */
object SpendingInsights {
    fun calculate(rows: List<TransactionEntity>, selectedMonth: LocalDate, today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()): SpendingInsight {
        val start = selectedMonth.withDayOfMonth(1)
        val current = start == today.withDayOfMonth(1)
        val end = if (current) today else start.plusMonths(1).minusDays(1)
        val previous = start.minusMonths(1)
        val previousEnd = if (current) previous.withDayOfMonth(minOf(today.dayOfMonth, previous.lengthOfMonth()))
            else start.minusDays(1)
        fun day(row: TransactionEntity) = Instant.ofEpochMilli(row.effectiveTimestamp).atZone(zone).toLocalDate()
        val reversed = rows.filter { it.status == "Reversed" && it.reviewState != "NeedsReview" }
            .mapNotNull { it.linkedOriginalId }.toSet()
        val eligible = rows.filter { RepaymentAccounting.eligible(it, reversed) && it.direction == "Debit" }
        fun sum(items: List<TransactionEntity>) = items.fold(BigInteger.ZERO) { n, row -> n + BigInteger.valueOf(RepaymentAccounting.personal(row)) }
        val selected = eligible.filter { day(it) in start..end }
        val prior = eligible.filter { day(it) in previous..previousEnd }
        val refunds = rows.filter { RepaymentAccounting.eligible(it, reversed) && it.direction == "Credit" &&
            it.transactionType == "Refund" && day(it) in start..end }
        val categories = selected.groupBy { it.category }.map { (category, items) ->
            CategorySpending(category, sum(items), items.map { it.id })
        }.sortedWith(compareByDescending<CategorySpending> { it.amount }.thenBy { it.category })
        return SpendingInsight(sum(selected), sum(prior), refunds.fold(BigInteger.ZERO) { n, row -> n + BigInteger.valueOf(row.amountMinor ?: 0) },
            categories, start, end, previous, previousEnd,
            start <= today && prior.isNotEmpty() && rows.any { day(it) <= previous }, selected)
    }
}

suspend fun TransactionRepository.spendingInsights(month: LocalDate, today: LocalDate = LocalDate.now()) =
    withLedger { SpendingInsights.calculate(it.transactions().all(), month, today) }

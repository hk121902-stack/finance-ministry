package `in`.financeministry.app.data

import java.math.BigInteger

data class RepaymentSummary(
    val outstanding: BigInteger, val newlyOwed: BigInteger,
    val received: BigInteger, val monthOutstanding: BigInteger,
    val expenses: List<TransactionEntity>, val repayments: List<RepaymentEntity>,
)

object RepaymentAccounting {
    fun eligible(row: TransactionEntity, reversed: Set<String> = emptySet()): Boolean =
        row.id !in reversed && row.duplicateOfId == null && row.currency == "INR" &&
            row.amountMinor != null && row.amountMinor > 0 && row.status == "Successful" &&
            row.reviewState != "NeedsReview" && row.transactionType !in setOf("SelfTransfer", "CardRepayment") && row.ownership != "SelfTransfer"

    fun personal(row: TransactionEntity): Long = if (!row.repaymentExpected || row.ownership !in setOf("ForOther", "Group"))
        row.amountMinor ?: 0 else row.personalShareMinor ?: row.amountMinor ?: 0

    fun principal(row: TransactionEntity): Long = if (eligible(row) && row.direction == "Debit" &&
        row.repaymentExpected && row.ownership in setOf("ForOther", "Group"))
        ((row.amountMinor ?: 0) - personal(row)).coerceAtLeast(0) else 0

    fun owed(row: TransactionEntity): Long = (principal(row) - row.repaidMinor).coerceAtLeast(0)

    fun summary(rows: List<TransactionEntity>, repayments: List<RepaymentEntity>, start: Long, end: Long,
        reversed: Set<String> = emptySet()): RepaymentSummary {
        val expenses = rows.filter { eligible(it, reversed) && it.direction == "Debit" && it.repaymentExpected && it.ownership in setOf("ForOther", "Group") }
        val ids = expenses.map { it.id }.toSet()
        val month = expenses.filter { it.effectiveTimestamp in start until end }
        fun List<Long>.total() = fold(BigInteger.ZERO) { n, value -> n + BigInteger.valueOf(value) }
        return RepaymentSummary(expenses.map(::owed).total(), month.map(::principal).total(),
            repayments.filter { it.expenseId in ids && it.receivedAt?.let { time -> time in start until end } == true }
                .map { it.amountMinor }.total(), month.map(::owed).total(), expenses, repayments.filter { it.expenseId in ids })
    }

    fun validateAllocation(expense: TransactionEntity, incoming: TransactionEntity?, amount: Long, allocatedCredit: Long) {
        require(amount > 0) { "Enter a positive repayment amount." }
        require(eligible(expense) && principal(expense) > 0) { "Choose a confirmed expense with repayment expected." }
        require(amount <= owed(expense)) { "Repayment exceeds the amount still owed." }
        if (incoming != null) {
            require(eligible(incoming) && incoming.direction == "Credit" && incoming.transactionType !in setOf("Refund", "Reversal")) {
                "Choose a confirmed incoming payment, not a refund or transfer."
            }
            require(allocatedCredit >= 0 && allocatedCredit <= incoming.amountMinor!!) { "Incoming allocation is invalid." }
            require(amount <= incoming.amountMinor!! - allocatedCredit) { "The incoming payment does not have enough unallocated money." }
        }
    }
}

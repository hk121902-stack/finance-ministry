package `in`.financeministry.app.feature

import `in`.financeministry.app.data.TransactionEntity

fun transactionFlowLabel(row: TransactionEntity): String =
    if (row.transactionType == "CardRepayment") "Card bill payment" else friendly(row.direction)

/** Display copy only. Persisted enum values remain unchanged for existing ledgers. */
fun friendly(value: String): String = when (value) {
    "Debit" -> "Money out"
    "Credit" -> "Money in"
    "AutoRecorded" -> "Saved automatically"
    "NeedsReview" -> "Needs review"
    "Confirmed" -> "Confirmed by you"
    "CashManual" -> "Cash"
    "ForOther" -> "For someone else"
    "SelfTransfer" -> "Self transfer"
    "MerchantPayment" -> "Purchase"
    "CardRepayment" -> "Card bill payment"
    "Unknown" -> "Not identified"
    "SMS" -> "From SMS"
    "Manual" -> "Added manually"
    else -> value.replace(Regex("([a-z])([A-Z])"), "$1 $2")
}

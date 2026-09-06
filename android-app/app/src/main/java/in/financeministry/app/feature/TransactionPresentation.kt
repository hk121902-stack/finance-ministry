package `in`.financeministry.app.feature

/** Display copy only. Persisted enum values remain unchanged for existing ledgers. */
fun friendly(value: String): String = when (value) {
    "Debit" -> "Money out"
    "Credit" -> "Money in"
    "AutoRecorded" -> "Saved automatically"
    "NeedsReview" -> "Needs review"
    "Confirmed" -> "Confirmed by you"
    "CashManual" -> "Cash"
    "Unknown" -> "Not identified"
    "SMS" -> "From SMS"
    "Manual" -> "Added manually"
    else -> value.replace(Regex("([a-z])([A-Z])"), "$1 $2")
}

package `in`.financeministry.app.data

enum class MatchKind { Duplicate, OwnTransfer }

data class MatchSuggestion(val key: String, val kind: MatchKind,
    val first: TransactionEntity, val second: TransactionEntity, val evidence: List<String>)
data class MatchScan(val suggestions: List<MatchSuggestion>, val truncated: Boolean, val comparisons: Int)

/** Suggestions never mutate records, confirm uncertain payments or settle repayments. */
object TransactionMatching {
    private const val WINDOW_MS = 5 * 60 * 1000L
    private val accountChannels = setOf("UPI", "BankTransfer", "IMPS", "NEFT", "RTGS")

    fun pairKey(first: String, second: String): String = listOf(first, second).sorted().joinToString("") { "${it.length}:$it" }

    fun suggest(rows: List<TransactionEntity>, sources: List<PaymentSourceEntity>,
        excludedPairs: Set<String> = emptySet(), reversed: Set<String> = emptySet(), limit: Int = 200): List<MatchSuggestion> =
        scan(rows, sources, excludedPairs, reversed, limit).suggestions

    fun scan(rows: List<TransactionEntity>, sources: List<PaymentSourceEntity>,
        excludedPairs: Set<String> = emptySet(), reversed: Set<String> = emptySet(), limit: Int = 200,
        maxComparisons: Int = 50_000): MatchScan {
        require(limit in 1..1000)
        require(maxComparisons in 1..1_000_000)
        val instruments = sources.associateBy { it.id }
        val results = mutableListOf<MatchSuggestion>()
        var comparisons = 0
        // Grouping bounds comparisons to equal amounts; a sliding time window avoids
        // comparing every transaction in a large imported history with every other one.
        val groups = rows.filter { RepaymentAccounting.eligible(it, reversed) &&
            it.direction in setOf("Debit", "Credit") && it.effectiveTimestamp > 0 &&
            it.transactionType !in setOf("Refund", "Reversal") }
            .sortedWith(compareByDescending<TransactionEntity> { it.effectiveTimestamp }.thenBy { it.id })
            .groupBy { it.amountMinor }
        for (group in groups.values) for (i in group.indices) {
            val a = group[i]
            for (j in i + 1 until group.size) {
                val b = group[j]
                if (a.effectiveTimestamp - b.effectiveTimestamp > WINDOW_MS) break
                if (a.id == b.id) continue
                if (comparisons == maxComparisons) return MatchScan(results, true, comparisons)
                comparisons++
                val key = pairKey(a.id, b.id)
                if (key in excludedPairs) continue
                val sourceA = instruments[a.paymentSourceId]
                val sourceB = instruments[b.paymentSourceId]
                val sameSource = sourceA != null && sourceB != null && sourceA.id == sourceB.id &&
                    a.channel == sourceA.channel && b.channel == sourceB.channel
                val sameMaskedInstrument = a.maskedAccountHint != null && a.maskedAccountHint == b.maskedAccountHint &&
                    a.channel == b.channel && a.channel !in setOf("Unknown", "CashManual")
                val sameReference = a.referenceHash != null && b.referenceHash != null && a.referenceHash.contentEquals(b.referenceHash)
                val conflictingReferences = a.referenceHash != null && b.referenceHash != null && !sameReference
                val sameMerchant = !a.counterpartyLabel.isNullOrBlank() &&
                    a.counterpartyLabel.trim().equals(b.counterpartyLabel?.trim(), ignoreCase = true)
                val manualAndSms = setOf(a.sourceType, b.sourceType) == setOf("Manual", "SMS")
                val kind: MatchKind
                val evidence: List<String>
                if (a.direction == b.direction && !conflictingReferences &&
                    (sameSource || sameMaskedInstrument && sameReference) && (manualAndSms || sameMerchant || sameReference)) {
                    kind = MatchKind.Duplicate
                    evidence = listOf("Same amount and direction", "Within five minutes",
                        if (sameSource) "Same registered payment source" else "Matching account hint and payment reference",
                        if (manualAndSms) "Manual entry and SMS record" else if (sameReference) "Matching payment reference" else "Same merchant label")
                } else if (a.direction != b.direction && sourceA != null && sourceB != null && sourceA.id != sourceB.id &&
                    sourceA.kind in setOf("Bank account", "UPI") && sourceB.kind in setOf("Bank account", "UPI") &&
                    a.channel in accountChannels && b.channel in accountChannels && a.channel == sourceA.channel && b.channel == sourceB.channel &&
                    !(sourceA.last4 != null && sourceA.last4 == sourceB.last4 && !sourceA.bankName.isNullOrBlank() &&
                        sourceA.bankName.trim().equals(sourceB.bankName?.trim(), ignoreCase = true))) {
                    kind = MatchKind.OwnTransfer
                    evidence = listOf("Equal money out and money in", "Within five minutes", "Two registered account sources", "You must confirm these are your own accounts")
                } else continue
                val ordered = listOf(a, b).sortedBy { it.id }
                results.add(MatchSuggestion(key, kind, ordered[0], ordered[1], evidence))
                if (results.size == limit) return MatchScan(results, true, comparisons)
            }
        }
        return MatchScan(results, false, comparisons)
    }
}

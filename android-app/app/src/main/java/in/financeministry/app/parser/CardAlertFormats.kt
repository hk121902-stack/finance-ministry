package `in`.financeministry.app.parser

/** Shared grammatical layouts, independently authored from documented alert shapes. */
internal object CardAlertFormats {
    private const val money = """(?:inr|rs\.?|₹|usd|eur|gbp|aed|sgd|aud|cad|jpy)\s*[\d,.]+"""
    private const val date = """(?:\d{4}-\d{2}-\d{2}|\d{1,2}[-/](?:\d{1,2}|[a-z]{3})[-/]\d{2,4})(?!\d)"""
    private const val card = """(?:your\s+)?[a-z ]{0,60}?(?:\bcard|\bbobcard)\s+(?:ending\s+(?:with\s+)?)?[*x•]*(?<last4>\d{4})(?!\d)"""
    private const val start = """^(?:(?:alert:|transaction successful!)\s*)?(?:spent\s+$money\s+on|$money\s+(?:(?:is|was)\s+)?spent\s+(?:on|using)|$money\s+has been charged to)\s+$card"""
    private val movement = Regex(start, RegexOption.IGNORE_CASE)
    // OneCard/Federal: a completed purchase names the merchant before the card,
    // sometimes preceded by a short greeting and without a transaction date.
    private val merchantFirst = Regex("""^(?:fresh picks!\s*)?$money\s+spent\s+at\s+(?<merchant>[^\r\n]{1,80}?)\s+on\s+$card(?=\.(?:\s|$)|$)""", RegexOption.IGNORE_CASE)
    private val layouts = listOf(
        merchantFirst,
        // HDFC/SBI/BOBCARD/Federal and similar: merchant precedes the date.
        Regex("""$start(?:\s+for\s+[^\r\n]{1,50}?)?\s+at\s+(?<merchant>[^\r\n]{1,80}?)\s+on\s+$date""", RegexOption.IGNORE_CASE),
        // Kotak: the date precedes the merchant.
        Regex("""$start\s+on\s+$date\s+at\s+(?<merchant>[^\r\n]{1,80}?)(?=\.\s|$)""", RegexOption.IGNORE_CASE),
        // YES Bank: @merchant followed by a timestamp.
        Regex("""$start\s+@(?<merchant>[^\r\n]{1,80}?)\s+$date""", RegexOption.IGNORE_CASE)
    )
    fun match(text: String): MatchResult? = layouts.firstNotNullOfOrNull { it.find(text) }
    fun hasMovement(text: String): Boolean = movement.containsMatchIn(text) || merchantFirst.containsMatchIn(text)
    val foreignAmount = Regex("""(?:\b(?:usd|eur|gbp|aed|sgd|aud|cad|jpy|chf|sar|hkd|cny)|[$€£])\s*\d""", RegexOption.IGNORE_CASE)
}

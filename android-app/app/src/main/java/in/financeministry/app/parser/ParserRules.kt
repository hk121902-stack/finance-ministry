package `in`.financeministry.app.parser

import java.util.Locale

internal object ParserRules {
    val otpOrVerification = Regex("\\b(?:otp|one[ -]?time(?:[ -]?password|[ -]?code)?|verification[ -]?(?:code|number)|2fa)\\b")
    val negatedMovement = Regex("\\b(?:no transaction happened|not debited|not credited|was not debited|was not credited|did not debit|did not credit)\\b")
    val amount = Regex("(?:\\bINR\\b|\\bRs\\.?|₹)\\s*([+-]?(?:\\d{1,3}(?:,\\d{1,3})+|\\d+)(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    val debit = Regex("\\b(?:debited|debit|withdrawn)\\b")
    val credit = Regex("\\b(?:credited|credit)\\b")
    val transfer = Regex("\\b(?:transferred|transfer)\\b")
    val ownAccounts = Regex("\\b(?:between|to) your own accounts?\\b")
    val failed = Regex("\\b(?:failed|declined|decline|unsuccessful)\\b")
    val reversed = Regex("\\b(?:reversed|reversal)\\b")
    val pending = Regex("\\b(?:pending|awaiting|processing)\\b")
    val balanceOrLimit = Regex("\\b(?:(?:available|avail\\.?|avl)\\s*bal(?:ance)?|balance|credit limit|limit)\\b")
    val balanceAmountPrefix = Regex("\\b(?:(?:available|avail\\.?|avl)\\s*bal(?:ance)?|balance|credit limit|limit)\\b[\\s.:=-]*$")
    val sentPayment = Regex("""^sent\s+(?:rs\.?|inr|₹)\s*[\d,.]+\s*\r?\nfrom [^\r\n]{1,60}\b(?:a/c|account)\s+[*x•]+\d{4}\s*\r?\nto [^\r\n]{1,60}(?:\r?\n|$)""")
    private const val cardAmount = """(?:rs\.?|inr|₹)\s*[\d,.]+"""
    val cardSpend = Regex("""^(?:spent\s+$cardAmount|$cardAmount\s+spent)\s+on\s+[^\r\n]{1,60}?\bcard\s+[*xX•]*(?<last4>\d{4})(?!\d)\s+at\s+(?<merchant>[^\r\n]{1,80}?)\s+on\s+\d{4}-\d{2}-\d{2}(?::\d{2}:\d{2}:\d{2})?(?!\d)""", RegexOption.IGNORE_CASE)
    private val securityFooter = Regex("""(?:^|[\r\n.!?])\s*not you\?""", RegexOption.IGNORE_CASE)
    fun transactionText(body: String): String = securityFooter.find(body)?.let { body.substring(0, it.range.first) }?.trim() ?: body.trim()
    val promotion = Regex("\\b(?:offer|promotion|promotional|cashback|increase your credit limit|apply now)\\b")
    val futureOrReminder = Regex("\\b(?:due|tomorrow|scheduled|reminder|will be)\\b")
    val completedMovement = Regex("\\b(?:debited|credited|withdrawn|transferred)\\b|\\btransaction\\s+(?:declined|failed|reversed|pending)\\b")

    fun normalized(body: String): String = body.lowercase(Locale.ROOT)
}

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
    val balanceOrLimit = Regex("\\b(?:available balance|balance|credit limit|limit)\\b")
    val promotion = Regex("\\b(?:offer|promotion|promotional|cashback|increase your credit limit|apply now)\\b")
    val futureOrReminder = Regex("\\b(?:due|tomorrow|scheduled|reminder|will be)\\b")
    val completedMovement = Regex("\\b(?:debited|credited|withdrawn|transferred)\\b|\\btransaction\\s+(?:declined|failed|reversed|pending)\\b")

    fun normalized(body: String): String = body.lowercase(Locale.ROOT)
}

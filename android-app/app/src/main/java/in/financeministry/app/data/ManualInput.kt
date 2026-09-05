package `in`.financeministry.app.data

import `in`.financeministry.app.core.model.*
import java.math.BigDecimal

data class ManualInput(
    val amount: String, val direction: Direction, val timestamp: Long,
    val type: TransactionType, val status: TransactionStatus = TransactionStatus.Successful,
    val channel: Channel = Channel.CashManual, val label: String = "", val notes: String = "",
    val accountHint: String = "",
) {
    fun amountMinor(): Long {
        require(amount.matches(Regex("[0-9]{1,16}(\\.[0-9]{1,2})?"))) { "Enter a positive amount with up to two decimal places." }
        val value = try { BigDecimal(amount).movePointRight(2).longValueExact() } catch (_: ArithmeticException) { 0L }
        require(value > 0) { "Amount must be positive and within the supported range." }
        return value
    }
    fun validate() {
        amountMinor()
        require(direction != Direction.Unknown) { "Choose a direction." }
        require(timestamp > 0) { "Choose a valid date and time." }
        require(type != TransactionType.Unknown) { "Choose a category (Other is available)." }
        require(label.length <= 60 && notes.length <= 200) { "Keep the label under 61 and notes under 201 characters." }
        require(accountHint.isEmpty() || accountHint.matches(Regex("[0-9]{4}"))) { "Enter only the last four account digits." }
        require(!Regex("(?i)(otp|one.time.password|[a-z0-9._-]+@[a-z][a-z0-9]*|[0-9]{6,})").containsMatchIn("$label $notes")) {
            "Do not enter OTPs, UPI IDs, full account numbers, or copied messages."
        }
    }
}

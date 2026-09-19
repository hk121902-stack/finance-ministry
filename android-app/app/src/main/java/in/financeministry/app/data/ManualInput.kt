package `in`.financeministry.app.data

import `in`.financeministry.app.core.model.*
import java.math.BigDecimal

data class ManualInput(
    val amount: String, val direction: Direction, val timestamp: Long,
    val type: TransactionType, val status: TransactionStatus = TransactionStatus.Successful,
    val channel: Channel = Channel.CashManual, val label: String = "", val notes: String = "",
    val accountHint: String = "",
    val category: String = "Other", val ownership: SpendingOwnership = SpendingOwnership.Personal,
    val groupLabel: String = "", val personalShare: String = "", val repaid: String = "",
    val paymentSourceId: String? = null,
) {
    fun amountMinor(): Long {
        require(amount.matches(Regex("[0-9]{1,16}(\\.[0-9]{1,2})?"))) { "Enter a positive amount with up to two decimal places." }
        val value = try { BigDecimal(amount).movePointRight(2).longValueExact() } catch (_: ArithmeticException) { 0L }
        require(value > 0) { "Amount must be positive and within the supported range." }
        return value
    }
    fun validate() {
        val total = amountMinor()
        require(direction != Direction.Unknown) { "Choose a direction." }
        require(timestamp > 0) { "Choose a valid date and time." }
        require(type != TransactionType.Unknown) { "Choose a category (Other is available)." }
        require(status != TransactionStatus.Unknown) { "Choose a payment status." }
        require(label.length <= 60 && notes.length <= 200) { "Keep the label under 61 and notes under 201 characters." }
        require(accountHint.isEmpty() || accountHint.matches(Regex("[0-9]{4}"))) { "Enter only the last four account digits." }
        require(!Regex("(?i)(otp|one.time.password|[a-z0-9._-]+@[a-z][a-z0-9]*|[0-9]{6,})").containsMatchIn("$label $notes")) {
            "Do not enter OTPs, UPI IDs, full account numbers, or copied messages."
        }
        require(category.isNotBlank() && category.length <= 40) { "Choose a category." }
        val share = personalShareMinor(total)
        require(share in 0..total) { "Your share must be between zero and the total amount." }
        require(ownership != SpendingOwnership.Group || groupLabel.trim().isNotBlank()) { "Name the group." }
        require(repaidMinor(total, share) in 0..(total - share)) { "Repaid amount cannot exceed the amount others owe." }
    }
    fun normalizedType(): TransactionType = when {
        ownership == SpendingOwnership.SelfTransfer || type == TransactionType.SelfTransfer -> TransactionType.SelfTransfer
        else -> type
    }
    fun normalizedOwnership(): SpendingOwnership =
        if (ownership == SpendingOwnership.SelfTransfer || type == TransactionType.SelfTransfer) SpendingOwnership.SelfTransfer else ownership
    fun personalShareMinor(total: Long = amountMinor()): Long = when (ownership) {
        SpendingOwnership.Personal, SpendingOwnership.Family, SpendingOwnership.SelfTransfer -> total
        SpendingOwnership.ForOther -> 0L
        SpendingOwnership.Group -> parseOptionalAmount(personalShare, "Enter your share with up to two decimal places.")
    }
    fun repaidMinor(total: Long = amountMinor(), share: Long = personalShareMinor(total)): Long =
        if (ownership !in listOf(SpendingOwnership.ForOther, SpendingOwnership.Group) || repaid.isBlank()) 0
        else parseOptionalAmount(repaid, "Enter repaid amount with up to two decimal places.")
    private fun parseOptionalAmount(value: String, error: String): Long {
        require(value.matches(Regex("[0-9]{1,16}(\\.[0-9]{1,2})?"))) { error }
        return BigDecimal(value).movePointRight(2).longValueExact()
    }
}

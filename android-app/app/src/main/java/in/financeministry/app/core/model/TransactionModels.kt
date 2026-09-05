package `in`.financeministry.app.core.model

/** Transient parser input. Its diagnostic form intentionally excludes private SMS content. */
class IncomingSms(
    val sender: String,
    val receivedAtMillis: Long,
    val body: String,
) {
    override fun toString(): String = "IncomingSms(redacted)"
}

enum class ParseDecision { Record, NeedsReview, Reject }
enum class Direction { Debit, Credit, Transfer, Unknown }
enum class TransactionStatus { Successful, Failed, Reversed, Pending, Unknown }
enum class Channel { UPI, Card, ATM, IMPS, NEFT, RTGS, BankTransfer, CashManual, Other, Unknown }
enum class TransactionType { MerchantPayment, P2PTransfer, SelfTransfer, SalaryIncome, Refund, Reversal, CashWithdrawal, Deposit, FeeCharge, Other, Unknown }
enum class SourceType { SMS, Manual }
enum class ReviewState { AutoRecorded, NeedsReview, Confirmed }

data class ParseAssessment(
    val decision: ParseDecision,
    val amountMinor: Long?,
    val currency: String?,
    val direction: Direction,
    val status: TransactionStatus,
    val channel: Channel,
    val transactionType: TransactionType,
    val maskedAccountHint: String?,
    val counterpartyLabel: String?,
    val confidence: Int,
    val ruleId: String,
    val parserVersion: Int = 1,
)

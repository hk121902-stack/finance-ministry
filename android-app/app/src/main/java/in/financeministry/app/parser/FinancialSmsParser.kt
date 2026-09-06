package `in`.financeministry.app.parser

import `in`.financeministry.app.core.model.Channel
import `in`.financeministry.app.core.model.Direction
import `in`.financeministry.app.core.model.IncomingSms
import `in`.financeministry.app.core.model.ParseAssessment
import `in`.financeministry.app.core.model.ParseDecision
import `in`.financeministry.app.core.model.TransactionStatus
import `in`.financeministry.app.core.model.TransactionType
import java.math.BigInteger

class RuleBasedFinancialSmsParser {
    fun parse(input: IncomingSms): ParseAssessment {
        val result = parseFields(input)
        if (result.decision == ParseDecision.Reject) return result
        val hints = Regex("""\b(?:a/c|accounts?|cards?)\s+[*xX•]+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
            .findAll(input.body).map { "••••${it.groupValues[1]}" }.distinct().toList()
        val counterparty = if (ParserRules.sentPayment.containsMatchIn(ParserRules.normalized(input.body))) {
            Regex("""(?m)^To ([A-Za-z][A-Za-z .&'-]{0,59})\r?$""").find(input.body)?.groupValues?.get(1)?.trim()
        } else null
        return result.copy(maskedAccountHint = hints.singleOrNull(), counterpartyLabel = counterparty)
    }

    private fun parseFields(input: IncomingSms): ParseAssessment {
        // Security instructions are not evidence of the transaction's channel or state.
        val text = ParserRules.normalized(input.body).substringBefore("\nnot you?").trim()
        val completed = ParserRules.completedMovement.containsMatchIn(text) || ParserRules.sentPayment.containsMatchIn(text)

        if (ParserRules.otpOrVerification.containsMatchIn(ParserRules.normalized(input.body))) {
            return rejected("otp_or_verification")
        }
        if (ParserRules.negatedMovement.containsMatchIn(text)) {
            return rejected("negated_or_non_transaction")
        }
        if (Regex("\\b(?:will be|to be|scheduled to be) (?:debited|credited)|\\b(?:debit|payment|transfer) (?:request|reminder|due)\\b").containsMatchIn(text)) {
            return rejected("promotional_or_scheduled")
        }
        if (ParserRules.balanceOrLimit.containsMatchIn(text) && !completed &&
            !ParserRules.failed.containsMatchIn(text) && !ParserRules.reversed.containsMatchIn(text)) {
            return rejected("balance_or_limit_only")
        }

        if (
            (ParserRules.promotion.containsMatchIn(text) || ParserRules.futureOrReminder.containsMatchIn(text)) &&
            !completed
        ) {
            return rejected("promotional_or_scheduled")
        }
        val direction = detectDirection(text)
        val status = detectStatus(text, direction)
        val decisive = direction != Direction.Unknown || status != TransactionStatus.Unknown || Regex("\\brefund(?:ed)?\\b").containsMatchIn(text)
        if (hasMultipleCandidateAmounts(text) && completed) {
            return assessment(
                decision = ParseDecision.NeedsReview,
                amountMinor = null,
                direction = direction,
                status = status,
                channel = detectChannel(text),
                transactionType = detectTransactionType(text, detectChannel(text)),
                confidence = 60,
                ruleId = "ambiguous_multiple_events",
            )
        }
        if (!decisive) {
            return rejected("no_decisive_transaction")
        }

        val amount = safeSingleAmount(text)
        val channel = detectChannel(text)
        val transactionType = detectTransactionType(text, channel)
        if (amount == null) {
            return assessment(
                decision = ParseDecision.NeedsReview,
                amountMinor = null,
                direction = direction,
                status = status,
                channel = channel,
                transactionType = transactionType,
                confidence = 60,
                ruleId = "decisive_missing_safe_amount",
            )
        }

        val confidence = if (direction != Direction.Unknown && status != TransactionStatus.Unknown) 96 else 70
        val decision = if (confidence >= 90) ParseDecision.Record else ParseDecision.NeedsReview
        return assessment(
            decision = decision,
            amountMinor = amount,
            direction = direction,
            status = status,
            channel = channel,
            transactionType = transactionType,
            confidence = confidence,
            ruleId = "currency_amount_transaction",
        )
    }

    private fun detectDirection(text: String): Direction {
        if (ParserRules.ownAccounts.containsMatchIn(text)) return Direction.Transfer
        val debit = ParserRules.debit.containsMatchIn(text) || ParserRules.sentPayment.containsMatchIn(text)
        val credit = ParserRules.credit.containsMatchIn(text)
        if (debit && credit) return Direction.Unknown
        if (debit) return Direction.Debit
        if (credit) return Direction.Credit
        if (ParserRules.transfer.containsMatchIn(text)) return Direction.Transfer
        return Direction.Unknown
    }

    private fun detectStatus(text: String, direction: Direction): TransactionStatus = when {
        ParserRules.failed.containsMatchIn(text) -> TransactionStatus.Failed
        ParserRules.reversed.containsMatchIn(text) -> TransactionStatus.Reversed
        ParserRules.pending.containsMatchIn(text) -> TransactionStatus.Pending
        direction != Direction.Unknown -> TransactionStatus.Successful
        else -> TransactionStatus.Unknown
    }

    private fun detectChannel(text: String): Channel = when {
        Regex("\\bupi\\b").containsMatchIn(text) -> Channel.UPI
        Regex("\\batm\\b").containsMatchIn(text) -> Channel.ATM
        Regex("\\bcard\\b").containsMatchIn(text) -> Channel.Card
        Regex("\\bimps\\b").containsMatchIn(text) -> Channel.IMPS
        Regex("\\bneft\\b").containsMatchIn(text) -> Channel.NEFT
        Regex("\\brtgs\\b").containsMatchIn(text) -> Channel.RTGS
        ParserRules.ownAccounts.containsMatchIn(text) -> Channel.BankTransfer
        else -> Channel.Unknown
    }

    private fun detectTransactionType(text: String, channel: Channel): TransactionType = when {
        Regex("\\brefund\\b").containsMatchIn(text) -> TransactionType.Refund
        ParserRules.reversed.containsMatchIn(text) -> TransactionType.Reversal
        Regex("\\bsalary\\b").containsMatchIn(text) -> TransactionType.SalaryIncome
        Regex("\\b(?:fee|charge)\\b").containsMatchIn(text) -> TransactionType.FeeCharge
        Regex("\\b(?:cash withdrawn|withdrawn)\\b").containsMatchIn(text) -> TransactionType.CashWithdrawal
        ParserRules.ownAccounts.containsMatchIn(text) -> TransactionType.SelfTransfer
        channel == Channel.Card -> TransactionType.MerchantPayment
        else -> TransactionType.Unknown
    }

    private fun safeSingleAmount(text: String): Long? {
        val candidates = amountCandidates(text)
        return if (candidates.size == 1) candidates.single() else null
    }

    private fun hasMultipleCandidateAmounts(text: String): Boolean = amountCandidates(text).size > 1

    private fun amountCandidates(text: String): List<Long?> =
        ParserRules.amount.findAll(text)
            .filterNot { match -> ParserRules.balanceAmountPrefix.containsMatchIn(text.substring(maxOf(0, match.range.first - 40), match.range.first)) }
            .map { match -> parseMinorUnits(match.groupValues[1]) }
            .toList()

    private fun parseMinorUnits(raw: String): Long? {
        if (raw.startsWith("+") || raw.startsWith("-")) return null
        val parts = raw.split('.')
        if (parts.size > 2) return null
        val integer = parts[0]
        if (!isValidInteger(integer)) return null
        val fraction = parts.getOrElse(1) { "" }
        if (fraction.length > 2 || !fraction.all(Char::isDigit)) return null

        val whole = BigInteger(integer.replace(",", ""))
        val minor = whole.multiply(BigInteger.valueOf(100)).add(BigInteger((fraction + "00").take(2)))
        return if (minor > BigInteger.ZERO && minor <= BigInteger.valueOf(Long.MAX_VALUE)) minor.toLong() else null
    }

    private fun isValidInteger(value: String): Boolean =
        value.matches(Regex("\\d+")) || value.matches(Regex("\\d{1,3}(?:,\\d{2})*,\\d{3}"))

    private fun rejected(ruleId: String): ParseAssessment = assessment(
        decision = ParseDecision.Reject,
        amountMinor = null,
        direction = Direction.Unknown,
        status = TransactionStatus.Unknown,
        channel = Channel.Unknown,
        transactionType = TransactionType.Unknown,
        confidence = 100,
        ruleId = ruleId,
    )

    private fun assessment(
        decision: ParseDecision,
        amountMinor: Long?,
        direction: Direction,
        status: TransactionStatus,
        channel: Channel,
        transactionType: TransactionType,
        confidence: Int,
        ruleId: String,
    ) = ParseAssessment(
        decision = decision,
        amountMinor = amountMinor,
        currency = if (amountMinor == null) null else "INR",
        direction = direction,
        status = status,
        channel = channel,
        transactionType = transactionType,
        maskedAccountHint = null,
        counterpartyLabel = null,
        confidence = confidence,
        ruleId = ruleId,
        parserVersion = 2,
    )
}

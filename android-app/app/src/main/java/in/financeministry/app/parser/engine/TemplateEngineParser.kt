package `in`.financeministry.app.parser.engine

import `in`.financeministry.app.core.model.Channel
import `in`.financeministry.app.core.model.Direction
import `in`.financeministry.app.core.model.IncomingSms
import `in`.financeministry.app.core.model.ParseAssessment
import `in`.financeministry.app.core.model.ParseDecision
import `in`.financeministry.app.core.model.TransactionStatus
import `in`.financeministry.app.core.model.TransactionType
import `in`.financeministry.app.parser.ParserRules
import java.math.BigInteger
import java.util.Locale

class TemplateEngineParser(
    private val templateRepository: TemplateRepository = InMemoryTemplateRepository()
) {
    fun parse(input: IncomingSms): ParseAssessment {
        val text = ParserRules.normalized(ParserRules.transactionText(input.body))

        // 1. Negative Guards
        if (ParserRules.otpOrVerification.containsMatchIn(ParserRules.normalized(input.body))) {
            return rejected("otp_or_verification")
        }
        if (ParserRules.negatedMovement.containsMatchIn(text)) {
            return rejected("negated_or_non_transaction")
        }
        if (Regex("\\b(?:will be|to be|scheduled to be) (?:debited|credited)|\\b(?:debit|payment|transfer) (?:request|reminder|due)\\b").containsMatchIn(text)) {
            return rejected("promotional_or_scheduled")
        }
        val completed = ParserRules.completedMovement.containsMatchIn(text) || ParserRules.sentPayment.containsMatchIn(text) || ParserRules.cardSpend.containsMatchIn(text) || hasCardMovementFallback(text) || hasMovementFallback(text)

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

        // We check for multiple amounts and ambiguous currencies just like the old parser
        if (hasMultipleCandidateAmounts(text) && completed) {
            return assessment(
                decision = ParseDecision.NeedsReview,
                amountMinor = null,
                direction = detectFallbackDirection(text),
                status = detectFallbackStatus(text, detectFallbackDirection(text)),
                channel = detectFallbackChannel(text),
                transactionType = detectFallbackTransactionType(text, detectFallbackChannel(text)),
                confidence = 60,
                ruleId = "ambiguous_multiple_events"
            )
        }

        if (Regex("""(?:\b(?:usd|eur|gbp|aed|sgd|aud|cad|jpy|chf|sar|hkd|cny)|[$€£])\s*\d""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return assessment(
                ParseDecision.NeedsReview, null, detectFallbackDirection(text), detectFallbackStatus(text, detectFallbackDirection(text)), detectFallbackChannel(text),
                detectFallbackTransactionType(text, detectFallbackChannel(text)), 60, "unsupported_currency")
        }



        // 1.5 Mixed movement guard
        val fallbackDirection = detectFallbackDirection(text)
        if (fallbackDirection == Direction.Unknown && ParserRules.debit.containsMatchIn(text) && ParserRules.credit.containsMatchIn(text)) {
            // It's a mixed movement not handled safely by ICICI exception
            return assessment(ParseDecision.NeedsReview, safeSingleAmount(text), Direction.Unknown, TransactionStatus.Unknown, Channel.Unknown, TransactionType.Unknown, 60, "mixed_movement")
        }

        // 2. Try strict templates
        val templates = allTemplates()
        for (template in templates) {
            val regex = Regex(template.regexPattern, RegexOption.IGNORE_CASE)
            val transactionText = ParserRules.transactionText(input.body)
            val match = regex.find(transactionText)
            if (match != null) {
                // If it is the iciciDebitRecipientCredit template, we have a guard check
                if (template.templateId == "icici_debit_recipient_credit") {
                    val recipient = match.groups["merchant"]?.value ?: ""
                    val accountOrMovement = Regex("""\b(?:accounts?|acct|a/c|debited|credited|transferred|not)\b""")
                    if (accountOrMovement.containsMatchIn(recipient)) {
                        continue
                    }
                }


                var amountStr: String? = null
                var merchant: String? = null
                var accountRaw: String? = null

                try { amountStr = amountStr ?: match.groups["amount"]?.value } catch (e: IllegalArgumentException) {}
                try { amountStr = amountStr ?: match.groups["anyAmount"]?.value } catch (e: IllegalArgumentException) {}
                try { amountStr = amountStr ?: match.groups["amount1"]?.value } catch (e: IllegalArgumentException) {}
                try { amountStr = amountStr ?: match.groups["amount2"]?.value } catch (e: IllegalArgumentException) {}
                try { amountStr = amountStr ?: match.groups["amount3"]?.value } catch (e: IllegalArgumentException) {}

                try { merchant = match.groups["merchant"]?.value?.trim() } catch (e: IllegalArgumentException) {}

                try { accountRaw = accountRaw ?: match.groups["account"]?.value } catch (e: IllegalArgumentException) {}
                try { accountRaw = accountRaw ?: match.groups["account1"]?.value } catch (e: IllegalArgumentException) {}
                try { accountRaw = accountRaw ?: match.groups["account2"]?.value } catch (e: IllegalArgumentException) {}
                try { accountRaw = accountRaw ?: match.groups["account3"]?.value } catch (e: IllegalArgumentException) {}
                try { accountRaw = accountRaw ?: match.groups["cardAccount"]?.value } catch (e: IllegalArgumentException) {}

                val parsedAmount = amountStr?.let { parseMinorUnits(it) } ?: safeSingleAmount(text)
                if (parsedAmount == null) {
                    return assessment(
                        decision = ParseDecision.NeedsReview,
                        amountMinor = null,
                        direction = template.direction,
                        status = template.status,
                        channel = template.channel,
                        transactionType = template.transactionType,
                        confidence = 60,
                        ruleId = "decisive_missing_safe_amount"
                    )
                }

                var finalDecision = template.decision
                var ruleId = template.ruleId
                if (finalDecision == ParseDecision.Record) {
                    val secondary = hasSecondaryConfirmation(text)
                    val unfamiliarCard = false
                    if (secondary) {
                        finalDecision = ParseDecision.NeedsReview
                        ruleId = "secondary_payment_confirmation"
                    } else if (unfamiliarCard) {
                        finalDecision = ParseDecision.NeedsReview
                        ruleId = "unfamiliar_card_layout"
                    } else {
                        ruleId = "currency_amount_transaction"
                    }
                }

                val confidence = if (finalDecision == ParseDecision.Record) 96 else 70

                val hints = mutableListOf<String>()
                if (accountRaw != null) {
                    hints.add("••••$accountRaw")
                }

                val fallbackHints = (Regex("""\b(?:a/c|accounts?|cards?)\s+[*xX•]+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
                    .findAll(text).map { "••••${it.groupValues[1]}" }.toList()).distinct()
                hints.addAll(fallbackHints)

                val finalMaskedAccount = hints.distinct().singleOrNull()

                return ParseAssessment(
                    decision = finalDecision,
                    amountMinor = parsedAmount,
                    currency = "INR",
                    direction = template.direction,
                    status = template.status,
                    channel = template.channel,
                    transactionType = template.transactionType,
                    maskedAccountHint = finalMaskedAccount,
                    counterpartyLabel = merchant,
                    confidence = confidence,
                    ruleId = ruleId,
                    parserVersion = 6
                )
            }
        }

        // 3. Fallback logic similar to old parser for generic cases that don't match templates fully
        val direction = detectFallbackDirection(text)
        val status = detectFallbackStatus(text, direction)
        val decisive = completed || direction != Direction.Unknown ||
            (status != TransactionStatus.Unknown && Regex("\\b(?:transaction|payment)\\b").containsMatchIn(text)) ||
            Regex("\\brefund(?:ed)?\\b").containsMatchIn(text)

        if (!decisive) {
            return rejected("no_decisive_transaction")
        }

        val channel = detectFallbackChannel(text)
        val transactionType = detectFallbackTransactionType(text, channel)
        val amount = safeSingleAmount(text)
        if (amount == null) {
            return assessment(
                decision = ParseDecision.NeedsReview,
                amountMinor = null,
                direction = direction,
                status = status,
                channel = channel,
                transactionType = transactionType,
                confidence = 60,
                ruleId = "decisive_missing_safe_amount"
            )
        }

        val secondary = hasSecondaryConfirmation(text)
        val unfamiliarCard = hasCardMovementFallback(text)
        val confidence = if (direction != Direction.Unknown && status != TransactionStatus.Unknown && !secondary && !unfamiliarCard) 96 else 70
        val decision = if (confidence >= 90) ParseDecision.Record else ParseDecision.NeedsReview

        val fallbackHints = (Regex("""\b(?:a/c|accounts?|cards?)\s+[*xX•]+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
            .findAll(text).map { "••••${it.groupValues[1]}" }.toList()).distinct()
        val finalMaskedAccount = fallbackHints.singleOrNull()


        return assessment(
            decision = decision,
            amountMinor = amount,
            direction = direction,
            status = status,
            channel = channel,
            transactionType = transactionType,
            confidence = confidence,
            ruleId = if (secondary) "secondary_payment_confirmation" else if (unfamiliarCard) "unfamiliar_card_layout" else "currency_amount_transaction",
        ).copy(
            maskedAccountHint = finalMaskedAccount,
            counterpartyLabel = if (ParserRules.sentPayment.containsMatchIn(text)) {
                Regex("""(?m)^To ([A-Za-z][A-Za-z .&'-]{0,59})\r?$""").find(input.body)?.groupValues?.get(1)?.trim()
            } else null
        )
    }

    private fun detectFallbackDirection(text: String): Direction {
        if (ParserRules.ownAccounts.containsMatchIn(text)) return Direction.Transfer
        val debit = ParserRules.debit.containsMatchIn(text) || ParserRules.sentPayment.containsMatchIn(text) || ParserRules.cardSpend.containsMatchIn(text) || hasCardMovementFallback(text) || hasDebitFallback(text)
        val credit = ParserRules.credit.containsMatchIn(text) || hasCreditFallback(text)
        if (debit && credit) return if (ParserRules.isIciciAccountDebit(text)) Direction.Debit else Direction.Unknown
        if (debit) return Direction.Debit
        if (credit) return Direction.Credit
        if (Regex("\\btransferred\\b").containsMatchIn(text)) return Direction.Transfer
        return Direction.Unknown
    }

    private fun detectFallbackStatus(text: String, direction: Direction): TransactionStatus = when {
        ParserRules.failed.containsMatchIn(text) -> TransactionStatus.Failed
        ParserRules.reversed.containsMatchIn(text) -> TransactionStatus.Reversed
        ParserRules.pending.containsMatchIn(text) || Regex("""^(?:dear customer,\s*)?refund of\s+(?:rs\.?|inr|₹)\s*[\d,.]+\s+for your\s+.{1,100}\border\s*#?\w+\s+is initiated\.""").containsMatchIn(text) -> TransactionStatus.Pending
        direction != Direction.Unknown -> TransactionStatus.Successful
        else -> TransactionStatus.Unknown
    }

    private fun detectFallbackChannel(text: String): Channel = when {
        ParserRules.cardSpend.containsMatchIn(text) || hasCardMovementFallback(text) || hasCardFallback(text) -> Channel.Card
        Regex("\\bupi\\b").containsMatchIn(text) -> Channel.UPI
        Regex("\\batm\\b").containsMatchIn(text) -> Channel.ATM
        Regex("\\bcard\\b").containsMatchIn(text) -> Channel.Card
        Regex("\\bimps\\b").containsMatchIn(text) -> Channel.IMPS
        Regex("\\bneft\\b").containsMatchIn(text) -> Channel.NEFT
        Regex("\\brtgs\\b").containsMatchIn(text) -> Channel.RTGS
        ParserRules.ownAccounts.containsMatchIn(text) -> Channel.BankTransfer
        else -> Channel.Unknown
    }

    private fun detectFallbackTransactionType(text: String, channel: Channel): TransactionType = when {
        Regex("""^(?:payment of (?:rs\.?|inr|₹)\s*[\d,.]+ has been received (?:on|towards) your (?:icici|axis) bank credit card [x*]+\d{4}\b|dear hdfcbank cardmember,\s*payment of (?:rs\.?|inr|₹)\s*[\d,.]+ received towards your credit card ending with \d{4}\b|hdfc bank cardmember, online payment of (?:rs\.?|inr|₹)\s*[\d,.]+ vide ref# [^\r\n]{1,80} was credited to your card ending \d{4}\b)""").containsMatchIn(text) -> TransactionType.CardRepayment
        Regex("\\brefund(?:ed)?\\b").containsMatchIn(text) -> TransactionType.Refund
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
        if (candidates.isEmpty()) return Regex("""^we confirm receipt of online payment made via bbpay for ([\d,.]+) against lpg refill booking no:\s*\d+\.your delivery authentication code is \d+\s*- hpcl\s*$""").matchEntire(text)?.groupValues?.get(1)?.let(::parseMinorUnits)
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


    private fun hasCardMovementFallback(text: String): Boolean = Regex("""^(?:(?:alert:|transaction successful!)\s*)?(?:spent\s+(?:inr|rs\.?|₹|usd|eur|gbp|aed|sgd|aud|cad|jpy)\s*[\d,.]+\s+on|(?:inr|rs\.?|₹|usd|eur|gbp|aed|sgd|aud|cad|jpy)\s*[\d,.]+\s+(?:(?:is|was)\s+)?spent\s+(?:on|using)|(?:inr|rs\.?|₹|usd|eur|gbp|aed|sgd|aud|cad|jpy)\s*[\d,.]+\s+has been charged to)\s+(?:your\s+)?[a-z ]{0,60}?(?:\bcard|\bbobcard)\s+(?:ending\s+(?:with\s+)?)?[*x•]*\d{4}(?!\d)""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    private fun hasMovementFallback(text: String): Boolean = hasDebitFallback(text) || hasCreditFallback(text)

    private fun hasSecondaryConfirmation(text: String): Boolean = Regex("""^autopay \(e-mandate\) success!\s*\r?\nfor [^\r\n]+\r?\ntxn amt:\s*(?:rs\.?|inr|₹)\s*[\d,.]+\s*\r?\ndt:\d{2}/\d{2}/\d{2,4}\s*\r?\nvia:hdfc bank cc\s+\d{4}\b""").containsMatchIn(text) || Regex("""^payment of (?:rs\.?|inr|₹)\s*[\d,.]+ using apay balance is successful at a\.in\.""").containsMatchIn(text) || Regex("""^dear user,\s*challan payment of (?:rs\.?|inr|₹)\s*[\d,.]+ against pan/tan \S+ for assessment year \d{4} has been successfully paid\.""").containsMatchIn(text) || Regex("""^hi [^,\r\n]{1,80}, we have received a payment of (?:rs\.?|inr|₹)\s*[\d,.]+ for your airtel wi-fi id \S+""").containsMatchIn(text) || Regex("""^we confirm receipt of online payment made via bbpay for ([\d,.]+) against lpg refill booking no:\s*\d+\.your delivery authentication code is \d+\s*- hpcl\s*$""").containsMatchIn(text)

    private fun hasCardFallback(text: String): Boolean = Regex("""^spent\s+(?:rs\.?|inr|₹)\s*[\d,.]+\s*\r?\naxis bank card no\.\s+[x*]+\d{4}\s*\r?\n\d{2}-\d{2}-\d{2,4}\s+\d{2}:\d{2}:\d{2}\s+ist\s*\r?\n[^\r\n]+\r?\navl limit:""").containsMatchIn(text) || Regex("""^txn\s+(?:rs\.?|inr|₹)\s*[\d,.]+\s*\r?\non hdfc bank card\s+\d{4}\s*\r?\nat [^\r\n]+\r?\nby upi\s+\d{12}\s*\r?\non\s+\d{2}-\d{2}\b""").containsMatchIn(text) || Regex("""^(?:rs\.?|inr|₹)\s*[\d,.]+\s+spent using icici bank card\s+[x*]+\d{4}\s+on\s+\d{2}-[a-z]{3}-\d{2,4}\s+on\s+.+?\.\s*avl limit:""").containsMatchIn(text) || Regex("""^autopay \(e-mandate\) success!\s*\r?\nfor [^\r\n]+\r?\ntxn amt:\s*(?:rs\.?|inr|₹)\s*[\d,.]+\s*\r?\ndt:\d{2}/\d{2}/\d{2,4}\s*\r?\nvia:hdfc bank cc\s+\d{4}\b""").containsMatchIn(text)

    private fun hasDebitFallback(text: String): Boolean = hasSecondaryConfirmation(text) || hasCardFallback(text) || Regex("""^(?:rs\.?|inr|₹)\s*[\d,.]+\s+dr\.\s+from\s+a/c\s+[x*]+\d{3,4}\s+and\s+cr\.\s+to\s+\S+\s+ref:\d{8,24}\.""").containsMatchIn(text) || Regex("""^(?:rs\.?|inr|₹)\s*[\d,.]+\s+spent from pluxee\s+meal wallet,\s*card no\.(?:[x*]+\d{4})?\s+on\s+\d{1,2}-\d{1,2}-\d{2,4}\s+\d{1,2}:\d{1,2}:\d{1,2}\s+at\s+.+?\.\s*avl bal""").containsMatchIn(text) || Regex("""^(?:rs\.?|inr|₹)\s*[\d,.]+\s+deducted from your pluxee card\s+[x*]+\d{4}\s+towards online convenience fee\.\s*pluxee\s*$""").containsMatchIn(text) || Regex("""^upi mandate:\s*\r?\nsent\s+(?:rs\.?|inr|₹)\s*[\d,.]+\s*\r?\nfrom hdfc bank a/c\s+[*x]*\d{4}\s*\r?\nto [^\r\n]+\r?\n\d{2}/\d{2}/\d{2,4}\s*\r?\nref\s+\d{8,24}\b""").containsMatchIn(text)

    private fun hasCreditFallback(text: String): Boolean = Regex("""^received!\s*\r?\n(?:rs\.?|inr|₹)\s*[\d,.]+\s+in hdfc bank a/c\s+[x*]+\d{4}\s*\r?\non\s+\d{2}-\d{2}-\d{2,4}\s*\r?\nfor imps\s*-""").containsMatchIn(text) || Regex("""^update!\s*(?:rs\.?|inr|₹)\s*[\d,.]+\s+deposited in hdfc bank a/c\s+[x*]+\d{4}\s+on\s+\d{2}-[a-z]{3}-\d{2,4}\s+for neft\s+cr-""").containsMatchIn(text) || Regex("""^alert!\s*(?:rs\.?|inr|₹)\s*[\d,.]+\s+refunded by\s+.+\s+on\s+\d{2}/[a-z]{3}/\d{2,4}\s*& adjusted against hdfc bank credit card\s+\d{4}\b""").containsMatchIn(text) || Regex("""^(?:dear customer,\s*)?refund of\s+(?:rs\.?|inr|₹)\s*[\d,.]+\s+for your\s+.{1,100}\border\s*#?\w+\s+is initiated\.""").containsMatchIn(text) || Regex("""^(?:payment of (?:rs\.?|inr|₹)\s*[\d,.]+ has been received (?:on|towards) your (?:icici|axis) bank credit card [x*]+\d{4}\b|dear hdfcbank cardmember,\s*payment of (?:rs\.?|inr|₹)\s*[\d,.]+ received towards your credit card ending with \d{4}\b|hdfc bank cardmember, online payment of (?:rs\.?|inr|₹)\s*[\d,.]+ vide ref# [^\r\n]{1,80} was credited to your card ending \d{4}\b)""").containsMatchIn(text)

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
        parserVersion = 6,
    )
}

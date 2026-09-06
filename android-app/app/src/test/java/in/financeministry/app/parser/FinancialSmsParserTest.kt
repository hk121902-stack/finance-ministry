package `in`.financeministry.app.parser

import `in`.financeministry.app.core.model.Channel
import `in`.financeministry.app.core.model.Direction
import `in`.financeministry.app.core.model.IncomingSms
import `in`.financeministry.app.core.model.ParseDecision
import `in`.financeministry.app.core.model.TransactionStatus
import `in`.financeministry.app.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FinancialSmsParserTest {
    private val parser = RuleBasedFinancialSmsParser()

    @Test
    fun otp_is_rejected_even_with_amount() {
        val result = parse("OTP 123456 for INR 250 UPI transaction")

        assertEquals(ParseDecision.Reject, result.decision)
        assertEquals("otp_or_verification", result.ruleId)
    }

    @Test
    fun decisive_debit_without_currency_needs_review() {
        val result = parse("Your account was debited by 250 through transfer")

        assertEquals(ParseDecision.NeedsReview, result.decision)
        assertNull(result.amountMinor)
        assertEquals(Direction.Debit, result.direction)
    }

    @Test
    fun successful_upi_debit_is_recordable() {
        val result = parse("INR 250.50 debited from your account via UPI")

        assertEquals(ParseDecision.Record, result.decision)
        assertEquals(25_050L, result.amountMinor)
        assertEquals(Direction.Debit, result.direction)
        assertEquals(TransactionStatus.Successful, result.status)
        assertEquals(Channel.UPI, result.channel)
        assertEquals(TransactionType.Unknown, result.transactionType)
    }

    @Test
    fun synthetic_regressions_have_literal_normalized_outcomes() {
        val cases = listOf(
            Case("UPI credit", "₹1,250.00 credited to your account via UPI", ParseDecision.Record, 125_000L, Direction.Credit, TransactionStatus.Successful, Channel.UPI, TransactionType.Unknown),
            Case("Indian grouping", "INR 1,25,000.25 debited from your account", ParseDecision.Record, 12_500_025L, Direction.Debit, TransactionStatus.Successful, Channel.Unknown, TransactionType.Unknown),
            Case("Card failure", "Rs. 10 card debit transaction declined", ParseDecision.Record, 1_000L, Direction.Debit, TransactionStatus.Failed, Channel.Card, TransactionType.MerchantPayment),
            Case("Refund", "INR 99.00 refund credited to your account", ParseDecision.Record, 9_900L, Direction.Credit, TransactionStatus.Successful, Channel.Unknown, TransactionType.Refund),
            Case("Reversal", "INR 200.00 debit transaction reversed", ParseDecision.Record, 20_000L, Direction.Debit, TransactionStatus.Reversed, Channel.Unknown, TransactionType.Reversal),
            Case("ATM withdrawal", "INR 1,000 cash withdrawn at ATM", ParseDecision.Record, 100_000L, Direction.Debit, TransactionStatus.Successful, Channel.ATM, TransactionType.CashWithdrawal),
            Case("IMPS transfer", "INR 500 debited through IMPS transfer", ParseDecision.Record, 50_000L, Direction.Debit, TransactionStatus.Successful, Channel.IMPS, TransactionType.Unknown),
            Case("NEFT transfer", "INR 600 debited through NEFT transfer", ParseDecision.Record, 60_000L, Direction.Debit, TransactionStatus.Successful, Channel.NEFT, TransactionType.Unknown),
            Case("RTGS transfer", "INR 700 debited through RTGS transfer", ParseDecision.Record, 70_000L, Direction.Debit, TransactionStatus.Successful, Channel.RTGS, TransactionType.Unknown),
            Case("Salary", "INR 50,000 salary credited to your account", ParseDecision.Record, 5_000_000L, Direction.Credit, TransactionStatus.Successful, Channel.Unknown, TransactionType.SalaryIncome),
            Case("Fee", "INR 25 fee debited from your account", ParseDecision.Record, 2_500L, Direction.Debit, TransactionStatus.Successful, Channel.Unknown, TransactionType.FeeCharge),
            Case("Merchant-less debit", "INR 750 debited from your account", ParseDecision.Record, 75_000L, Direction.Debit, TransactionStatus.Successful, Channel.Unknown, TransactionType.Unknown),
            Case("Own-account transfer", "INR 500 transferred between your own accounts", ParseDecision.Record, 50_000L, Direction.Transfer, TransactionStatus.Successful, Channel.BankTransfer, TransactionType.SelfTransfer),
            Case("Balance only", "Available balance: INR 900", ParseDecision.Reject, null, Direction.Unknown, TransactionStatus.Unknown, Channel.Unknown, TransactionType.Unknown),
            Case("Credit limit offer", "Increase your credit limit to INR 50,000 today", ParseDecision.Reject, null, Direction.Unknown, TransactionStatus.Unknown, Channel.Unknown, TransactionType.Unknown),
            Case("Excessive fraction", "INR 12.345 debited from your account", ParseDecision.NeedsReview, null, Direction.Debit, TransactionStatus.Successful, Channel.Unknown, TransactionType.Unknown),
            Case("Overflow", "INR 92233720368547758.08 debited from your account", ParseDecision.NeedsReview, null, Direction.Debit, TransactionStatus.Successful, Channel.Unknown, TransactionType.Unknown),
            Case("Transaction before balance", "INR 100 debited; available balance INR 900", ParseDecision.Record, 10_000L, Direction.Debit, TransactionStatus.Successful, Channel.Unknown, TransactionType.Unknown),
            Case("Two movements", "INR 100 debited from your account. INR 200 credited to your account.", ParseDecision.NeedsReview, null, Direction.Unknown, TransactionStatus.Unknown, Channel.Unknown, TransactionType.Unknown),
            Case("Future payment", "INR 500 payment due tomorrow", ParseDecision.Reject, null, Direction.Unknown, TransactionStatus.Unknown, Channel.Unknown, TransactionType.Unknown),
            Case("Negated debit", "No transaction happened; account not debited", ParseDecision.Reject, null, Direction.Unknown, TransactionStatus.Unknown, Channel.Unknown, TransactionType.Unknown),
        )

        cases.forEach { case ->
            val result = parse(case.body)

            assertEquals(case.name, case.decision, result.decision)
            assertEquals(case.name, case.amountMinor, result.amountMinor)
            assertEquals(case.name, case.direction, result.direction)
            assertEquals(case.name, case.status, result.status)
            assertEquals(case.name, case.channel, result.channel)
            assertEquals(case.name, case.transactionType, result.transactionType)
            assertEquals(case.name, null, result.counterpartyLabel)
        }
    }

    @Test
    fun transient_input_to_string_does_not_expose_raw_sms_fields() {
        val input = IncomingSms("SYNTHETIC-SENDER", 1_700_000_000_100, "INR 300 debited from account 123456")

        assertFalse(input.toString().contains("SYNTHETIC-SENDER"))
        assertFalse(input.toString().contains("INR 300 debited from account 123456"))
    }

    private fun parse(body: String) = parser.parse(IncomingSms("SYNTHETIC-BANK", 1_700_000_000_000, body))

    @Test fun structured_card_spend_variants_record_safe_fields() {
        for (prefix in listOf("Spent Rs.42", "Rs.42.00 spent", "Spent INR 42.00")) {
            val result = parse("$prefix On TEST Bank Card 0000 At TEST FOOD2 On 2026-09-06:13:20:14.Not You? To Block+Reissue Call 18000000000/SMS BLOCK CC 0000 to 7000000000")
            assertEquals(prefix, ParseDecision.Record, result.decision)
            assertEquals(4200L, result.amountMinor)
            assertEquals(Direction.Debit, result.direction)
            assertEquals(TransactionStatus.Successful, result.status)
            assertEquals(Channel.Card, result.channel)
            assertEquals(TransactionType.MerchantPayment, result.transactionType)
            assertEquals("••••0000", result.maskedAccountHint)
            assertEquals("TEST FOOD2", result.counterpartyLabel)
        }
    }

    @Test fun card_spend_rejects_prompts_negation_and_otp() {
        for (body in listOf(
            "Spend Rs.42 On TEST Bank Card 0000 At TEST FOOD On 2026-09-06 to earn rewards",
            "If you spent Rs.42 On TEST Bank Card 0000 At TEST FOOD On 2026-09-06",
            "You have not spent Rs.42 On TEST Bank Card 0000 At TEST FOOD On 2026-09-06",
            "Spent Rs.42 on groceries this month",
            "Spent Rs.42 On TEST Bank Card 1234567890123456 At TEST FOOD On 2026-09-06",
            "Spent Rs.42 On TEST Bank Card 0000 At TEST FOOD On 2026-09-06.Not You? OTP 123456 for verification"
        )) assertEquals(body, ParseDecision.Reject, parse(body).decision)
    }

    @Test fun inline_security_footer_is_not_transaction_evidence() {
        val result = parse("Spent Rs.42 On TEST Bank Card XX0000 At TEST FOOD On 2026-09-06.Not You? UPI payment failed INR 500")
        assertEquals(ParseDecision.Record, result.decision)
        assertEquals(4200L, result.amountMinor)
        assertEquals(Channel.Card, result.channel)
        assertEquals(TransactionStatus.Successful, result.status)
    }

    @Test fun card_spend_ambiguous_amounts_still_require_review() {
        val result = parse("Spent Rs.42 On TEST Bank Card 0000 At TEST FOOD On 2026-09-06. INR 43 also charged")
        assertEquals(ParseDecision.NeedsReview, result.decision)
        assertNull(result.amountMinor)
        assertEquals(Direction.Debit, result.direction)
    }

    @Test fun scheduled_messages_and_credit_limits_are_not_completed_transactions() {
        listOf("INR 250 will be debited tomorrow", "Your credit limit is INR 50000", "Credit balance INR 900", "INR 100 payment request").forEach {
            assertEquals(it, ParseDecision.Reject, parse(it).decision)
        }
        assertEquals(ParseDecision.NeedsReview, parse("Your refund of INR 250 is received").decision)
    }

    @Test fun balance_abbreviations_are_not_transaction_amounts() {
        listOf("AvlBal", "Avl Bal", "Avail. Bal", "Available balance").forEach { label ->
            val result = parse("Your account is credited with INR 7.00 by UPI; $label: Rs1234.56 - TEST BANK")
            assertEquals(label, ParseDecision.Record, result.decision)
            assertEquals(label, 700L, result.amountMinor)
            assertEquals(Direction.Credit, result.direction)
            assertEquals(TransactionStatus.Successful, result.status)
            assertEquals(Channel.UPI, result.channel)
        }
        assertEquals(4200L, parse("Balance INR 900; INR 42 debited").amountMinor)
    }

    @Test fun structured_sent_payment_is_a_debit_with_safe_fields() {
        val result = parse("Sent Rs.42.00\nFrom HDFC Bank A/C *0000\nTo TEST PERSON\nOn 01/01/26\nRef 000000000000\nNot You?\nSMS BLOCK UPI to 0000000000")
        assertEquals(ParseDecision.Record, result.decision)
        assertEquals(4200L, result.amountMinor)
        assertEquals(Direction.Debit, result.direction)
        assertEquals(TransactionStatus.Successful, result.status)
        assertEquals("••••0000", result.maskedAccountHint)
        assertEquals("TEST PERSON", result.counterpartyLabel)
        // A security footer alone does not establish the payment channel.
        assertEquals(Channel.Unknown, result.channel)
    }

    @Test fun sent_word_alone_is_not_payment_evidence() {
        listOf("Sent Rs.42 voucher to your email", "We sent Rs.42 offer details", "Sent Rs.42.00\nTo TEST PERSON").forEach {
            assertEquals(it, ParseDecision.Reject, parse(it).decision)
        }
    }

    @Test fun ambiguous_amount_preserves_unambiguous_fields() {
        val result = parse("INR 42 or INR 43 credited via UPI")
        assertEquals(ParseDecision.NeedsReview, result.decision)
        assertNull(result.amountMinor)
        assertEquals(Direction.Credit, result.direction)
        assertEquals(TransactionStatus.Successful, result.status)
        assertEquals(Channel.UPI, result.channel)
    }

    @Test fun masked_hints_do_not_export_full_or_conflicting_accounts() {
        assertEquals("••••0000", parse("INR 42 debited from account XX0000").maskedAccountHint)
        assertNull(parse("INR 42 debited from account 123456789012").maskedAccountHint)
        assertNull(parse("INR 42 transferred between your own accounts XX0000 and card XX9999").maskedAccountHint)
        assertNull(parse("INR 42 debited from account XX00001").maskedAccountHint)
    }

    @Test fun balance_only_and_security_messages_remain_rejected() {
        listOf("AvlBal: Rs1234.56", "Avail. Bal INR 42", "Not You? SMS BLOCK UPI to 0000000000").forEach {
            assertEquals(it, ParseDecision.Reject, parse(it).decision)
        }
        assertEquals(ParseDecision.Reject, parse("OTP 123456\nSent Rs.42.00\nFrom TEST Bank A/C *0000\nTo TEST PERSON").decision)
        assertEquals(ParseDecision.Reject, parse("INR 42 will be credited tomorrow; AvlBal Rs1234").decision)
    }

    @Test fun verification_guard_checks_the_entire_message() {
        assertEquals(ParseDecision.Reject, parse("INR 42 debited\nNot You?\nOTP 123456 for verification").decision)
    }

    private data class Case(
        val name: String,
        val body: String,
        val decision: ParseDecision,
        val amountMinor: Long?,
        val direction: Direction,
        val status: TransactionStatus,
        val channel: Channel,
        val transactionType: TransactionType,
    )
}

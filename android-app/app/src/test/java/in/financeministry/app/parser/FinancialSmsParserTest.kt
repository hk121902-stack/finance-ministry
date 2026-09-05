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

    @Test fun scheduled_messages_and_credit_limits_are_not_completed_transactions() {
        listOf("INR 250 will be debited tomorrow", "Your credit limit is INR 50000", "Credit balance INR 900", "INR 100 payment request").forEach {
            assertEquals(it, ParseDecision.Reject, parse(it).decision)
        }
        assertEquals(ParseDecision.NeedsReview, parse("Your refund of INR 250 is received").decision)
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

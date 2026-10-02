package `in`.financeministry.app.parser

import `in`.financeministry.app.core.model.*
import org.junit.Assert.*
import org.junit.Test

class CardBillPaymentParserTest {
    private fun parse(body: String) = RuleBasedFinancialSmsParser().parse(IncomingSms("TEST", 1L, body))

    @Test fun explicit_card_payment_receipts_are_bill_payments() {
        listOf(
            "Dear SBI Cardholder, Payment of Rs.42.00 has been received towards your SBI Credit Card ending 0000 on 01-Jan-26.",
            "INR 42.00 credited to your Kotak Credit Card x0000 towards bill payment.",
            "Payment of Rs.42.00 has been received for your BOBCARD ending 0000.",
            "Payment of Rs.42.00 received on your American Express Card ending 0000.",
            "Payment of Rs.42.00 received towards your SBI credit card ending 0000. Next statement due on 01-Feb-26."
        ).forEach { body ->
            val result = parse(body)
            assertEquals(body, ParseDecision.Record, result.decision)
            assertEquals(body, TransactionType.CardRepayment, result.transactionType)
            assertEquals(body, Direction.Credit, result.direction)
            assertEquals(body, 4200L, result.amountMinor)
        }
    }

    @Test fun explicit_bank_debits_and_successful_bill_payments_are_excluded_payment_types() {
        listOf(
            "Rs.42.00 debited from A/c XX0000 towards credit card bill payment on 01-Jan-26.",
            "Your credit card bill payment of Rs.42.00 is successful.",
            "Your payment of INR 42.00 towards your IDFC FIRST Bank credit card ending 0000 is successful.",
            "Payment of Rs.42.00 to your credit card ending 0000 completed successfully."
        ).forEach { body ->
            val result = parse(body)
            assertEquals(body, ParseDecision.Record, result.decision)
            assertEquals(body, TransactionType.CardRepayment, result.transactionType)
            assertEquals(body, Direction.Debit, result.direction)
            assertEquals(body, 4200L, result.amountMinor)
        }
        assertEquals(Channel.BankTransfer, parse("Rs.42.00 debited from A/c XX0000 towards credit card bill payment.").channel)
        assertEquals(Channel.NEFT, parse("Rs.42.00 debited from A/c XX0000 towards credit card bill payment via NEFT.").channel)
    }

    @Test fun refunds_cashback_purchases_and_generic_bank_debits_are_not_bill_payments() {
        listOf(
            "Rs.42.00 credited to your credit card XX0000 as cashback.",
            "Rs.42.00 refunded to your credit card XX0000 towards the original payment.",
            "Rs.42.00 debited from A/c XX0000 on 01-Jan-26.",
            "Spent Rs.42.00 on Credit Card 0000 at TEST SHOP on 2026-01-01."
        ).forEach { assertNotEquals(it, TransactionType.CardRepayment, parse(it).transactionType) }
        assertEquals(TransactionType.Refund, parse("Rs.42.00 refunded to your credit card XX0000 towards the original payment.").transactionType)
        listOf(
            "Your credit card bill payment of Rs.42.00 is due tomorrow.",
            "Your credit card bill payment of Rs.42.00 is scheduled for tomorrow.",
            "Your credit card available limit is Rs.42.00."
        ).forEach { assertEquals(it, ParseDecision.Reject, parse(it).decision) }
    }
}

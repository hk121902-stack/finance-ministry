package `in`.financeministry.app.parser

import `in`.financeministry.app.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Invented values only. No private SMS, names, references or balances in the repo. */
class ParserCoverageTest {
    private fun parse(body: String) = RuleBasedFinancialSmsParser().parse(IncomingSms("TEST", 0L, body))

    @Test fun product_names_and_administration_are_not_money_movements() {
        listOf(
            "Request to link your HDFC Bank Credit Card 0000 to UPI.",
            "Your Debit Card was issued today.",
            "PIN Change successful! For HDFC Bank Debit Card XX0000.",
            "Successfully modified limits for your Credit Card.",
            "Statement Generated: For HDFC Bank Credit Card XX0000.",
            "Credit Card bill generated for Rs.42.00. Pay now.",
            "Enjoy cashless claim processing at hospitals.",
            "FREE tickets with your Credit Card. Apply today.",
            "Your transfer request is processing.",
            "UPI Mandate Set for Rs.42 to TEST SHOP.",
            "AutoPay Active! For TEST SHOP Starting:01/01/26 On HDFC Bank Card 0000"
        ).forEach { assertEquals(it, ParseDecision.Reject, parse(it).decision) }
    }

    @Test fun wallet_single_digit_time_and_balance_is_are_supported() {
        val wallet = parse("Rs.42 spent from Pluxee Meal wallet, card no. on 01-01-2026 1:2:3 at TEST SHOP. Avl bal Rs.900.")
        assertEquals(ParseDecision.Record, wallet.decision)
        assertEquals(4200L, wallet.amountMinor)
        val receipt = parse("Payment of Rs 42 using Apay balance is successful at A.in. Updated balance is Rs 900. If not u? call 1800000000 - SMS via Pine Labs")
        assertEquals(ParseDecision.NeedsReview, receipt.decision)
        assertEquals(4200L, receipt.amountMinor)
    }

    @Test fun wallet_unpadded_calendar_components_do_not_drop_payments() {
        for (date in listOf("4-08-2026", "04-8-2026", "4-8-26")) {
            val result = parse("Rs.42 spent from Pluxee Meal wallet, card no. on $date 11:13:4 at TEST SHOP. Avl bal Rs.900.")
            assertEquals(date, ParseDecision.Record, result.decision)
            assertEquals(4200L, result.amountMinor)
            assertEquals(Direction.Debit, result.direction)
        }
    }

    @Test fun autopay_secondary_confirmation_does_not_automatically_duplicate_card_spend() {
        val result = parse("AutoPay (E-mandate) Success!\nFor TEST SHOP\nTxn Amt:INR42.00\nDt:01/01/26\nVia:HDFC Bank CC 0000\nSI Hub ID: TEST\nTnC")
        assertEquals(ParseDecision.NeedsReview, result.decision)
        assertEquals(4200L, result.amountMinor)
        assertEquals(Direction.Debit, result.direction)
    }

    @Test fun common_outgoing_templates_extract_payment_not_balance() {
        listOf(
            "Rs.42.00 Dr. from A/C XXXXXX0000 and Cr. to test@upi. Ref:000000000000. AvlBal:Rs900.00(01:01:26 12:00:00). Not you? Call 1800000000-BOB",
            "Spent INR 42\nAxis Bank Card no. XX0000\n01-01-26 12:00:00 IST\nTEST SHOP\nAvl Limit: INR 900\nNot you? SMS BLOCK 0000 to 7000000000",
            "Txn Rs.42.00\nOn HDFC Bank Card 0000\nAt test@upi\nby UPI 000000000000\nOn 01-01\nNot You?\nCall 1800000000",
            "Rs.42.00 spent from Pluxee Meal wallet, card no. on 01-01-26 12:00:00 at TEST SHOP. Avl bal Rs.900. Not you call 1800000000",
            "Rs.42.00 spent from Pluxee  Meal wallet, card no.xx0000 on 01-01-26 12:00:00 at TEST SHOP. Avl bal Rs.900.",
            "Rs.42.00 deducted from your Pluxee Card xxxx0000 towards ONLINE CONVENIENCE FEE. Pluxee",
            "INR 42.00 spent using ICICI Bank Card XX0000 on 01-Jan-26 on TEST SHOP. Avl Limit: INR 900. If not you, call 1800000000."
        ).forEach {
            val result = parse(it)
            assertEquals(it, ParseDecision.Record, result.decision)
            assertEquals(it, 4200L, result.amountMinor)
            assertEquals(it, Direction.Debit, result.direction)
            assertEquals(it, TransactionStatus.Successful, result.status)
        }
    }

    @Test fun deposits_and_executed_mandates_are_movements() {
        listOf(
            "Received!\nINR 42.00 in HDFC Bank A/c xx0000\nOn 01-01-26\nFor IMPS -TEST BANK- 000000000000\nAvl bal INR 900",
            "Update! INR 42.00 deposited in HDFC Bank A/c XX0000 on 01-JAN-26 for NEFT Cr-TEST.Av l bal INR 900. Cheque deposits in A/C are subject to clearing".replace("Av l", "Avl")
        ).forEach {
            assertEquals(it, ParseDecision.Record, parse(it).decision)
            assertEquals(4200L, parse(it).amountMinor)
            assertEquals(Direction.Credit, parse(it).direction)
        }
        listOf(
            "UPI Mandate:\nSent Rs.42.00\nfrom HDFC Bank A/c 0000\nTo TEST SHOP\n01/01/26\nRef 000000000000\nNot You? Call 1800000000"
        ).forEach {
            assertEquals(it, ParseDecision.Record, parse(it).decision)
            assertEquals(4200L, parse(it).amountMinor)
            assertEquals(Direction.Debit, parse(it).direction)
        }
    }

    @Test fun refunds_distinguish_posted_from_initiated() {
        val posted = parse("Alert! Rs.42 refunded by TEST SHOP on 01/JAN/26 & adjusted against HDFC Bank Credit Card 0000 View updated balance here: https://example.com")
        assertEquals(ParseDecision.Record, posted.decision)
        assertEquals(4200L, posted.amountMinor)
        assertEquals(Direction.Credit, posted.direction)
        assertEquals(TransactionStatus.Successful, posted.status)
        assertEquals(TransactionType.Refund, posted.transactionType)
        val pending = parse("Dear customer, refund of ₹42 for your TEST Order #000 is initiated. It should reflect in 3–5 days - TEST")
        assertEquals(ParseDecision.Record, pending.decision)
        assertEquals(Direction.Credit, pending.direction)
        assertEquals(TransactionStatus.Pending, pending.status)
    }

    @Test fun repayments_are_not_income_and_secondary_receipts_require_review() {
        listOf(
            "Payment of Rs 42 has been received on your ICICI Bank Credit Card XX0000 through Bharat Bill Payment System on 01-JAN-26.",
            "Payment of INR 42 has been received towards your Axis Bank Credit Card XX0000 on 01-01-26 - Axis Bank",
            "DEAR HDFCBANK CARDMEMBER, PAYMENT OF Rs. 42 RECEIVED TOWARDS YOUR CREDIT CARD ENDING WITH 0000 ON 01-01-26.YOUR AVAILABLE LIMIT IS RS. 900",
            "HDFC Bank Cardmember, Online Payment of Rs.42 vide Ref# TEST was credited to your card ending 0000 On 01/JAN/26_value Date 01/JAN/26"
        ).forEach {
            val result = parse(it)
            assertEquals(it, ParseDecision.Record, result.decision)
            assertEquals(4200L, result.amountMinor)
            assertEquals(Direction.Credit, result.direction)
            assertEquals("CardRepayment", result.transactionType.name)
        }
        listOf(
            "Payment of Rs 42 using Apay balance is successful at A.in. Updated balance is Rs 900. If not u? call 1800000000 - SMS via Pine Labs",
            "Dear User, Challan payment of Rs. 42 against PAN/TAN XXXXX0000X for Assessment Year 2026 has been successfully paid. e-Filing, ITD.",
            "Hi TEST, we have received a payment of Rs. 42 for your Airtel Wi-Fi ID 0000_dsl. To download the payment receipt, click https://example.com",
            "We confirm receipt of online payment made via BBPAY for 42 against LPG Refill Booking No: 0000.Your Delivery Authentication Code is 0000 - HPCL"
        ).forEach {
            val result = parse(it)
            assertEquals(it, ParseDecision.NeedsReview, result.decision)
            assertEquals(it, 4200L, result.amountMinor)
            assertEquals(Direction.Debit, result.direction)
            assertEquals(TransactionStatus.Successful, result.status)
        }
    }

    @Test fun new_templates_keep_otp_future_negation_and_multi_amount_guards() {
        val card = "Spent INR 42\nAxis Bank Card no. XX0000\n01-01-26 12:00:00 IST\nTEST SHOP\nAvl Limit: INR 900"
        listOf("OTP 123456 $card", "If you $card", "You have not $card", "Tomorrow $card").forEach {
            assertEquals(it, ParseDecision.Reject, parse(it).decision)
        }
        assertEquals(ParseDecision.NeedsReview, parse("$card\nINR 50 also debited").decision)
        // These still match the anchored layout: rejection must come from the guards.
        assertEquals(ParseDecision.Reject, parse("$card\nAmount will be debited tomorrow").decision)
        assertEquals(ParseDecision.Reject, parse("$card\nYour account was not debited").decision)
        assertEquals(ParseDecision.Reject, parse("$card\nOTP 123456").decision)
        assertEquals(ParseDecision.Reject, parse("Rs.42 deducted from your reward points offer").decision)
    }
}

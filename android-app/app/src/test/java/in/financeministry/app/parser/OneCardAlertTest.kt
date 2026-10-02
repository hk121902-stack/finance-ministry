package `in`.financeministry.app.parser

import `in`.financeministry.app.core.model.*
import org.junit.Assert.*
import org.junit.Test

class OneCardAlertTest {
    private val alert = "Fresh picks! Rs. 42.50 spent at TEST SHOP on your Federal Bank  One Credit Card xxXX0000. Reward points added. To dispute, click: https://1crd.in/OneCrd/EXAMPLE"
    private fun parse(body: String) = RuleBasedFinancialSmsParser().parse(IncomingSms("TEST", 1L, body))

    @Test fun merchant_first_onecard_alert_records_purchase_and_fields() {
        listOf(alert, alert.removePrefix("Fresh picks! ")).forEach { body ->
            val result = parse(body)
            assertEquals(ParseDecision.Record, result.decision)
            assertEquals(4250L, result.amountMinor)
            assertEquals(Direction.Debit, result.direction)
            assertEquals(TransactionStatus.Successful, result.status)
            assertEquals(Channel.Card, result.channel)
            assertEquals(TransactionType.MerchantPayment, result.transactionType)
            assertEquals("TEST SHOP", result.counterpartyLabel)
            assertEquals("••••0000", result.maskedAccountHint)
        }
    }

    @Test fun promotional_and_non_transaction_messages_do_not_record_a_purchase() {
        listOf(
            alert.replace("spent at", "can be spent at"),
            alert.replace("spent at", "will be spent at"),
            "Fresh picks! Spend Rs. 42.50 at TEST SHOP on your Federal Bank One Credit Card xxXX0000 to earn rewards.",
            "Fresh picks! Rs. 42.50 cashback offer at TEST SHOP on your Federal Bank One Credit Card xxXX0000.",
            alert.replace("spent at", "not spent at"),
            alert.replace("xxXX0000", "123456780000"),
            "Rs. 42.50 spent at TEST SHOP on your Federal Bank One Credit Card xxXX0000-extra."
        ).forEach { body -> assertEquals(body, ParseDecision.Reject, parse(body).decision) }
    }

    @Test fun ambiguous_amounts_and_otp_remain_protected() {
        assertEquals(ParseDecision.NeedsReview, parse(alert + " Another payment Rs. 50.00.").decision)
        assertNull(parse(alert + " Another payment Rs. 50.00.").amountMinor)
        assertEquals(ParseDecision.Reject, parse(alert + " OTP 123456.").decision)
    }
}

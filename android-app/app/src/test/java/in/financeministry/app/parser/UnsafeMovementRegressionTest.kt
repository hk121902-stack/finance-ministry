package `in`.financeministry.app.parser

import `in`.financeministry.app.core.model.IncomingSms
import `in`.financeministry.app.core.model.ParseDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UnsafeMovementRegressionTest {
    private fun parse(body: String) = RuleBasedFinancialSmsParser().parse(IncomingSms("SYNTHETIC", 1788600000000L, body))

    @Test fun negated_movements_are_rejected() {
        listOf(
            "Your account has not been debited INR 500.00.",
            "Your account will not be debited INR 500.00.",
            "INR 500.00 has not yet been credited to your account.",
            "Your card was never charged INR 500.00.",
        ).forEach { assertEquals(it, ParseDecision.Reject, parse(it).decision) }
    }

    @Test fun conditional_and_conversational_movements_are_not_auto_recorded() {
        listOf(
            "If INR 500.00 is debited from your account, call customer care.",
            "Hi, I was debited INR 125.00 yesterday. Can you check?",
            "We were credited INR 125.00 yesterday. Could you confirm?",
            "Your dispute for INR 500.00 debited yesterday has been registered.",
        ).forEach { assertNotEquals(it, ParseDecision.Record, parse(it).decision) }
    }

    @Test fun genuine_alerts_with_dispute_instructions_still_record() {
        listOf(
            "INR 500.00 debited from your account via UPI. Call customer care for dispute.",
            "Fresh picks! Rs. 111.00 spent at Sample Limited on your Federal Bank One Credit Card xxXX1234. Reward points added. To dispute, click: https://example.test/dispute",
            "INR 500.00 debited via UPI. Not You? Call customer care.",
        ).forEach { assertEquals(it, ParseDecision.Record, parse(it).decision) }
    }
}

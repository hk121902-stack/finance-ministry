package `in`.financeministry.app.parser

import `in`.financeministry.app.core.model.*
import org.junit.Assert.*
import org.junit.Test

class CardAlertFieldsTest {
    @Test fun known_card_layouts_preserve_merchant_and_masked_card() {
        for (body in listOf(
            "INR 42.50 spent on Kotak Credit Card x0000 on 01-01-26 at TEST SHOP. Avl limit INR 900",
            "ALERT: INR 42.50 is spent on your BOBCARD ending 0000 at Upi-TEST SHOP on 01-01-2026. Current outstanding is Rs 700",
            "INR 42.50 spent on YES BANK Card X0000 @TEST SHOP 01-01-2026 12:00:00 pm. Avl Lmt INR 900"
        )) {
            val result = `in`.financeministry.app.parser.engine.TemplateEngineParser().parse(IncomingSms("TEST", 0, body))
            assertEquals(ParseDecision.Record, result.decision)
            assertEquals(Channel.Card, result.channel)
            assertEquals("••••0000", result.maskedAccountHint)
            assertTrue(result.counterpartyLabel?.endsWith("TEST SHOP") == true)
        }
    }
    @Test fun unfamiliar_card_layout_is_reviewable_instead_of_silently_lost() {
        val result = `in`.financeministry.app.parser.engine.TemplateEngineParser().parse(IncomingSms("TEST", 0,
            "INR 42.50 spent on New Bank Credit Card ending 0000; terminal TEST; Avl Limit INR 900"))
        assertEquals(ParseDecision.NeedsReview, result.decision)
        assertEquals(4250L, result.amountMinor)
        assertEquals(Direction.Debit, result.direction)
        assertNull(result.counterpartyLabel)
    }
}

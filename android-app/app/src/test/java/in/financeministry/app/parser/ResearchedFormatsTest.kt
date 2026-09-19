package `in`.financeministry.app.parser

import `in`.financeministry.app.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Independently authored synthetic fixtures. Public format sources: docs/ROADMAP.md. */
@RunWith(Parameterized::class)
class ResearchedFormatsTest(private val label: String, private val body: String,
    private val decision: ParseDecision, private val amount: Long?, private val direction: Direction) {
    @Test fun expected_transaction_semantics() {
        val actual = `in`.financeministry.app.parser.engine.TemplateEngineParser().parse(IncomingSms("TEST", 0, body))
        assertEquals(label, decision, actual.decision)
        assertEquals(label, amount, actual.amountMinor)
        assertEquals(label, direction, actual.direction)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<Any?>> {
            val rows = mutableListOf<Array<Any?>>()
            fun debit(name: String, body: String) { rows += arrayOf(name, body, ParseDecision.Record, 4250L, Direction.Debit) }
            fun credit(name: String, body: String) { rows += arrayOf(name, body, ParseDecision.Record, 4250L, Direction.Credit) }
            fun reject(name: String, body: String) { rows += arrayOf(name, body, ParseDecision.Reject, null, Direction.Unknown) }
            debit("Kotak date before merchant", "INR 42.50 spent on Kotak Credit Card x0000 on 01-01-26 at TEST SHOP. Avl limit INR 900.5 Not you? SMS CCLOST 0000 to 7000000000")
            debit("BOBCARD summaries", "ALERT: INR 42.50 is spent on your BOBCARD ending 0000 at TEST SHOP on 01-01-2026. Available credit limit is Rs 9,000.04, Current outstanding is Rs 700.96. Not you? Call 1800000000")
            debit("SBI day first", "Rs.42.50 spent on your SBI Credit Card ending 0000 at TEST SHOP on 01/01/26.")
            debit("Federal ending with", "INR 42.50 spent on your credit card ending with 0000 at TEST SHOP on 01-01-2026 15:30:15. Available limit Rs.900 -Federal Bank")
            debit("IDFC successful prefix", "Transaction Successful! INR 42.50 spent on your IDFC FIRST Bank Credit Card ending XX0000 at TEST SHOP on 01-JAN-2026 at 01:28 PM Avbl Limit: INR 900")
            debit("YES at sign", "INR 42.50 spent on YES BANK Card X0000 @UPI_TEST SHOP 01-01-2026 06:17:25 pm. Avl Lmt INR 900. SMS BLKCC 0000 to 7000000000 if not you")
            debit("Kotak charged", "Rs.42.50 has been charged to your Kotak Credit Card ending 0000 for purchase at TEST SHOP on 01-JAN-2026. Available Credit Limit: Rs.900")
            debit("ICICI cycle summary", "Rs.42.50 spent using your Credit Card ending 0000 for Flight Ticket at TEST SHOP on 01-Jan-2026 08:15 PM. Total Spends this cycle: Rs.700. Available Limit: Rs.900")
            debit("HDFC existing layout", "Spent Rs.42.50 On HDFC Bank Card 0000 At TEST SHOP On 2026-01-01:12:00:00.Not You? Call 1800000000")
            debit("Axis multiline", "Spent INR 42.50\nAxis Bank Card no. XX0000\n01-01-26 12:00:00 IST\nTEST SHOP\nAvl Limit: INR 900")
            debit("PNB aval", "Ac XX0000 Debited with Rs.42.50, 01-01-2026 07:47:16. Aval Bal Rs.900 CR. Helpline 1800000000-PNB")
            debit("IDFC new bal", "Your A/C XXXXXXX0000 is debited by INR 42.50 on 01/01/26 17:36. New Bal :INR 900")
            credit("IDFC credit new bal", "Your A/C XXXXXXX0000 is credited by INR 42.50 on 01/01/26 17:36. New Bal :INR 900")
            credit("Federal bare bal", "Rs 42.50 credited to your A/c XX0000 via IMPS on 01JAN2026 11:45:30 IMPS Ref no 000000000000 Bal:Rs 900 -Federal Bank")
            debit("IndusInd grow dot bal", "IndusInd A/C Debited; INR 42.50 Ref-ACH DR INW PAY/TEST/Grow.Bal INR 900.Dispute-Call 1800000000-IndusInd Bank.")
            credit("Indian Bank UPI", "Rs.42.50 credited to a/c *0000 on 01/01/2026 by a/c linked to VPA test@upi (UPI Ref no 000000000000).Indian Bank")
            debit("SBI standard", "Rs.42.50 debited from A/c X0000 on 01Jan26. Avl Bal Rs.900")
            debit("Federal UPI", "Rs 42.50 debited via UPI on 01-01-2026 10:30:25 to VPA test@upi.Ref No 000000000000.Small txns?Use UPI Lite!-Federal Bank")
            debit("NSDL", "INR 42.50 debited from your NSDL Payments Bank account. Avl Bal INR 900")
            credit("PSB", "INR 42.50 credited to your PSB account. Avl Bal INR 900")
            credit("Kerala Bank", "INR 42.50 credited to your Kerala Bank account. Avl Bal INR 900")
            rows += arrayOf("BOI mixed account clauses", "Rs.42.50 debited A/cXX0000 and credited to TEST SHOP via UPI Ref No 000000000000 on 01Jan26. -BOI", ParseDecision.NeedsReview, 4250L, Direction.Unknown)
            for (currency in listOf("USD", "EUR", "GBP", "AED")) {
                rows += arrayOf("Foreign purchase $currency", "Transaction Successful! $currency 42.50 spent on your IDFC FIRST Bank Credit Card ending XX0000 at TEST SHOP on 01-JAN-2026 Avbl Limit: INR 900", ParseDecision.NeedsReview, null, Direction.Debit)
            }
            for ((name, body) in listOf(
                "OTP" to "OTP 000000 for INR 42.50 spent on Kotak Credit Card x0000 on 01-01-26 at TEST SHOP.",
                "Future" to "INR 42.50 will be spent on Kotak Credit Card x0000 on 01-01-26 at TEST SHOP.",
                "Negated" to "INR 42.50 was not spent on Kotak Credit Card x0000 on 01-01-26 at TEST SHOP.",
                "Summary" to "Total Spends this cycle: Rs.700. Available Limit: Rs.900. Current outstanding is Rs.500.",
                "Bill" to "Your BOBCARD ending 0000 bill is generated. Total amount due INR 42.50. Pay before 01-01-26.",
                "Offer" to "Spend INR 42.50 on your credit card ending 0000 at TEST SHOP on 01-01-26 to get rewards.",
                "Card issued" to "Your Kotak Credit Card ending 0000 has been issued. Limit INR 900",
                "No merchant date" to "INR 42.50 spent on holidays. Available balance INR 900"
            )) reject(name, body)
            rows += arrayOf("Two movements", "INR 42.50 spent on Kotak Credit Card x0000 on 01-01-26 at TEST SHOP. INR 70 debited from your account.", ParseDecision.NeedsReview, null, Direction.Debit)
            return rows
        }
    }
}

package `in`.financeministry.app.parser

/** Explicit settlement evidence only; mentioning a card is not sufficient. */
internal object CardBillPaymentFormats {
    private const val money = """(?:rs\.?|inr|₹)\s*[\d,.]+"""
    private const val card = """(?:[a-z ]{0,40}credit card|bobcard|american express card)"""
    private val receipt = Regex("""\bpayment of $money (?:has been |is )?received (?:on|towards|for) your $card\b""")
    private val creditedBill = Regex("""$money credited to your $card\b.{0,60}\btowards (?:credit card )?bill payment\b""")
    private val bankDebit = Regex("""$money debited from .{1,60}\btowards (?:your )?(?:credit card|bobcard) bill payment\b""")
    private val successfulBill = Regex("""\bcredit card bill payment of $money (?:is |has been )?(?:successful|completed)\b""")
    private val successfulPayment = Regex("""\bpayment of $money (?:towards|to) your $card\b.{0,70}\b(?:is successful|completed successfully|has been completed)\b""")
    private val excluded = Regex("""\b(?:refund(?:ed)?|cashback|revers(?:ed|al)|scheduled|tomorrow)\b""")
    fun credit(text: String) = !excluded.containsMatchIn(text) &&
        (receipt.containsMatchIn(text) || creditedBill.containsMatchIn(text) || MovementTemplates.repayment.containsMatchIn(text))
    fun debit(text: String) = !excluded.containsMatchIn(text) &&
        (bankDebit.containsMatchIn(text) || successfulBill.containsMatchIn(text) || successfulPayment.containsMatchIn(text))
    fun matches(text: String) = credit(text) || debit(text)
}

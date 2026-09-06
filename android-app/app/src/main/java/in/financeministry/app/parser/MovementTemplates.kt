package `in`.financeministry.app.parser

/** Positive movement evidence for supported layouts, not standalone product/status words.
 * Input is normalized transaction text; original private text is never persisted here.
 */
internal object MovementTemplates {
    private const val money = """(?:rs\.?|inr|₹)\s*[\d,.]+"""
    val bobDebit = Regex("""^$money\s+dr\.\s+from\s+a/c\s+[x*]+\d{3,4}\s+and\s+cr\.\s+to\s+\S+\s+ref:\d{8,24}\.""")
    val axisCard = Regex("""^spent\s+$money\s*\r?\naxis bank card no\.\s+[x*]+\d{4}\s*\r?\n\d{2}-\d{2}-\d{2,4}\s+\d{2}:\d{2}:\d{2}\s+ist\s*\r?\n[^\r\n]+\r?\navl limit:""")
    val hdfcCardUpi = Regex("""^txn\s+$money\s*\r?\non hdfc bank card\s+\d{4}\s*\r?\nat [^\r\n]+\r?\nby upi\s+\d{12}\s*\r?\non\s+\d{2}-\d{2}\b""")
    val pluxeeSpend = Regex("""^$money\s+spent from pluxee\s+meal wallet,\s*card no\.(?:[x*]+\d{4})?\s+on\s+\d{1,2}-\d{1,2}-\d{2,4}\s+\d{1,2}:\d{1,2}:\d{1,2}\s+at\s+.+?\.\s*avl bal""")
    val pluxeeFee = Regex("""^$money\s+deducted from your pluxee card\s+[x*]+\d{4}\s+towards online convenience fee\.\s*pluxee\s*$""")
    val iciciCard = Regex("""^$money\s+spent using icici bank card\s+[x*]+\d{4}\s+on\s+\d{2}-[a-z]{3}-\d{2,4}\s+on\s+.+?\.\s*avl limit:""")
    val mandate = Regex("""^upi mandate:\s*\r?\nsent\s+$money\s*\r?\nfrom hdfc bank a/c\s+[*x]*\d{4}\s*\r?\nto [^\r\n]+\r?\n\d{2}/\d{2}/\d{2,4}\s*\r?\nref\s+\d{8,24}\b""")
    val autopay = Regex("""^autopay \(e-mandate\) success!\s*\r?\nfor [^\r\n]+\r?\ntxn amt:\s*$money\s*\r?\ndt:\d{2}/\d{2}/\d{2,4}\s*\r?\nvia:hdfc bank cc\s+\d{4}\b""")
    val received = Regex("""^received!\s*\r?\n$money\s+in hdfc bank a/c\s+[x*]+\d{4}\s*\r?\non\s+\d{2}-\d{2}-\d{2,4}\s*\r?\nfor imps\s*-""")
    val deposited = Regex("""^update!\s*$money\s+deposited in hdfc bank a/c\s+[x*]+\d{4}\s+on\s+\d{2}-[a-z]{3}-\d{2,4}\s+for neft\s+cr-""")
    val postedRefund = Regex("""^alert!\s*$money\s+refunded by\s+.+\s+on\s+\d{2}/[a-z]{3}/\d{2,4}\s*& adjusted against hdfc bank credit card\s+\d{4}\b""")
    val initiatedRefund = Regex("""^(?:dear customer,\s*)?refund of\s+$money\s+for your\s+.{1,100}\border\s*#?\w+\s+is initiated\.""")

    val repayment = Regex("""^(?:payment of $money has been received (?:on|towards) your (?:icici|axis) bank credit card [x*]+\d{4}\b|dear hdfcbank cardmember,\s*payment of $money received towards your credit card ending with \d{4}\b|hdfc bank cardmember, online payment of $money vide ref# [^\r\n]{1,80} was credited to your card ending \d{4}\b)""")
    private val amazonReceipt = Regex("""^payment of $money using apay balance is successful at a\.in\.""")
    private val taxReceipt = Regex("""^dear user,\s*challan payment of $money against pan/tan \S+ for assessment year \d{4} has been successfully paid\.""")
    private val airtelReceipt = Regex("""^hi [^,\r\n]{1,80}, we have received a payment of $money for your airtel wi-fi id \S+""")
    val lpgReceipt = Regex("""^we confirm receipt of online payment made via bbpay for ([\d,.]+) against lpg refill booking no:\s*\d+\.your delivery authentication code is \d+\s*- hpcl\s*$""")
    // Secondary confirmations can duplicate a bank debit. Keep them out of totals until reviewed.
    fun receipt(text: String) = listOf(amazonReceipt, taxReceipt, airtelReceipt, lpgReceipt).any { it.containsMatchIn(text) }
    fun secondaryConfirmation(text: String) = receipt(text) || autopay.containsMatchIn(text)
    fun debit(text: String) = listOf(bobDebit, axisCard, hdfcCardUpi, pluxeeSpend, pluxeeFee, iciciCard, mandate, autopay).any { it.containsMatchIn(text) } || receipt(text)
    fun credit(text: String) = listOf(received, deposited, postedRefund, initiatedRefund, repayment).any { it.containsMatchIn(text) }
    fun matches(text: String) = debit(text) || credit(text)
    fun card(text: String) = listOf(axisCard, hdfcCardUpi, iciciCard, autopay).any { it.containsMatchIn(text) }
}

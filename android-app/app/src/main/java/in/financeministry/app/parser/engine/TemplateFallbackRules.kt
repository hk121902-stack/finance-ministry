package `in`.financeministry.app.parser.engine

import `in`.financeministry.app.core.model.Channel
import `in`.financeministry.app.core.model.Direction
import `in`.financeministry.app.core.model.TransactionStatus
import `in`.financeministry.app.core.model.TransactionType

// Extra templates for generic debit / credit matching that fall back from the strict templates
fun getFallbackTemplates(): List<ParsingTemplate> {
    val money = """(?:rs\.?|inr|₹)\s*(?<amount>[\d,.]+)"""
    return listOf(
        // iciciDebitRecipientCredit
        ParsingTemplate(
            templateId = "icici_debit_recipient_credit",
            regexPattern = """icici\s+bank\s+acct\s+[x*]+(?<account>\d{3,4})\s+debited\s+for\s+(?:rs\.?|inr|₹)\s*(?<amount>[\d,.]+)\s+on\s+\d{2}-[a-z]{3}-\d{2};\s*(?<merchant>[a-z][a-z .&'-]{0,79})\s+credited\.\s*upi:\s*(?<ref>\d{12})\.""",
            direction = Direction.Debit,
            status = TransactionStatus.Successful,
            channel = Channel.UPI,
            transactionType = TransactionType.Unknown
        )
    )
}

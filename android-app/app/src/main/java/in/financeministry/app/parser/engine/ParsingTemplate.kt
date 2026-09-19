package `in`.financeministry.app.parser.engine

import `in`.financeministry.app.core.model.Channel
import `in`.financeministry.app.core.model.Direction
import `in`.financeministry.app.core.model.ParseDecision
import `in`.financeministry.app.core.model.TransactionStatus
import `in`.financeministry.app.core.model.TransactionType

data class ParsingTemplate(
    val templateId: String,
    val regexPattern: String,
    val direction: Direction,
    val status: TransactionStatus,
    val channel: Channel,
    val transactionType: TransactionType,
    val priority: Int = 0,
    val decision: ParseDecision = ParseDecision.Record,
    val ruleId: String = "template_match"
)

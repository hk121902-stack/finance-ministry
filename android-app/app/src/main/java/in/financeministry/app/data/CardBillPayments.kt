package `in`.financeministry.app.data

import java.util.UUID

/** Correct the classification without confirming an uncertain amount/status or altering labels. */
suspend fun TransactionRepository.markCardBillPayment(id: String): TransactionEntity = withLedger { db ->
    val dao = db.transactions()
    val before = requireNotNull(dao.get(id)) { "This transaction no longer exists." }
    require(!dao.hasActiveFinancialMatch(id)) { "Undo the match decision before changing this transaction." }
    require(before.linkedOriginalId == null && dao.linkedTo(id).isEmpty()) { "Review the linked refund or reversal in Edit / confirm before changing this transaction." }
    require(before.direction in listOf("Debit", "Credit") && before.ownership != "SelfTransfer" &&
        before.transactionType !in listOf("Refund", "Reversal", "SelfTransfer")) {
        "Use Edit / confirm to review this transaction's financial details first."
    }
    if (before.transactionType == "CardRepayment") return@withLedger before
    require(before.ownership !in listOf("ForOther", "Group")) { "Review the spending split before marking an own-card bill payment." }
    val now = System.currentTimeMillis()
    val after = before.copy(transactionType = "CardRepayment", isUserCorrected = true, updatedAt = now)
    validateRepaymentEdit(dao, before, after)
    db.runInTransaction {
        dao.update(after)
        dao.audit(listOf(CorrectionEntity(UUID.randomUUID().toString(), id, now, "transactionType", before.transactionType, "CardRepayment")))
    }
    revision.value++
    after
}

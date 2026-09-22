package `in`.financeministry.app.data

import `in`.financeministry.app.core.model.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal fun validateRepaymentEdit(dao: TransactionDao, before: TransactionEntity, after: TransactionEntity) {
    val outgoing = dao.repaymentsFor(before.id)
    val incoming = dao.allocationsFrom(before.id)
    if (outgoing.isEmpty() && incoming.isEmpty()) return
    val identityChanged = before.amountMinor != after.amountMinor || before.direction != after.direction ||
        before.status != after.status || before.currency != after.currency || before.channel != after.channel ||
        before.transactionType != after.transactionType || before.effectiveTimestamp != after.effectiveTimestamp ||
        before.reviewState == "NeedsReview" && after.reviewState != "NeedsReview" ||
        before.repaymentExpected != after.repaymentExpected || before.personalShareMinor != after.personalShareMinor ||
        before.ownership != after.ownership
    require(!identityChanged) { "Review and unlink existing repayments before changing financial details or the spending split." }
    if (outgoing.any { it.method != "Legacy" }) {
        require(before.repaidMinor == after.repaidMinor) { "Use Record repayment or edit its history instead of replacing the repaid total." }
    }
}

/** Backward-compatible old form values become undated events, never fabricated receipts. */
internal fun syncLegacyRepayment(dao: TransactionDao, row: TransactionEntity) {
    val entries = dao.repaymentsFor(row.id)
    if (entries.any { it.method != "Legacy" }) return
    if (entries.fold(0L) { total, item -> Math.addExact(total, item.amountMinor) } == row.repaidMinor) return
    entries.forEach { dao.deleteRepayment(it.id) }
    if (row.repaidMinor > 0) dao.addRepayment(RepaymentEntity("legacy-${row.id}", row.id, null,
        row.repaidMinor, null, row.groupLabel.orEmpty(), "Legacy", row.updatedAt))
}

suspend fun TransactionRepository.repaymentSummary(month: LocalDate): RepaymentSummary = withLedger { db ->
    val start = month.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val end = month.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    RepaymentAccounting.summary(db.transactions().repaymentCandidates(), db.transactions().repayments(), start, end,
        db.transactions().reversedOriginals().toSet())
}

suspend fun TransactionRepository.repaymentsFor(id: String): List<RepaymentEntity> = withLedger { it.transactions().repaymentsFor(id) }

suspend fun TransactionRepository.availableRepaymentCredits(): List<Pair<TransactionEntity, Long>> = withLedger { db ->
    val dao = db.transactions()
    val reversed = dao.reversedOriginals().toSet()
    dao.all().filter { RepaymentAccounting.eligible(it, reversed) && it.direction == "Credit" && it.transactionType !in setOf("Refund", "Reversal") }
        .map { it to (it.amountMinor!! - dao.allocationsFrom(it.id).fold(0L) { total, allocation -> Math.addExact(total, allocation.amountMinor) }) }
        .filter { it.second > 0 }
}

suspend fun TransactionRepository.recordRepayment(expenseId: String, amount: String, receivedAt: Long, payer: String,
    incomingId: String? = null): RepaymentEntity = withLedger { db ->
    val minor = ManualInput(amount, Direction.Credit, receivedAt, TransactionType.Other, label = payer).also { it.validate() }.amountMinor()
    require(payer.length <= 40) { "Keep the name within 40 characters." }
    val dao = db.transactions()
    val expense = requireNotNull(dao.get(expenseId)) { "Expense no longer exists." }
    val credit = incomingId?.let { requireNotNull(dao.get(it)) { "Incoming payment no longer exists." } }
    require(expense.id !in dao.reversedOriginals() && (credit == null || credit.id !in dao.reversedOriginals())) { "A reversed transaction cannot be allocated." }
    if (credit != null) require(receivedAt == credit.effectiveTimestamp) { "A linked repayment uses the incoming payment's actual date." }
    val allocated = credit?.let { dao.allocationsFrom(it.id).fold(0L) { total, r -> Math.addExact(total, r.amountMinor) } } ?: 0L
    RepaymentAccounting.validateAllocation(expense, credit, minor, allocated)
    val now = System.currentTimeMillis()
    val cashId = if (credit == null) UUID.randomUUID().toString() else null
    val repayment = RepaymentEntity(UUID.randomUUID().toString(), expenseId, credit?.id ?: cashId, minor,
        receivedAt, payer.trim(), if (credit == null) "Cash" else "Linked", now)
    db.runInTransaction {
        if (cashId != null) dao.insert(TransactionEntity(cashId, sourceType = "Manual", sourceTimestamp = receivedAt,
            effectiveTimestamp = receivedAt, amountMinor = minor, direction = "Credit", status = "Successful",
            channel = "CashManual", transactionType = "Other", counterpartyLabel = payer.trim().ifBlank { "Cash repayment" },
            reviewState = "Confirmed", createdAt = now, updatedAt = now))
        dao.addRepayment(repayment)
        updateRepaymentAggregate(dao, expense, now)
    }
    revision.value++
    repayment
}

suspend fun TransactionRepository.removeRepayment(id: String) = withLedger { db ->
    val dao = db.transactions()
    val repayment = requireNotNull(dao.repayments().firstOrNull { it.id == id }) { "Repayment no longer exists." }
    val expense = requireNotNull(dao.get(repayment.expenseId))
    db.runInTransaction {
        dao.deleteRepayment(id)
        updateRepaymentAggregate(dao, expense, System.currentTimeMillis())
    }
    revision.value++
}

/** Edits an allocation, never silently changes the amount or date of an incoming transaction. */
suspend fun TransactionRepository.editRepayment(id: String, amount: String, receivedAt: Long?, payer: String) = withLedger { db ->
    val dao = db.transactions()
    val old = requireNotNull(dao.repayments().firstOrNull { it.id == id }) { "Repayment no longer exists." }
    val expense = requireNotNull(dao.get(old.expenseId)) { "Expense no longer exists." }
    val minor = ManualInput(amount, Direction.Credit, receivedAt ?: expense.effectiveTimestamp, TransactionType.Other,
        label = payer).also { it.validate() }.amountMinor()
    require(payer.length <= 40) { "Keep the name within 40 characters." }
    val credit = old.incomingId?.let { requireNotNull(dao.get(it)) { "Incoming payment no longer exists." } }
    require(credit != null || old.method == "Legacy") { "A receipt is required." }
    if (credit != null) require(receivedAt == credit.effectiveTimestamp) { "A linked repayment uses the incoming payment's actual date." }
    val reversed = dao.reversedOriginals().toSet()
    require(expense.id !in reversed && (credit == null || credit.id !in reversed)) { "A reversed transaction cannot be allocated." }
    val otherAllocated = credit?.let { dao.allocationsFrom(it.id).filter { allocation -> allocation.id != id }
        .fold(0L) { n, allocation -> Math.addExact(n, allocation.amountMinor) } } ?: 0L
    RepaymentAccounting.validateAllocation(expense.copy(repaidMinor = expense.repaidMinor - old.amountMinor), credit, minor, otherAllocated)
    val now = System.currentTimeMillis()
    val updated = old.copy(amountMinor = minor, receivedAt = receivedAt, payer = payer.trim())
    db.runInTransaction {
        dao.updateRepayment(updated)
        updateRepaymentAggregate(dao, expense, now)
        dao.audit(listOf(CorrectionEntity(UUID.randomUUID().toString(), expense.id, now, "repayment:$id",
            "${old.amountMinor}|${old.receivedAt}|${old.payer}", "${updated.amountMinor}|${updated.receivedAt}|${updated.payer}")))
    }
    revision.value++
}

private fun updateRepaymentAggregate(dao: TransactionDao, expense: TransactionEntity, now: Long) {
    val total = dao.repaymentsFor(expense.id).fold(0L) { n, r -> Math.addExact(n, r.amountMinor) }
    dao.update(expense.copy(repaidMinor = total, isUserCorrected = true, updatedAt = now))
    dao.audit(listOf(CorrectionEntity(UUID.randomUUID().toString(), expense.id, now,
        "repaidMinor", expense.repaidMinor.toString(), total.toString())))
}

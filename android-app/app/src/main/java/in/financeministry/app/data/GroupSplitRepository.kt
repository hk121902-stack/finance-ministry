package `in`.financeministry.app.data

import `in`.financeministry.app.core.model.*
import java.util.UUID

suspend fun TransactionRepository.localMembers(): List<LocalMemberEntity> = withLedger { it.transactions().members() }
suspend fun TransactionRepository.groupSharesFor(id: String): List<GroupShareEntity> = withLedger { it.transactions().sharesFor(id) }

internal fun validateMemberName(name: String) {
    require(name.trim().length in 1..40 && name.none { it.isISOControl() } && !name.trim().equals("You", true)) {
        "Use a short local name (1–40 characters), other than You."
    }
    require(!Regex("(?i)(otp|[a-z0-9._-]+@[a-z][a-z0-9]*|[0-9]{6,})").containsMatchIn(name)) {
        "Use a nickname, not contact details, account numbers or OTPs."
    }
}
suspend fun TransactionRepository.addLocalMember(name: String): LocalMemberEntity = withLedger { db ->
    validateMemberName(name)
    val dao = db.transactions()
    require(dao.members().none { it.name.equals(name.trim(), true) }) { "That name already exists. Choose the existing person." }
    val member = LocalMemberEntity(UUID.randomUUID().toString(), name.trim(), System.currentTimeMillis())
    dao.addMember(member); revision.value++; member
}
suspend fun TransactionRepository.renameLocalMember(id: String, name: String) = withLedger { db ->
    validateMemberName(name)
    val dao = db.transactions()
    val old = requireNotNull(dao.member(id)) { "Person no longer exists." }
    require(dao.members().none { it.id != id && it.name.equals(name.trim(), true) }) { "That name already exists." }
    dao.updateMember(old.copy(name = name.trim())); revision.value++
}

internal fun validateGroupSplitEdit(dao: TransactionDao, before: TransactionEntity?, after: TransactionEntity,
    requested: List<MemberShareInput>?) {
    val old = before?.let { dao.sharesFor(it.id) }.orEmpty()
    val retained = requested ?: old.map { MemberShareInput(it.memberId, it.amountMinor) }
    if (retained.isNotEmpty()) {
        require(RepaymentAccounting.eligible(after, dao.reversedOriginals().toSet())) { "Confirm an eligible expense before assigning people." }
        val history = before?.let { dao.repaymentsFor(it.id) }.orEmpty()
        require(history.all { it.memberId != null && it.method != "Legacy" } &&
            history.fold(0L) { total, receipt -> Math.addExact(total, receipt.amountMinor) } == after.repaidMinor) {
            "Use individual repayments instead of replacing the repaid total. Unlink old unassigned repayments before assigning people."
        }
    }
    if (requested == null) {
        if (old.isNotEmpty()) {
            require(after.ownership == "Group" && after.direction == "Debit" && after.repaymentExpected &&
                after.amountMinor == before?.amountMinor && after.personalShareMinor == before?.personalShareMinor &&
                after.status == "Successful" && after.transactionType !in setOf("SelfTransfer", "CardRepayment")) {
                "Edit or remove the individual split before changing this expense's amount, purpose or your share."
            }
        }
        return
    }
    if (requested.isNotEmpty()) {
        require(after.ownership == "Group" && after.direction == "Debit" && after.repaymentExpected &&
            after.status == "Successful" && after.transactionType !in setOf("SelfTransfer", "CardRepayment")) { "Choose a successful group expense first." }
        GroupSplitAccounting.validate(requireNotNull(after.amountMinor), requireNotNull(after.personalShareMinor), requested)
        require(requested.all { dao.member(it.memberId) != null }) { "Choose existing local people." }
        require(old.isNotEmpty() || after.repaidMinor == 0L) { "Unlink existing repayments before assigning people." }
    }
    val changed = old.map { MemberShareInput(it.memberId, it.amountMinor) }.toSet() != requested.toSet()
    if (changed && before != null) require(dao.repaymentsFor(before.id).isEmpty()) {
        "Unlink existing repayments before changing or assigning people. Incoming payments are kept; then relink them to each person."
    }
}
internal fun replaceGroupShares(dao: TransactionDao, row: TransactionEntity, requested: List<MemberShareInput>?, now: Long) {
    if (requested == null) return
    val before = dao.sharesFor(row.id).map { MemberShareInput(it.memberId, it.amountMinor) }.toSet()
    if (before == requested.toSet()) return
    dao.deleteShares(row.id)
    dao.addShares(requested.mapIndexed { index, share -> GroupShareEntity("${index.toString().padStart(2, '0')}-${UUID.randomUUID()}", row.id, share.memberId, share.amountMinor) })
    fun label(shares: Collection<MemberShareInput>) = shares.sortedBy { it.memberId }.joinToString(";") { "${it.memberId}:${it.amountMinor}" }
    dao.audit(listOf(CorrectionEntity(UUID.randomUUID().toString(), row.id, now, "groupShares", label(before), label(requested))))
}
suspend fun TransactionRepository.saveGroupSplit(expenseId: String, personalMinor: Long, others: List<MemberShareInput>,
    expectedUpdatedAt: Long) = withLedger { db ->
    val dao = db.transactions()
    val expense = requireNotNull(dao.get(expenseId)) { "Expense no longer exists." }
    require(personalMinor in 0..requireNotNull(expense.amountMinor)) { "Check your share." }
    require(expense.updatedAt == expectedUpdatedAt) { "This expense changed. Reopen the split before saving." }
    require(!dao.hasActiveFinancialMatch(expenseId) && RepaymentAccounting.eligible(expense, dao.reversedOriginals().toSet())) { "Review this expense or undo its match first." }
    val now = maxOf(System.currentTimeMillis(), expense.updatedAt + 1)
    val row = expense.copy(personalShareMinor = personalMinor, isUserCorrected = true, updatedAt = now)
    validateGroupSplitEdit(dao, expense, row, others)
    validateRepaymentEdit(dao, expense, row)
    db.runInTransaction {
        dao.update(row)
        replaceGroupShares(dao, row, others, now)
        if (expense.personalShareMinor != personalMinor) dao.audit(listOf(CorrectionEntity(UUID.randomUUID().toString(), row.id,
            now, "personalShareMinor", expense.personalShareMinor?.toString(), personalMinor.toString())))
    }
    revision.value++
}

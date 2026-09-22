package `in`.financeministry.app.data

import org.json.JSONObject
import java.util.UUID
import `in`.financeministry.app.core.model.*

enum class MatchAction { SamePayment, SelfTransfer, KeepBoth }

/** Stable financial/classification state, not an opaque serialized database row. */
internal fun matchState(row: TransactionEntity): String = JSONObject().apply {
    val fields = linkedMapOf<String, Any?>("amount" to row.amountMinor, "currency" to row.currency,
        "date" to row.effectiveTimestamp, "direction" to row.direction, "status" to row.status,
        "channel" to row.channel, "review" to row.reviewState, "source" to row.paymentSourceId,
        "category" to row.category, "notes" to row.userNotes, "ownership" to row.ownership,
        "type" to row.transactionType, "group" to row.groupLabel, "share" to row.personalShareMinor,
        "expected" to row.repaymentExpected, "duplicate" to row.duplicateOfId,
        "categoryReview" to row.categoryNeedsReview, "label" to row.counterpartyLabel,
        "account" to row.maskedAccountHint, "repaid" to row.repaidMinor)
    fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}.toString()

private fun restoreMatchFields(row: TransactionEntity, snapshot: String): TransactionEntity {
    validateMatchState(snapshot)
    val state = JSONObject(snapshot)
    require(state.getLong("amount") == row.amountMinor) { "The matched amount changed. Undo is not safe." }
    fun optional(name: String) = if (state.isNull(name)) null else state.getString(name)
    return row.copy(category = state.getString("category"), userNotes = optional("notes"), ownership = state.getString("ownership"),
        transactionType = state.getString("type"), groupLabel = optional("group"),
        personalShareMinor = if (state.isNull("share")) null else state.getLong("share"),
        repaymentExpected = state.getBoolean("expected"), duplicateOfId = optional("duplicate"),
        categoryNeedsReview = state.getBoolean("categoryReview"), isUserCorrected = true,
        updatedAt = maxOf(System.currentTimeMillis(), row.updatedAt + 1))
}

internal fun validateMatchState(snapshot: String) {
    val state = JSONObject(BackupInput.decode(snapshot.toByteArray(Charsets.UTF_8)))
    require(state.keys().asSequence().toSet() == setOf("amount", "currency", "date", "direction", "status", "channel", "review", "source",
        "category", "notes", "ownership", "type", "group", "share", "expected", "duplicate", "categoryReview", "label", "account", "repaid")) { "Invalid match snapshot fields." }
    require(state.get("amount") is Number && state.getLong("amount") > 0 && state.getString("currency") == "INR" &&
        state.get("date") is Number && state.getLong("date") > 0) { "Invalid match amount or date." }
    require(state.getString("direction") in setOf("Debit", "Credit") && state.getString("status") == "Successful" &&
        state.getString("review") in setOf("Confirmed", "AutoRecorded") && state.getString("channel") in Channel.entries.map { it.name } &&
        state.getString("type") in TransactionType.entries.map { it.name } && state.getString("ownership") in SpendingOwnership.entries.map { it.name } &&
        state.getString("category") in transactionCategories) { "Invalid matched transaction state." }
    require(state.get("expected") is Boolean && state.get("categoryReview") is Boolean && state.getLong("repaid") in 0..state.getLong("amount") &&
        (state.isNull("share") || state.get("share") is Number && state.getLong("share") in 0..state.getLong("amount"))) { "Invalid match split." }
    for (field in listOf("source", "notes", "group", "duplicate", "label", "account")) {
        require(state.isNull(field) || state.get(field) is String) { "Invalid match text." }
    }
    require(state.isNull("notes") || state.getString("notes").length <= 200) { "Match notes are too long." }
}

suspend fun TransactionRepository.matchSuggestionScan(): MatchScan = withLedger { db ->
    val dao = db.transactions()
    val decisions = dao.matchDecisions().filter { it.undoneAt == null }
    val lockedIds = decisions.filter { it.action != MatchAction.KeepBoth.name }.flatMap { listOf(it.firstId, it.secondId) }.toSet()
    TransactionMatching.scan(dao.all().filter { it.id !in lockedIds }, dao.allSources(),
        excludedPairs = decisions.map { it.pairKey }.toSet(), reversed = dao.reversedOriginals().toSet())
}
suspend fun TransactionRepository.matchSuggestions(): List<MatchSuggestion> = matchSuggestionScan().suggestions

suspend fun TransactionRepository.matchDecisions(): List<MatchDecisionEntity> = withLedger { it.transactions().matchDecisions() }

private fun requireNoAllocations(dao: TransactionDao, rows: List<TransactionEntity>) {
    require(rows.all { dao.repaymentsFor(it.id).isEmpty() && dao.allocationsFrom(it.id).isEmpty() }) {
        "This pair has repayment links. Review and unlink those allocations before resolving the match. Incoming payments are kept."
    }
    require(rows.all { it.linkedOriginalId == null && dao.linkedTo(it.id).isEmpty() }) {
        "A refund or reversal is linked to this pair. Review those links before resolving it."
    }
}

suspend fun TransactionRepository.resolveMatch(suggestion: MatchSuggestion, action: MatchAction,
    survivorId: String? = null, categoryFromId: String? = null, notesFromId: String? = null): MatchDecisionEntity = withLedger { db ->
    val dao = db.transactions()
    val a = requireNotNull(dao.get(suggestion.first.id)) { "A transaction no longer exists." }
    val b = requireNotNull(dao.get(suggestion.second.id)) { "A transaction no longer exists." }
    require(matchState(a) == matchState(suggestion.first) && matchState(b) == matchState(suggestion.second)) { "The pair changed. Open a fresh comparison." }
    require(!dao.hasActiveFinancialMatch(a.id) && !dao.hasActiveFinancialMatch(b.id) &&
        dao.matchDecisions().none { it.undoneAt == null && it.pairKey == suggestion.key }) { "This pair has already been resolved." }
    val current = TransactionMatching.suggest(listOf(a, b), dao.allSources(), reversed = dao.reversedOriginals().toSet()).singleOrNull()
    require(current?.key == suggestion.key && current?.kind == suggestion.kind) { "This pair is no longer a supported match." }
    var first = a; var second = b
    val now = maxOf(System.currentTimeMillis(), a.updatedAt + 1, b.updatedAt + 1)
    if (action != MatchAction.KeepBoth) requireNoAllocations(dao, listOf(a, b))
    when (action) {
        MatchAction.SamePayment -> {
            require(suggestion.kind == MatchKind.Duplicate) { "Choose a duplicate pair." }
            val winner = listOf(a, b).firstOrNull { it.id == survivorId } ?: throw IllegalArgumentException("Choose which record to count.")
            val categoryRow = listOf(a, b).firstOrNull { it.id == (categoryFromId ?: winner.id) } ?: throw IllegalArgumentException("Choose a category to retain.")
            val notesRow = listOf(a, b).firstOrNull { it.id == (notesFromId ?: winner.id) } ?: throw IllegalArgumentException("Choose notes to retain.")
            fun resolved(row: TransactionEntity) = if (row.id == winner.id) row.copy(category = categoryRow.category,
                categoryNeedsReview = categoryRow.categoryNeedsReview, userNotes = notesRow.userNotes, updatedAt = now, isUserCorrected = true)
                else row.copy(duplicateOfId = winner.id, updatedAt = now, isUserCorrected = true)
            first = resolved(a); second = resolved(b)
        }
        MatchAction.SelfTransfer -> {
            require(suggestion.kind == MatchKind.OwnTransfer) { "Choose an own-account transfer pair." }
            fun transferred(row: TransactionEntity) = row.copy(ownership = "SelfTransfer", transactionType = "SelfTransfer",
                groupLabel = null, personalShareMinor = row.amountMinor, repaymentExpected = false, updatedAt = now, isUserCorrected = true)
            first = transferred(a); second = transferred(b)
        }
        MatchAction.KeepBoth -> Unit
    }
    val decision = MatchDecisionEntity(UUID.randomUUID().toString(), suggestion.key, action.name, a.id, b.id,
        matchState(a), matchState(b), matchState(first), matchState(second), now)
    db.runInTransaction {
        if (action != MatchAction.KeepBoth) { dao.update(first); dao.update(second) }
        dao.addMatchDecision(decision)
        dao.audit(listOf(CorrectionEntity(UUID.randomUUID().toString(), a.id, now, "matchDecision", null, decision.id),
            CorrectionEntity(UUID.randomUUID().toString(), b.id, now, "matchDecision", null, decision.id)))
    }
    revision.value++
    decision
}

suspend fun TransactionRepository.undoMatch(id: String) = withLedger { db ->
    val dao = db.transactions()
    val decision = requireNotNull(dao.matchDecision(id)) { "This decision no longer exists." }
    require(decision.undoneAt == null) { "This decision is already undone." }
    val a = requireNotNull(dao.get(decision.firstId)); val b = requireNotNull(dao.get(decision.secondId))
    if (decision.action != MatchAction.KeepBoth.name) {
        requireNoAllocations(dao, listOf(a, b))
        require(matchState(a) == decision.afterFirst && matchState(b) == decision.afterSecond) {
            "A matched transaction changed later. Undo would overwrite those changes; review the records first."
        }
    }
    val now = System.currentTimeMillis()
    db.runInTransaction {
        if (decision.action != MatchAction.KeepBoth.name) {
            dao.update(restoreMatchFields(a, decision.beforeFirst)); dao.update(restoreMatchFields(b, decision.beforeSecond))
        }
        dao.updateMatchDecision(decision.copy(undoneAt = now))
        dao.audit(listOf(CorrectionEntity(UUID.randomUUID().toString(), a.id, now, "matchUndo", decision.id, null),
            CorrectionEntity(UUID.randomUUID().toString(), b.id, now, "matchUndo", decision.id, null)))
    }
    revision.value++
}

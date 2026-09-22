package `in`.financeministry.app.data

import `in`.financeministry.app.core.model.transactionCategories
import java.util.UUID

internal data class BatchChange(val id: String, val before: String?, val after: String?, val auditCount: Int, val categoryNeedsReview: Boolean)
class BatchUndo internal constructor(internal val owner: TransactionRepository, internal val generation: Long,
    internal val epoch: String, internal val field: String, internal val changes: List<BatchChange>, val skippedIds: List<String>) {
    val changedIds: List<String> get() = changes.map { it.id }
    internal var consumed = false
}

class BatchPreview internal constructor(internal val owner: TransactionRepository, internal val generation: Long,
    internal val revision: Long, internal val epoch: String, internal val ids: Set<String>, val category: String?, val sourceId: String?,
    val changeCount: Int, val unchangedCount: Int, val skipped: Map<String, String>)

suspend fun TransactionRepository.previewBatch(ids: Set<String>, category: String? = null, sourceId: String? = null): BatchPreview = withLedger { db ->
    require(ids.size in 1..500) { "Select between 1 and 500 transactions." }
    require((category == null) != (sourceId == null)) { "Choose a category or a payment source." }
    category?.let { require(it in transactionCategories) { "Choose a category." } }
    val dao = db.transactions()
    val source = sourceId?.let { requireNotNull(dao.source(it)) { "Source no longer exists." } }
    require(source == null || source.active) { "Choose an active source." }
    val skipped = mutableMapOf<String, String>()
    var changes = 0
    var unchanged = 0
    ids.forEach { id ->
        val row = dao.get(id)
        when {
            row == null -> skipped[id] = "Transaction no longer exists"
            dao.hasActiveFinancialMatch(id) -> skipped[id] = "Undo its match decision before editing"
            source != null && source.channel != row.channel -> skipped[id] = "Payment method does not match this source"
            (category != null && row.category == category && !row.categoryNeedsReview) || (source != null && row.paymentSourceId == source.id) -> unchanged++
            else -> changes++
        }
    }
    BatchPreview(this, eraseGeneration.value, revision.value, batchEpoch, ids.toSet(), category, sourceId, changes, unchanged, skipped.toMap())
}

suspend fun TransactionRepository.applyBatch(preview: BatchPreview): BatchUndo = withLedger { db ->
    require(preview.owner === this && preview.generation == eraseGeneration.value && preview.epoch == batchEpoch && preview.revision == revision.value) {
        "The ledger changed after this preview. Preview the selection again."
    }
    performBatch(db, preview.ids, preview.category, preview.sourceId)
}

/** Only classification changes: never amounts, purpose, dates or review confirmation. */
suspend fun TransactionRepository.batchUpdate(ids: Set<String>, category: String? = null,
    sourceId: String? = null): BatchUndo = withLedger { performBatch(it, ids, category, sourceId) }

private fun TransactionRepository.performBatch(db: FinanceDatabase, ids: Set<String>, category: String?, sourceId: String?): BatchUndo {
    require(ids.size in 1..500) { "Select between 1 and 500 transactions." }
    require((category == null) != (sourceId == null)) { "Choose a category or a payment source." }
    category?.let { require(it in transactionCategories) { "Choose a category." } }
    val dao = db.transactions()
    val source = sourceId?.let { requireNotNull(dao.source(it)) { "Source no longer exists." } }
    require(source == null || source.active) { "Choose an active source." }
    val field = if (category != null) "category" else "paymentSourceId"
    val changes = mutableListOf<BatchChange>()
    val skipped = mutableListOf<String>()
    db.runInTransaction {
        ids.forEach { id ->
            val row = dao.get(id)
            if (row == null || dao.hasActiveFinancialMatch(id) || (source != null && source.channel != row.channel)) {
                skipped.add(id)
            } else {
                val before = if (category != null) row.category else row.paymentSourceId
                val after = category ?: sourceId
                if (before != after || category != null && row.categoryNeedsReview) {
                    val now = maxOf(System.currentTimeMillis(), row.updatedAt + 1)
                    dao.update(if (category != null) row.copy(category = category, categoryNeedsReview = false, isUserCorrected = true, updatedAt = now)
                        else row.copy(paymentSourceId = sourceId, isUserCorrected = true, updatedAt = now))
                    dao.audit(listOf(CorrectionEntity(UUID.randomUUID().toString(), id, now, field, before, after)))
                    changes.add(BatchChange(id, before, after, dao.corrections(id).count { it.fieldName == field }, row.categoryNeedsReview))
                }
            }
        }
    }
    if (changes.isNotEmpty()) revision.value++
    return BatchUndo(this, eraseGeneration.value, batchEpoch, field, changes.toList(), skipped.toList())
}

suspend fun TransactionRepository.undoBatch(token: BatchUndo) = withLedger { db ->
    require(token.owner === this && token.generation == eraseGeneration.value && token.epoch == batchEpoch && !token.consumed) { "This undo has expired." }
    val dao = db.transactions()
    db.runInTransaction {
        val rows = token.changes.map { change ->
            val row = requireNotNull(dao.get(change.id)) { "A selected transaction no longer exists." }
            require(!dao.hasActiveFinancialMatch(row.id)) { "Undo the match decision before changing this transaction." }
            val value = if (token.field == "category") row.category else row.paymentSourceId
            require(value == change.after) { "A selected field was edited later. Undo would overwrite it." }
            require(dao.corrections(row.id).count { it.fieldName == token.field } == change.auditCount) { "A selected field was edited later. Undo would overwrite it." }
            if (token.field == "paymentSourceId" && change.before != null) {
                val source = dao.source(change.before)
                require(source != null && source.active && source.channel == row.channel) { "The previous source is no longer compatible." }
            }
            row to change
        }
        rows.forEach { (row, change) ->
            val now = maxOf(System.currentTimeMillis(), row.updatedAt + 1)
            dao.update(if (token.field == "category") row.copy(category = requireNotNull(change.before), categoryNeedsReview = change.categoryNeedsReview, updatedAt = now)
                else row.copy(paymentSourceId = change.before, updatedAt = now))
            dao.audit(listOf(CorrectionEntity(UUID.randomUUID().toString(), row.id, now, token.field, change.after, change.before)))
        }
    }
    token.consumed = true
    if (token.changes.isNotEmpty()) revision.value++
}

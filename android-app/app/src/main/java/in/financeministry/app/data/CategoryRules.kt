package `in`.financeministry.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import `in`.financeministry.app.core.model.transactionCategories
import java.util.Locale
import java.util.UUID

@Entity(tableName = "category_rules")
data class CategoryRuleEntity(@PrimaryKey val id: String, val merchant: String, val sourceId: String?,
    val category: String, val enabled: Boolean, val createdAt: Long)

data class RememberCategoryRule(val merchant: String, val sourceId: String?)

internal fun buildRememberedRule(dao: TransactionDao, row: TransactionEntity, choice: RememberCategoryRule): CategoryRuleEntity {
    require(choice.merchant.trim().length in 2..60 && CategoryRules.normalize(choice.merchant) == CategoryRules.normalize(row.counterpartyLabel.orEmpty())) {
        "A remembered rule must match this transaction's merchant."
    }
    require(row.category in transactionCategories) { "Choose a category." }
    choice.sourceId?.let { require(it == row.paymentSourceId && dao.source(it)?.active == true) { "Choose this payment's active source, or all sources." } }
    val old = dao.rules().firstOrNull { CategoryRules.normalize(it.merchant) == CategoryRules.normalize(choice.merchant) &&
        it.sourceId == choice.sourceId && it.category == row.category }
    return CategoryRuleEntity(old?.id ?: UUID.randomUUID().toString(), choice.merchant.trim(), choice.sourceId, row.category, true,
        old?.createdAt ?: System.currentTimeMillis())
}

object CategoryRules {
    fun normalize(value: String) = value.trim().lowercase(Locale.ROOT)
    private fun matches(row: TransactionEntity, rules: List<CategoryRuleEntity>): List<CategoryRuleEntity> =
        if (row.isUserCorrected || row.sourceType == "Manual" || row.counterpartyLabel.isNullOrBlank()) emptyList()
        else rules.filter { it.enabled && normalize(it.merchant) == normalize(row.counterpartyLabel) &&
            (it.sourceId == null || it.sourceId == row.paymentSourceId) }
    fun conflicts(row: TransactionEntity, rules: List<CategoryRuleEntity>) = matches(row, rules).map { it.category }.distinct().size > 1
    fun conflictingRuleIds(rules: List<CategoryRuleEntity>): Set<String> = rules.filter { a -> a.enabled && rules.any { b ->
        b.enabled && a.id != b.id && normalize(a.merchant) == normalize(b.merchant) && a.category != b.category &&
            (a.sourceId == null || b.sourceId == null || a.sourceId == b.sourceId)
    } }.map { it.id }.toSet()
    fun apply(row: TransactionEntity, rules: List<CategoryRuleEntity>): TransactionEntity {
        val categories = matches(row, rules).map { it.category }.distinct()
        return when (categories.size) {
            0 -> row
            1 -> row.copy(category = categories.single(), categoryNeedsReview = false)
            else -> row.copy(categoryNeedsReview = true)
        }
    }
}

suspend fun TransactionRepository.categoryRules(): List<CategoryRuleEntity> = withLedger { it.transactions().rules() }
suspend fun TransactionRepository.categoryConflicts(): List<TransactionEntity> = withLedger { db ->
    db.transactions().all().filter { it.categoryNeedsReview }
}
suspend fun TransactionRepository.saveCategoryRule(merchant: String, sourceId: String?, category: String,
    enabled: Boolean = true, id: String? = null): CategoryRuleEntity = withLedger { db ->
    require(merchant.trim().length in 2..60) { "Enter a merchant name between 2 and 60 characters." }
    require(category in transactionCategories) { "Choose a category." }
    sourceId?.let { require(db.transactions().source(it) != null) { "Choose a registered source." } }
    val old = id?.let { ruleId -> requireNotNull(db.transactions().rules().firstOrNull { it.id == ruleId }) { "Rule no longer exists." } }
    val rule = CategoryRuleEntity(old?.id ?: UUID.randomUUID().toString(), merchant.trim(), sourceId, category, enabled, old?.createdAt ?: System.currentTimeMillis())
    db.transactions().saveRule(rule)
    revision.value++
    rule
}
suspend fun TransactionRepository.deleteCategoryRule(id: String) = withLedger { it.transactions().deleteRule(id); revision.value++ }

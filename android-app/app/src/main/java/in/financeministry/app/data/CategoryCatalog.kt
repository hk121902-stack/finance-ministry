package `in`.financeministry.app.data

import `in`.financeministry.app.core.model.transactionCategories
import org.json.JSONArray
import java.util.Locale

internal const val CUSTOM_CATEGORIES_KEY = "custom_categories"

internal fun normalizedCategoryName(value: String): String = value.trim().replace(Regex("\\s+"), " ")

internal fun validCategoryName(value: String): Boolean = value.length in 2..40 &&
    '+' !in value && value.none { it.isISOControl() }

internal fun decodeCustomCategories(array: JSONArray): List<String> {
    require(array.length() <= 50) { "Too many custom categories." }
    val names = (0 until array.length()).map { index ->
        require(array.opt(index) is String) { "Invalid custom category." }
        array.getString(index)
    }
    require(names.all { it == normalizedCategoryName(it) && validCategoryName(it) }) { "Invalid custom category." }
    val normalized = (transactionCategories + names).map { it.lowercase(Locale.ROOT) }
    require(normalized.size == normalized.toSet().size) { "Duplicate category." }
    return names
}

internal fun customCategories(dao: TransactionDao): List<String> =
    decodeCustomCategories(JSONArray(dao.metadata(CUSTOM_CATEGORIES_KEY) ?: "[]"))

internal fun categoryOptions(dao: TransactionDao): List<String> = transactionCategories + customCategories(dao)

internal fun categoryExists(dao: TransactionDao, category: String): Boolean = category in categoryOptions(dao)

suspend fun TransactionRepository.categories(): List<String> = withLedger { categoryOptions(it.transactions()) }

suspend fun TransactionRepository.addCategory(name: String): String = withLedger { db ->
    val clean = normalizedCategoryName(name)
    require(validCategoryName(clean)) { "Enter 2–40 characters without + or line breaks." }
    val dao = db.transactions()
    val current = customCategories(dao)
    require(current.size < 50) { "You can add up to 50 custom categories." }
    require(categoryOptions(dao).none { it.equals(clean, ignoreCase = true) }) { "This category already exists." }
    dao.metadata(LedgerMetadataEntity(CUSTOM_CATEGORIES_KEY, JSONArray(current + clean).toString()))
    revision.value++
    clean
}

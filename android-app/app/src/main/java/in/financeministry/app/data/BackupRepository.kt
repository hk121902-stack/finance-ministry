package `in`.financeministry.app.data

import android.database.Cursor
import android.util.Base64
import `in`.financeministry.app.core.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.math.BigInteger

/** No database/Keystore keys or raw SMS are exported. Data is re-encrypted under the destination's key. */
class BackupPreview internal constructor(internal val data: String, internal val eraseGeneration: Long,
    val transactionCount: Int, val repaymentCount: Int, val sourceCount: Int, val ruleCount: Int,
    val budgetCount: Int, val recurringReminderCount: Int, val createdAt: Long)
class PreparedBackup internal constructor(val bytes: ByteArray, val revision: Long)
data class RestoreResult(val settingsApplied: Boolean)

// Parent-before-child order; reverse order deletes safely within the restore transaction.
internal val backupTables = listOf("payment_sources", "import_batches", "transactions", "corrections", "repayments", "category_rules", "match_decisions", "budgets", "recurring_reminders")
private val backupPreferences = setOf("preferred_name", "onboarding_complete", "notifications", "review_reminder_hour", "review_reminder_minute")

private fun TransactionRepository.installationId(): String = preferences.getString("installation_id", null)
    ?: UUID.randomUUID().toString().also { check(preferences.edit().putString("installation_id", it).commit()) }

private fun TransactionRepository.backupData(db: FinanceDatabase): JSONObject {
    val tables = JSONObject()
    for (table in backupTables) {
        val rows = JSONArray()
        db.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                val row = JSONObject()
                cursor.columnNames.forEachIndexed { index, name ->
                    row.put(name, when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_BLOB -> JSONObject().put("blob", Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                        else -> error("Unsupported ledger value.")
                    })
                }
                rows.put(row)
            }
        }
        tables.put(table, rows)
    }
    val settings = JSONObject()
    backupPreferences.sorted().forEach { name -> preferences.all[name]?.let { settings.put(name, it) } }
    return JSONObject().put("tables", tables).put("preferences", settings)
}

private fun digest(value: ByteArray): String = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value), Base64.NO_WRAP)
private fun digest(value: String): String = digest(value.toByteArray(Charsets.UTF_8))

suspend fun TransactionRepository.createEncryptedBackup(password: CharArray): ByteArray = prepareBackup(password).bytes

suspend fun TransactionRepository.prepareBackup(password: CharArray): PreparedBackup = withLedger { db ->
    val data = backupData(db)
    val plain = JSONObject().put("format", 1).put("schema", 7).put("createdAt", System.currentTimeMillis())
        .put("origin", installationId()).put("fingerprintHistoryRequiresReview", db.transactions().metadata("foreign_restore") == "true")
        .put("data", data).toString().toByteArray(Charsets.UTF_8)
    try {
        val encrypted = BackupCipher.encrypt(plain, password)
        pendingBackupDigest = digest(data.toString())
        pendingBackupRevision = revision.value
        pendingBackupFileDigest = digest(encrypted)
        PreparedBackup(encrypted, revision.value)
    } finally { plain.fill(0) }
}

/** Call only after SAF output stream has been successfully written and closed. */
private suspend fun TransactionRepository.confirmBackupWritten(snapshotRevision: Long, fileDigest: String) = withLedger { _ ->
    require(pendingBackupRevision == snapshotRevision && pendingBackupDigest != null && pendingBackupFileDigest == fileDigest) { "This backup operation expired. Create a new backup." }
    check(preferences.edit().putString("completed_backup_digest", pendingBackupDigest)
        .putLong("last_backup_at", System.currentTimeMillis()).commit())
    pendingBackupDigest = null; pendingBackupRevision = null; pendingBackupFileDigest = null
}

suspend fun TransactionRepository.writePreparedBackup(prepared: PreparedBackup, openStream: () -> java.io.OutputStream) {
    // Freeze the actual file bytes, not just the ledger revision: preferences can change
    // without a transaction revision, and a second preparation supersedes the first.
    val bytes = prepared.bytes.copyOf()
    try {
        val fileDigest = digest(bytes)
        withLedger { _ ->
            require(pendingBackupRevision == prepared.revision && pendingBackupFileDigest == fileDigest) {
                "This backup operation expired. Create a new backup."
            }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            openStream().use { it.write(bytes); it.flush() }
        }
        confirmBackupWritten(prepared.revision, fileDigest)
    } finally { bytes.fill(0) }
}

suspend fun TransactionRepository.previewBackup(encrypted: ByteArray, password: CharArray): BackupPreview = withLedger { db ->
    val plain = BackupCipher.decrypt(encrypted, password)
    val root = try { JSONObject(BackupInput.decode(plain)) } catch (_: Exception) {
        throw IllegalArgumentException("Backup contents are invalid. Your ledger was not changed.")
    } finally { plain.fill(0) }
    validateBackup(root, db)
    val tables = root.getJSONObject("data").getJSONObject("tables")
    BackupPreview(root.toString(), eraseGeneration.value, tables.getJSONArray("transactions").length(),
        tables.getJSONArray("repayments").length(), tables.getJSONArray("payment_sources").length(),
        tables.getJSONArray("category_rules").length(), tables.getJSONArray("budgets").length(),
        tables.getJSONArray("recurring_reminders").length(), root.getLong("createdAt"))
}

suspend fun TransactionRepository.hasBackupData(): Boolean = withLedger { db -> hasBackupData(db) }

private fun hasBackupData(db: FinanceDatabase): Boolean = backupTables.any { table ->
    db.openHelper.readableDatabase.query("SELECT 1 FROM `$table` LIMIT 1").use { it.moveToFirst() }
}

suspend fun TransactionRepository.restoreBackup(preview: BackupPreview, protectCurrent: Boolean): RestoreResult = withLedger { db ->
    require(preview.eraseGeneration == eraseGeneration.value) { "This preview expired. Choose the backup again." }
    val root = JSONObject(preview.data)
    validateBackup(root, db)
    if (hasBackupData(db)) {
        require(protectCurrent && preferences.getString("completed_backup_digest", null) == digest(backupData(db).toString())) {
            "Save an encrypted backup of your current ledger before replacing it. If it changed, back it up again."
        }
    }
    val foreign = root.getString("origin") != installationId() || root.optBoolean("fingerprintHistoryRequiresReview", false)
    val data = root.getJSONObject("data")
    val tables = data.getJSONObject("tables")
    // Pause capture before the financial commit, including the settings-recovery failure case.
    check(preferences.edit().putBoolean("sms_disclosure", false).putBoolean("review_reminder", false).commit()) {
        "Could not pause recording safely. Your ledger was not replaced."
    }
        db.runInTransaction {
        for (table in backupTables.reversed()) db.openHelper.writableDatabase.execSQL("DELETE FROM `$table`")
        for (table in backupTables) {
            val rows = tables.getJSONArray(table)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val columns = row.keys().asSequence().toList().sorted()
                val bindings = columns.map { column -> val value = row.get(column)
                    when {
                        value === JSONObject.NULL -> null
                        value is JSONObject -> Base64.decode(value.getString("blob"), Base64.NO_WRAP)
                        else -> value
                    }
                }.toTypedArray()
                val names = columns.joinToString(",") { "`$it`" }
                val marks = columns.joinToString(",") { "?" }
                db.openHelper.writableDatabase.execSQL("INSERT INTO `$table` ($names) VALUES ($marks)", bindings)
            }
        }
        db.openHelper.writableDatabase.execSQL("UPDATE recurring_reminders SET active = 0")
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "Backup links are invalid." } }
        db.transactions().metadata(LedgerMetadataEntity("pending_restore_settings", data.getJSONObject("preferences").toString()))
        db.transactions().metadata(LedgerMetadataEntity("foreign_restore", foreign.toString()))
    }
    invalidateImportPreviews()
    pendingBackupDigest = null; pendingBackupRevision = null; pendingBackupFileDigest = null
    // Settings recovery is durable; the database transaction is the single financial commit.
    revision.value++
    val settingsApplied = try { applyPendingRestoreSettings(db); true } catch (_: Exception) { false }
    RestoreResult(settingsApplied)
}

internal fun TransactionRepository.applyPendingRestoreSettings(db: FinanceDatabase) {
    val pending = db.transactions().metadata("pending_restore_settings") ?: return
    val settings = JSONObject(pending)
    val edit = preferences.edit()
    backupPreferences.forEach { edit.remove(it) }
    for (key in settings.keys()) when (val value = settings.get(key)) {
        is Boolean -> edit.putBoolean(key, value)
        is Int -> edit.putInt(key, value)
        is Long -> edit.putInt(key, value.toInt())
        is String -> edit.putString(key, value)
    }
    check(edit.putBoolean("sms_disclosure", false).putBoolean("review_reminder", false)
        .remove("completed_backup_digest").remove("last_backup_at").remove("last_backup_location").remove("last_capture_at").remove("capture_error").commit()) {
        "Ledger restored. Settings recovery will retry when you reopen the app."
    }
    if (isMainLedger) {
        `in`.financeministry.app.sms.ReviewReminder.cancel(applicationContext)
        `in`.financeministry.app.sms.RecurringPaymentReminder.cancel(applicationContext)
        applicationContext.getSystemService(android.app.NotificationManager::class.java).cancelAll()
    }
    db.transactions().removeMetadata("pending_restore_settings")
}

private fun validateBackup(root: JSONObject, db: FinanceDatabase) {
    if (root.optInt("format") == 1 && root.optInt("schema") == 5) {
        val oldTables = root.getJSONObject("data").getJSONObject("tables")
        require(!oldTables.has("match_decisions")) { "Unexpected legacy backup table." }
        oldTables.put("match_decisions", JSONArray()); root.put("schema", 6)
    }
    if (root.optInt("format") == 1 && root.optInt("schema") == 6) {
        val oldTables = root.getJSONObject("data").getJSONObject("tables")
        require(!oldTables.has("budgets") && !oldTables.has("recurring_reminders")) { "Unexpected legacy backup table." }
        oldTables.put("budgets", JSONArray()).put("recurring_reminders", JSONArray()); root.put("schema", 7)
    }
    require(root.optInt("format") == 1 && root.optInt("schema") == 7 && root.optLong("createdAt") > 0 && root.optString("origin").length in 1..80) {
        "Unsupported backup version. Your ledger was not changed."
    }
    val data = root.getJSONObject("data")
    val tables = data.getJSONObject("tables")
    require(tables.keys().asSequence().toSet() == backupTables.toSet()) { "Backup tables are incomplete." }
    val rowsByTable = mutableMapOf<String, Map<String, JSONObject>>()
    for (table in backupTables) {
        val columns = mutableMapOf<String, String>()
        val required = mutableSetOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                columns[name] = c.getString(c.getColumnIndexOrThrow("type"))
                if (c.getInt(c.getColumnIndexOrThrow("notnull")) == 1) required.add(name)
            }
        }
        val rows = tables.getJSONArray(table)
        require(rows.length() <= 100_000) { "Backup has too many records." }
        val indexed = mutableMapOf<String, JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(row.keys().asSequence().toSet() == columns.keys) { "Backup fields do not match this app version." }
            require(required.none { row.isNull(it) }) { "Required backup fields are missing." }
            columns.forEach { (column, type) ->
                val value = row.get(column)
                if (value !== JSONObject.NULL) when (type) {
                    "INTEGER" -> require(value is Int || value is Long) { "Invalid numeric backup field." }
                    "TEXT" -> require(value is String && value.length <= 10000) { "Invalid text backup field." }
                    "BLOB" -> require(value is JSONObject && value.keys().asSequence().toSet() == setOf("blob") && Base64.decode(value.getString("blob"), Base64.NO_WRAP).size == 32) { "Invalid fingerprint." }
                    else -> error("Unsupported backup field.")
                }
            }
            val id = row.getString("id")
            require(id.length in 1..100 && indexed.put(id, row) == null) { "Duplicate or invalid record identity." }
            val booleanColumns = when (table) {
                "transactions" -> listOf("isUserCorrected", "repaymentExpected", "categoryNeedsReview")
                "payment_sources" -> listOf("active")
                "category_rules" -> listOf("enabled")
                "recurring_reminders" -> listOf("active")
                else -> emptyList()
            }
            require(booleanColumns.all { row.getLong(it) in 0L..1L }) { "Invalid boolean backup field." }
        }
        rowsByTable[table] = indexed
    }
    val transactions = rowsByTable.getValue("transactions")
    val sources = rowsByTable.getValue("payment_sources")
    for (budget in rowsByTable.getValue("budgets").values) {
        require(budget.getString("category") in transactionCategories && budget.getLong("monthlyLimitMinor") > 0 &&
            (budget.isNull("alertPercent") || budget.getLong("alertPercent") in 50L..100L) &&
            budget.getLong("createdAt") > 0 && budget.getLong("updatedAt") >= budget.getLong("createdAt")) { "Invalid budget." }
    }
    require(rowsByTable.getValue("budgets").values.groupBy { it.getString("category") }.none { it.value.size > 1 }) { "Duplicate budget category." }
    for (reminder in rowsByTable.getValue("recurring_reminders").values) {
        require(reminder.getString("title").trim().length in 2..40 &&
            (reminder.isNull("amountMinor") || reminder.getLong("amountMinor") > 0) &&
            reminder.getLong("preferredDay") in 1L..31L && reminder.getString("category") in transactionCategories &&
            reminder.getLong("createdAt") > 0 && reminder.getLong("updatedAt") >= reminder.getLong("createdAt")) { "Invalid recurring reminder." }
        require(reminder.isNull("lastLinkedAt") == reminder.isNull("lastLinkedTransactionId")) { "Incomplete recurring payment link." }
        if (!reminder.isNull("lastLinkedTransactionId")) {
            val linked = requireNotNull(transactions[reminder.getString("lastLinkedTransactionId")]) { "Recurring payment is missing." }
            require(linked.getString("direction") == "Debit" && reminder.getLong("lastLinkedAt") == linked.getLong("effectiveTimestamp")) { "Invalid recurring payment link." }
        }
    }
    for (decision in rowsByTable.getValue("match_decisions").values) {
        require(decision.getString("firstId") in transactions && decision.getString("secondId") in transactions &&
            decision.getString("firstId") != decision.getString("secondId")) { "Match records are missing." }
        require(decision.getString("action") in MatchAction.entries.map { it.name } && decision.getLong("createdAt") > 0) { "Invalid match decision." }
        require(decision.isNull("undoneAt") || decision.getLong("undoneAt") > 0) { "Invalid match undo date." }
        require(decision.getString("pairKey") == TransactionMatching.pairKey(decision.getString("firstId"), decision.getString("secondId"))) { "Invalid match pair." }
        listOf("beforeFirst", "beforeSecond", "afterFirst", "afterSecond").forEach { key -> validateMatchState(decision.getString(key)) }
    }
    for (source in sources.values) {
        require(source.getString("channel") in Channel.entries.map { it.name } && source.getString("nickname").trim().length in 2..40) { "Invalid payment source." }
        require(source.isNull("last4") || source.getString("last4").matches(Regex("[0-9]{4}"))) { "Invalid source identifier." }
    }
    for (row in transactions.values) {
        require(row.getString("direction") in Direction.entries.map { it.name } && row.getString("status") in TransactionStatus.entries.map { it.name } &&
            row.getString("channel") in Channel.entries.map { it.name } && row.getString("transactionType") in TransactionType.entries.map { it.name } &&
            row.getString("ownership") in SpendingOwnership.entries.map { it.name } && row.getString("reviewState") in ReviewState.entries.map { it.name }) { "Invalid transaction state." }
        require(row.getString("sourceType") in SourceType.entries.map { it.name }) { "Invalid transaction origin." }
        val amount = if (row.isNull("amountMinor")) null else row.getLong("amountMinor")
        require(amount == null || amount > 0) { "Invalid transaction amount." }
        require(row.getLong("effectiveTimestamp") > 0 && row.getLong("sourceTimestamp") > 0) { "Invalid transaction date." }
        require(row.getLong("repaidMinor") >= 0 && (row.isNull("personalShareMinor") || row.getLong("personalShareMinor") in 0..(amount ?: 0))) { "Invalid spending share." }
        if (!row.isNull("paymentSourceId")) require(sources[row.getString("paymentSourceId")]?.getString("channel") == row.getString("channel")) { "Payment source is missing or incompatible." }
        if (!row.isNull("linkedOriginalId")) require(row.getString("linkedOriginalId") in transactions) { "Linked transaction is missing." }
        if (!row.isNull("duplicateOfId")) require(row.getString("duplicateOfId") in transactions && row.getString("duplicateOfId") != row.getString("id")) { "Invalid duplicate link." }
    }
    val expenseTotals = mutableMapOf<String, BigInteger>()
    val creditTotals = mutableMapOf<String, BigInteger>()
    for (r in rowsByTable.getValue("repayments").values) {
        val expense = requireNotNull(transactions[r.getString("expenseId")]) { "Repayment expense is missing." }
        val amount = r.getLong("amountMinor")
        require(amount > 0 && r.getString("method") in setOf("Legacy", "Cash", "Linked") && (r.isNull("receivedAt") || r.getLong("receivedAt") > 0)) { "Invalid repayment." }
        require(r.getString("method") == "Legacy" || !r.isNull("receivedAt")) { "Dated repayment is missing its date." }
        require(expense.getString("direction") == "Debit" && expense.getString("ownership") in setOf("ForOther", "Group") && expense.getLong("repaymentExpected") == 1L) { "Invalid repayment expense." }
        val principal = expense.optLong("amountMinor", 0) - expense.optLong("personalShareMinor", expense.optLong("amountMinor", 0))
        val total = (expenseTotals[r.getString("expenseId")] ?: BigInteger.ZERO) + BigInteger.valueOf(amount)
        require(total <= BigInteger.valueOf(principal)) { "Repayment exceeds the expense share." }
        expenseTotals[r.getString("expenseId")] = total
        if (!r.isNull("incomingId")) {
            val incoming = requireNotNull(transactions[r.getString("incomingId")]) { "Repayment credit is missing." }
            require(incoming.getString("direction") == "Credit" && incoming.getString("status") == "Successful" &&
                incoming.getString("reviewState") != "NeedsReview" && incoming.getString("transactionType") !in setOf("SelfTransfer", "CardRepayment", "Refund", "Reversal") &&
                incoming.getString("ownership") != "SelfTransfer" && incoming.isNull("duplicateOfId") && incoming.optString("currency") == "INR") { "Repayment must link to eligible money in." }
            require(r.getString("method") != "Legacy" && r.getLong("receivedAt") == incoming.getLong("effectiveTimestamp")) { "Repayment date does not match its receipt." }
            if (r.getString("method") == "Cash") require(incoming.getString("sourceType") == "Manual" && incoming.getString("channel") == "CashManual") { "Invalid cash receipt." }
            val allocated = (creditTotals[r.getString("incomingId")] ?: BigInteger.ZERO) + BigInteger.valueOf(amount)
            require(allocated <= BigInteger.valueOf(incoming.getLong("amountMinor"))) { "Incoming payment is over-allocated." }
            creditTotals[r.getString("incomingId")] = allocated
        } else require(r.getString("method") == "Legacy") { "Incoming payment link is missing." }
    }
    transactions.forEach { (id, row) -> require(BigInteger.valueOf(row.getLong("repaidMinor")) == (expenseTotals[id] ?: BigInteger.ZERO)) { "Repayment history does not match its total." } }
    for (r in rowsByTable.getValue("category_rules").values) {
        require(r.getString("category") in transactionCategories && r.getString("merchant").trim().length in 2..60) { "Invalid category rule." }
        require(r.isNull("sourceId") || r.getString("sourceId") in sources) { "Rule source is missing." }
    }
    val prefs = data.getJSONObject("preferences")
    require(prefs.keys().asSequence().all { it in backupPreferences }) { "Unsupported backup preference." }
    for (key in prefs.keys()) when (key) {
        "preferred_name" -> require(prefs.get(key) is String && prefs.getString(key).length <= 40)
        "review_reminder_hour" -> require(prefs.get(key) is Number && prefs.getInt(key) in 0..23)
        "review_reminder_minute" -> require(prefs.get(key) is Number && prefs.getInt(key) in 0..59)
        else -> require(prefs.get(key) is Boolean)
    }
}

suspend fun TransactionRepository.csvReport(start: Long, end: Long): String = withLedger { db ->
    require(start < end) { "Choose a valid period." }
    val sources = db.transactions().allSources().associateBy { it.id }
    fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    buildString {
        append("Date,Label,Amount INR,Direction,Category,Purpose,Source,Repayment expected,Repaid INR,Review state\r\n")
        db.transactions().between(start, end).sortedBy { it.effectiveTimestamp }.forEach { r ->
            val values = listOf(java.time.Instant.ofEpochMilli(r.effectiveTimestamp).toString(),
                r.counterpartyLabel ?: r.groupLabel.orEmpty(), r.amountMinor?.let { java.math.BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty(),
                r.direction, r.category, r.ownership, sources[r.paymentSourceId]?.nickname.orEmpty(), r.repaymentExpected.toString(),
                java.math.BigDecimal.valueOf(r.repaidMinor, 2).toPlainString(), r.reviewState)
            append(values.joinToString(",", transform = ::cell)); append("\r\n")
        }
    }
}

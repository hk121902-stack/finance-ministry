package `in`.financeministry.app.data

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.parser.RuleBasedFinancialSmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class LedgerSnapshot(val rows: List<TransactionEntity>, val debit: BigInteger, val credit: BigInteger,
    val dailyDebit: BigInteger = BigInteger.ZERO, val dailyCredit: BigInteger = BigInteger.ZERO,
    val hasOlder: Boolean = false)

/** All mutation, capture and erasure share one gate. No raw source is stored. */
class TransactionRepository(private val context: Context, private val namespace: String = "finance") {
    private val mutex = Mutex()
    private var database: FinanceDatabase? = null
    private val dbName = "$namespace.db"
    private val secrets = DeviceSecrets(context, namespace)
    val preferences = context.getSharedPreferences("${namespace}_settings", Context.MODE_PRIVATE)
    val revision = MutableStateFlow(0L)
    val eraseGeneration = MutableStateFlow(0L)
    private val parser = RuleBasedFinancialSmsParser()
    private var importEpoch = UUID.randomUUID().toString()
    private fun db(): FinanceDatabase = database ?: FinanceDatabase.open(context,
        secrets.databasePassphrase(context.getDatabasePath(dbName).exists()), dbName).also { database = it }
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    suspend fun snapshot(offset: Int = 0, filter: String = "All", today: LocalDate = LocalDate.now()): LedgerSnapshot = locked {
        require(offset >= 0 && offset <= Int.MAX_VALUE - 101)
        require(filter in listOf("All", "Review", "Manual", "Edited"))
        if (database == null && !context.getDatabasePath(dbName).exists()) return@locked LedgerSnapshot(emptyList(), BigInteger.ZERO, BigInteger.ZERO)
        val zone = ZoneId.systemDefault()
        val month = today.withDayOfMonth(1)
        val rows = db().transactions().between(month.atStartOfDay(zone).toInstant().toEpochMilli(), month.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val reversedOriginals = db().transactions().reversedOriginals().toSet()
        fun sum(direction: Direction, daily: Boolean = false) = rows.filter { it.id !in reversedOriginals && (!daily || it.effectiveTimestamp in dayStart until dayEnd) && it.direction == direction.name && it.status == TransactionStatus.Successful.name &&
            it.reviewState != ReviewState.NeedsReview.name && it.transactionType !in listOf(TransactionType.SelfTransfer.name, TransactionType.CardRepayment.name) }
            .fold(BigInteger.ZERO) { total, row -> total + BigInteger.valueOf(row.amountMinor ?: 0) }
        val page = db().transactions().page(filter, 101, offset)
        LedgerSnapshot(page.take(100), sum(Direction.Debit), sum(Direction.Credit),
            sum(Direction.Debit, true), sum(Direction.Credit, true), page.size > 100)
    }

    suspend fun get(id: String): TransactionEntity? = locked {
        if (database == null && !context.getDatabasePath(dbName).exists()) null else db().transactions().get(id)
    }

    fun captureAllowed(): Boolean = preferences.getBoolean("sms_disclosure", false) &&
        context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun historyPermissionGranted(): Boolean = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun previewImport(source: HistoricalSmsSource = AndroidHistoricalSmsSource(context), now: Long = System.currentTimeMillis(),
        progress: (Int) -> Unit = {}): ImportPreview = withContext(Dispatchers.IO) {
        check(historyPermissionGranted()) { "Reading existing SMS is not permitted." }
        val epoch = locked { importEpoch }
        val window = ImportWindow.lastThreeMonths(now)
        val candidates = mutableListOf<ImportCandidate>()
        val seen = mutableSetOf<String>()
        var scanned = 0; var ignored = 0; var duplicates = 0
        source.read(window) { message ->
            currentCoroutineContext().ensureActive()
            check(historyPermissionGranted()) { "SMS permission was removed." }
            check(++scanned <= 20000) { "Too many messages to preview safely. Nothing was imported." }
            if (!window.contains(message.date)) { ignored++ }
            else {
                val parsed = parser.parse(IncomingSms(message.sender, message.date, message.body))
                if (parsed.decision == ParseDecision.Reject) ignored++ else locked {
                    check(epoch == importEpoch) { "Data changed. Start a new scan." }
                    val timestamp = message.sentDate.takeIf { it > 0 } ?: message.date
                    val primary = secrets.hmacSource(message.sender, timestamp, message.body)
                    val alternate = secrets.hmacSource(message.sender, message.date, message.body)
                    val keys = listOf(primary, alternate).map { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                    val dao = if (database != null || context.getDatabasePath(dbName).exists()) db().transactions() else null
                    if (keys.any { it in seen } || dao?.hasFingerprint(primary) == true || dao?.hasFingerprint(alternate) == true) duplicates++
                    else {
                        check(candidates.size < 5000) { "Too many transactions to preview safely. Nothing was imported." }
                        val review = parsed.decision == ParseDecision.NeedsReview || parsed.transactionType in listOf(TransactionType.Refund, TransactionType.Reversal)
                        val row = TransactionEntity(UUID.randomUUID().toString(), primary, SourceType.SMS.name, message.date, message.date,
                            parsed.amountMinor, parsed.currency, parsed.direction.name, parsed.status.name, parsed.channel.name,
                            parsed.transactionType.name, parsed.counterpartyLabel, parsed.maskedAccountHint,
                            confidence = parsed.confidence, reviewState = if (review) "NeedsReview" else "AutoRecorded",
                            parserVersion = parsed.parserVersion, createdAt = now, updatedAt = now)
                        candidates += ImportCandidate(row, alternate)
                    }
                    seen.addAll(keys)
                }
            }
            if (scanned % 50 == 0) progress(scanned)
        }
        locked { check(epoch == importEpoch) { "Data changed. Start a new scan." } }
        ImportPreview(window, scanned, ignored, duplicates, epoch, candidates.toList())
    }

    suspend fun commitImport(preview: ImportPreview): ImportResult {
        val operation = currentCoroutineContext()
        return locked {
            operation.ensureActive()
            check(preview.epoch == importEpoch) { "This preview expired. Scan again." }
            check(historyPermissionGranted()) { "SMS permission was removed. Scan again." }
            val batchId = UUID.randomUUID().toString()
            var inserted = 0; var duplicates = preview.duplicates
            val dao = db().transactions()
            db().runInTransaction {
                for (candidate in preview.candidates) {
                    operation.ensureActive()
                    if (dao.hasFingerprint(candidate.row.sourceFingerprint!!) || dao.hasFingerprint(candidate.alternateFingerprint)) duplicates++
                    else if (dao.insert(candidate.row.copy(importBatchId = batchId)) != -1L) inserted++ else duplicates++
                }
                operation.ensureActive()
                if (inserted > 0) dao.insertBatch(ImportBatchEntity(batchId, System.currentTimeMillis(), preview.window.start, preview.window.end, inserted))
            }
            revision.value++
            ImportResult(batchId, inserted, duplicates)
        }
    }

    suspend fun latestImport(): ImportBatchEntity? = locked {
        if (database == null && !context.getDatabasePath(dbName).exists()) null else db().transactions().latestImport()
    }

    suspend fun undoImport(batchId: String): Int = locked {
        if (database == null && !context.getDatabasePath(dbName).exists()) return@locked 0
        val dao = db().transactions()
        val rows = dao.untouchedImport(batchId)
        db().runInTransaction {
            rows.forEach { dao.unlinkFrom(it.id); dao.delete(it.id) }
            dao.deleteBatch(batchId)
        }
        rows.forEach { context.getSystemService(NotificationManager::class.java).cancel(it.id, 1) }
        revision.value++
        rows.size
    }

    suspend fun ingest(sms: IncomingSms, onSaved: (TransactionEntity) -> Unit = {}): Boolean = locked {
        if (!captureAllowed()) return@locked false
        val parsed = parser.parse(sms)
        if (parsed.decision == ParseDecision.Reject) return@locked false
        val now = System.currentTimeMillis()
        val referenceHash = `in`.financeministry.app.parser.TransactionReference.extract(sms.body)?.let {
            secrets.hmacSource("transaction-reference-v1:${sms.sender.lowercase(java.util.Locale.ROOT)}", 0, it)
        }
        var row = TransactionEntity(id = UUID.randomUUID().toString(), sourceFingerprint = secrets.hmacSource(sms.sender, sms.receivedAtMillis, sms.body),
            sourceType = SourceType.SMS.name, sourceTimestamp = sms.receivedAtMillis, effectiveTimestamp = sms.receivedAtMillis,
            amountMinor = parsed.amountMinor, currency = parsed.currency, direction = parsed.direction.name, status = parsed.status.name,
            channel = parsed.channel.name, transactionType = parsed.transactionType.name, maskedAccountHint = parsed.maskedAccountHint,
            counterpartyLabel = parsed.counterpartyLabel,
            confidence = parsed.confidence, reviewState = if (parsed.decision == ParseDecision.Record) ReviewState.AutoRecorded.name else ReviewState.NeedsReview.name,
            parserVersion = parsed.parserVersion, createdAt = now, updatedAt = now, referenceHash = referenceHash)
        val dao = db().transactions()
        var inserted = false
        db().runInTransaction {
            val adjustment = row.transactionType in listOf("Refund", "Reversal")
            if (adjustment) {
                val originals = referenceHash?.let(dao::byReference).orEmpty().filter {
                    it.sourceType == "SMS" && !it.isUserCorrected && it.status == "Successful" && it.direction == "Debit" &&
                        it.transactionType !in listOf("Refund", "Reversal", "SelfTransfer") && it.reviewState == "AutoRecorded" &&
                        it.amountMinor == row.amountMinor && it.currency == row.currency &&
                        it.maskedAccountHint != null && it.maskedAccountHint == row.maskedAccountHint &&
                        it.channel != "Unknown" && it.channel == row.channel && it.sourceTimestamp <= row.sourceTimestamp &&
                        dao.linkedTo(it.id).isEmpty()
                }
                val validAdjustment = parsed.decision == ParseDecision.Record &&
                    ((row.transactionType == "Refund" && row.direction == "Credit" && row.status == "Successful") ||
                        (row.transactionType == "Reversal" && row.status == "Reversed"))
                row = if (validAdjustment && originals.size == 1) row.copy(linkedOriginalId = originals.single().id)
                    else row.copy(reviewState = "NeedsReview")
            }
            inserted = dao.insert(row) != -1L
        }
        if (!inserted) return@locked false
        revision.value++
        // Insertion is committed. Notification failure must never roll it back; erase cannot race posting.
        try { onSaved(row) } catch (_: Exception) { /* OS notification availability is independent of capture. */ }
        true
    }

    suspend fun save(input: ManualInput, id: String? = null, newId: String = UUID.randomUUID().toString()): String = locked {
        input.validate()
        val dao = db().transactions()
        val old = id?.let { requireNotNull(dao.get(it)) { "This transaction no longer exists." } }
        val now = System.currentTimeMillis()
        val row = TransactionEntity(id = old?.id ?: newId, sourceFingerprint = old?.sourceFingerprint,
            sourceType = old?.sourceType ?: SourceType.Manual.name, sourceTimestamp = old?.sourceTimestamp ?: input.timestamp,
            effectiveTimestamp = input.timestamp, amountMinor = input.amountMinor(), direction = input.direction.name, status = input.status.name,
            channel = input.channel.name, transactionType = input.type.name, counterpartyLabel = input.label.trim().ifBlank { null },
            userNotes = input.notes.trim().ifBlank { null }, maskedAccountHint = input.accountHint.takeIf { it.isNotEmpty() }?.let { "••••$it" },
            confidence = old?.confidence ?: 0, reviewState = ReviewState.Confirmed.name, parserVersion = old?.parserVersion ?: 0,
            isUserCorrected = old != null, createdAt = old?.createdAt ?: now, updatedAt = now, importBatchId = old?.importBatchId)
        db().runInTransaction {
            if (old == null) dao.insert(row) else {
                // Corrections override automation; discard any links affected by the edit.
                dao.unlinkFrom(old.id)
                fun fields(r: TransactionEntity) = mapOf("amountMinor" to r.amountMinor?.toString(), "direction" to r.direction,
                    "status" to r.status, "channel" to r.channel, "transactionType" to r.transactionType, "effectiveTimestamp" to r.effectiveTimestamp.toString(),
                    "counterpartyLabel" to r.counterpartyLabel, "maskedAccountHint" to r.maskedAccountHint, "userNotes" to r.userNotes, "reviewState" to r.reviewState)
                val before = fields(old)
                dao.update(row)
                dao.audit(fields(row).filter { (key, value) -> before[key] != value }.map { (key, value) ->
                    CorrectionEntity(UUID.randomUUID().toString(), row.id, now, key, before[key], value) })
            }
        }
        revision.value++
        row.id
    }

    suspend fun delete(id: String) = locked {
        db().runInTransaction { db().transactions().unlinkFrom(id); db().transactions().delete(id) }
        context.getSystemService(NotificationManager::class.java).cancel(id, 1)
        revision.value++
    }

    suspend fun eraseAll() = locked {
        importEpoch = UUID.randomUUID().toString()
        eraseGeneration.value++
        // Disable capture before deletion; queued broadcasts recheck it inside this same mutex.
        check(preferences.edit().clear().commit())
        database?.close(); database = null
        val file = context.getDatabasePath(dbName)
        if (file.exists()) check(context.deleteDatabase(dbName)) { "Database deletion failed. Capture remains off." }
        secrets.erase()
        if (namespace == "finance") context.getSystemService(NotificationManager::class.java).cancelAll()
        revision.value++
    }

    suspend fun close() = locked { database?.close(); database = null }
}

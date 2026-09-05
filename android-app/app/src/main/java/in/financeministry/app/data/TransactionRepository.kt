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
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class LedgerSnapshot(val rows: List<TransactionEntity>, val debit: BigInteger, val credit: BigInteger)

/** All mutation, capture and erasure share one gate. No raw source is stored. */
class TransactionRepository(private val context: Context, private val namespace: String = "finance") {
    private val mutex = Mutex()
    private var database: FinanceDatabase? = null
    private val dbName = "$namespace.db"
    private val secrets = DeviceSecrets(context, namespace)
    val preferences = context.getSharedPreferences("${namespace}_settings", Context.MODE_PRIVATE)
    val revision = MutableStateFlow(0L)
    private val parser = RuleBasedFinancialSmsParser()
    private fun db(): FinanceDatabase = database ?: FinanceDatabase.open(context,
        secrets.databasePassphrase(context.getDatabasePath(dbName).exists()), dbName).also { database = it }
    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    suspend fun snapshot(): LedgerSnapshot = locked {
        if (database == null && !context.getDatabasePath(dbName).exists()) return@locked LedgerSnapshot(emptyList(), BigInteger.ZERO, BigInteger.ZERO)
        val zone = ZoneId.systemDefault()
        val month = LocalDate.now(zone).withDayOfMonth(1)
        val rows = db().transactions().between(month.atStartOfDay(zone).toInstant().toEpochMilli(), month.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli())
        fun sum(direction: Direction) = rows.filter { it.direction == direction.name && it.status == TransactionStatus.Successful.name &&
            it.reviewState != ReviewState.NeedsReview.name && it.transactionType != TransactionType.SelfTransfer.name }
            .fold(BigInteger.ZERO) { total, row -> total + BigInteger.valueOf(row.amountMinor ?: 0) }
        LedgerSnapshot(db().transactions().latest(), sum(Direction.Debit), sum(Direction.Credit))
    }

    suspend fun get(id: String): TransactionEntity? = locked {
        if (database == null && !context.getDatabasePath(dbName).exists()) null else db().transactions().get(id)
    }

    fun captureAllowed(): Boolean = preferences.getBoolean("sms_disclosure", false) &&
        context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun ingest(sms: IncomingSms, onSaved: (TransactionEntity) -> Unit = {}): Boolean = locked {
        if (!captureAllowed()) return@locked false
        val parsed = parser.parse(sms)
        if (parsed.decision == ParseDecision.Reject) return@locked false
        val now = System.currentTimeMillis()
        val row = TransactionEntity(id = UUID.randomUUID().toString(), sourceFingerprint = secrets.hmacSource(sms.sender, sms.receivedAtMillis, sms.body),
            sourceType = SourceType.SMS.name, sourceTimestamp = sms.receivedAtMillis, effectiveTimestamp = sms.receivedAtMillis,
            amountMinor = parsed.amountMinor, currency = parsed.currency, direction = parsed.direction.name, status = parsed.status.name,
            channel = parsed.channel.name, transactionType = parsed.transactionType.name, maskedAccountHint = parsed.maskedAccountHint,
            confidence = parsed.confidence, reviewState = if (parsed.decision == ParseDecision.Record) ReviewState.AutoRecorded.name else ReviewState.NeedsReview.name,
            parserVersion = parsed.parserVersion, createdAt = now, updatedAt = now)
        if (db().transactions().insert(row) == -1L) return@locked false
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
            isUserCorrected = old != null, createdAt = old?.createdAt ?: now, updatedAt = now)
        db().runInTransaction {
            if (old == null) dao.insert(row) else {
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
        db().transactions().delete(id)
        context.getSystemService(NotificationManager::class.java).cancel(id, 1)
        revision.value++
    }

    suspend fun eraseAll() = locked {
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

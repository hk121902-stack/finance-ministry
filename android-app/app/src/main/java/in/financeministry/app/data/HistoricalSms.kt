package `in`.financeministry.app.data

import android.content.Context
import android.provider.Telephony
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId

data class ImportWindow(val start: Long, val end: Long) {
    fun contains(timestamp: Long) = timestamp in start..end
    companion object {
        fun lastThreeMonths(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): ImportWindow =
            ImportWindow(Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusMonths(3)
                .atStartOfDay(zone).toInstant().toEpochMilli(), now)
    }
}

/** Transient inbox input. Never log or persist the body/sender. */
class HistoricalSms(val sender: String, val date: Long, val sentDate: Long, val body: String) {
    override fun toString() = "HistoricalSms(redacted)"
}

fun interface HistoricalSmsSource {
    suspend fun read(window: ImportWindow, emit: suspend (HistoricalSms) -> Unit)
}

class AndroidHistoricalSmsSource(private val context: Context) : HistoricalSmsSource {
    override suspend fun read(window: ImportWindow, emit: suspend (HistoricalSms) -> Unit) {
        val columns = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.BODY)
        val cursor = context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, columns,
            "date >= ? AND date <= ?", arrayOf(window.start.toString(), window.end.toString()), "date ASC")
            ?: error("The SMS inbox is unavailable.")
        cursor.use {
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                emit(HistoricalSms(it.getString(0).orEmpty(), it.getLong(1), it.getLong(2), it.getString(3).orEmpty()))
            }
        }
    }
}

internal data class ImportCandidate(val row: TransactionEntity, val alternateFingerprint: ByteArray)

/** Normalized preview only; process death/navigation drops it without ledger writes. */
class ImportPreview internal constructor(
    val window: ImportWindow,
    val scanned: Int,
    val ignored: Int,
    val duplicates: Int,
    internal val epoch: String,
    internal val candidates: List<ImportCandidate>,
) {
    val rows: List<TransactionEntity> get() = candidates.map { it.row }
    val ready: Int get() = candidates.count { it.row.reviewState != "NeedsReview" }
    val needsReview: Int get() = candidates.size - ready
}

data class ImportResult(val batchId: String, val inserted: Int, val duplicates: Int)

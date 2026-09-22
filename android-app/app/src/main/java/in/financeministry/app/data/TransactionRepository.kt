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
    val hasOlder: Boolean = false, val personalSpend: BigInteger = BigInteger.ZERO,
    val paidForOthers: BigInteger = BigInteger.ZERO, val outstandingRepayments: BigInteger = BigInteger.ZERO,
    val selectedMonthOutstandingRepayments: BigInteger = BigInteger.ZERO,
    val selectedMonth: LocalDate = LocalDate.now().withDayOfMonth(1),
    val reversedOriginalIds: Set<String> = emptySet(),
    val resultCount: Long = 0, val resultDebit: BigInteger = BigInteger.ZERO,
    val resultCredit: BigInteger = BigInteger.ZERO)

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
    internal var batchEpoch = UUID.randomUUID().toString()
    internal var pendingBackupDigest: String? = null
    internal var pendingBackupRevision: Long? = null
    internal var pendingBackupFileDigest: String? = null
    internal val applicationContext get() = context
    internal val isMainLedger get() = namespace == "finance"
    private fun db(): FinanceDatabase {
        val result = database ?: FinanceDatabase.open(context,
            secrets.databasePassphrase(context.getDatabasePath(dbName).exists()), dbName).also { database = it }
        applyPendingRestoreSettings(result)
        return result
    }
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
    internal suspend fun <T> withLedger(block: (FinanceDatabase) -> T): T = locked { block(db()) }
    internal fun invalidateImportPreviews() { importEpoch = UUID.randomUUID().toString(); batchEpoch = UUID.randomUUID().toString() }

    suspend fun snapshot(offset: Int = 0, filter: String = "All", today: LocalDate = LocalDate.now(),
        currentDay: LocalDate = today, search: String = ""): LedgerSnapshot = locked {
        require(offset >= 0 && offset <= Int.MAX_VALUE - 101)
        val filterParts = filter.split("+")
        val purpose = filterParts.firstOrNull { it in listOf("Personal", "Family", "ForOthers", "Group", "SelfTransfer") } ?: "All"
        val origin = filterParts.firstOrNull { it in listOf("Manual", "Edited") } ?: "All"
        val direction = filterParts.firstOrNull { it in listOf("Debit", "Credit") } ?: "All"
        val categories = filterParts.filter { it.startsWith("Category:") }.map { it.removePrefix("Category:") }.distinct()
        val sourceIds = filterParts.filter { it.startsWith("Source:") }.map { it.removePrefix("Source:") }.distinct()
        require(filter == "All" || filter == "Review" || filterParts.all {
            it in listOf("Manual", "Edited", "Personal", "Family", "ForOthers", "Group", "SelfTransfer", "Debit", "Credit") ||
                (it.startsWith("Category:") && it.removePrefix("Category:") in transactionCategories) ||
                (it.startsWith("Source:") && it.removePrefix("Source:").matches(Regex("[A-Za-z0-9-]{1,80}")))
        })
        if (database == null && !context.getDatabasePath(dbName).exists()) return@locked LedgerSnapshot(emptyList(), BigInteger.ZERO, BigInteger.ZERO)
        val zone = ZoneId.systemDefault()
        val month = today.withDayOfMonth(1)
        val rows = db().transactions().between(month.atStartOfDay(zone).toInstant().toEpochMilli(), month.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val dayStart = currentDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = currentDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val reversedOriginals = db().transactions().reversedOriginals().toSet()
        fun eligible(row: TransactionEntity, daily: Boolean = false) = RepaymentAccounting.eligible(row, reversedOriginals) &&
            (!daily || row.effectiveTimestamp in dayStart until dayEnd)
        fun sum(direction: Direction, daily: Boolean = false) = rows.filter { eligible(it, daily) && it.direction == direction.name &&
            true }
            .fold(BigInteger.ZERO) { total, row -> total + BigInteger.valueOf(row.amountMinor ?: 0) }
        val monthStart = month.atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEnd = month.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val normalizedSearch = search.trim().take(80)
        val page = db().transactions().page(purpose, origin, direction, filter == "Review", monthStart, monthEnd, 101, offset,
            categories.isEmpty(), categories, sourceIds.isEmpty(), sourceIds, normalizedSearch)
        val resultTotals = db().transactions().filteredTotals(purpose, origin, direction, filter == "Review", monthStart, monthEnd,
            categories.isEmpty(), categories, sourceIds.isEmpty(), sourceIds, normalizedSearch)
        fun personal(row: TransactionEntity): Long = RepaymentAccounting.personal(row)
        fun owed(row: TransactionEntity): Long = RepaymentAccounting.owed(row)
        val eligibleDebits = rows.filter { eligible(it) && it.direction == Direction.Debit.name }
        LedgerSnapshot(page.take(100), sum(Direction.Debit), sum(Direction.Credit),
            sum(Direction.Debit, true), sum(Direction.Credit, true), page.size > 100,
            eligibleDebits.fold(BigInteger.ZERO) { total, row -> total + BigInteger.valueOf(personal(row)) },
            eligibleDebits.filter { it.ownership in listOf("ForOther", "Group") }.fold(BigInteger.ZERO) { total, row -> total + BigInteger.valueOf((row.amountMinor ?: 0) - personal(row)) },
            db().transactions().repaymentCandidates().filter { eligible(it) && it.direction == Direction.Debit.name }
                .fold(BigInteger.ZERO) { total, row -> total + BigInteger.valueOf(owed(row)) },
            eligibleDebits.filter { it.ownership in listOf("ForOther", "Group") }
                .fold(BigInteger.ZERO) { total, row -> total + BigInteger.valueOf(owed(row)) }, month, reversedOriginals,
            resultTotals.count, BigInteger.valueOf(resultTotals.debit), BigInteger.valueOf(resultTotals.credit))
    }

    suspend fun reviewCount(): Int = locked {
        if (database == null && !context.getDatabasePath(dbName).exists()) 0 else db().transactions().reviewCount()
    }

    suspend fun reviewQueue(offset: Int = 0): LedgerSnapshot = locked {
        require(offset >= 0 && offset <= Int.MAX_VALUE - 101)
        if (database == null && !context.getDatabasePath(dbName).exists()) return@locked LedgerSnapshot(emptyList(), BigInteger.ZERO, BigInteger.ZERO)
        val page = db().transactions().reviewPage(101, offset)
        LedgerSnapshot(page.take(100), BigInteger.ZERO, BigInteger.ZERO, hasOlder = page.size > 100,
            reversedOriginalIds = db().transactions().reversedOriginals().toSet())
    }

    suspend fun paymentSources(): List<PaymentSourceEntity> = locked { if (database == null && !context.getDatabasePath(dbName).exists()) emptyList() else db().transactions().allSources() }
    suspend fun activePaymentSources(): List<PaymentSourceEntity> = locked { if (database == null && !context.getDatabasePath(dbName).exists()) emptyList() else db().transactions().activeSources() }
    suspend fun addPaymentSource(nickname: String, kind: String, channel: Channel, bankName: String = "", last4: String = ""): PaymentSourceEntity = locked {
        require(nickname.trim().length in 2..40) { "Give this source a short name." }
        require(channel != Channel.Unknown && channel != Channel.CashManual) { "Choose UPI, card, or a bank transfer method." }
        require(last4.isBlank() || last4.matches(Regex("[0-9]{4}"))) { "Use four digits or leave it blank." }
        require(db().transactions().activeSources().none { it.nickname.equals(nickname.trim(), ignoreCase = true) }) { "Use a different source name." }
        val source = PaymentSourceEntity(UUID.randomUUID().toString(), nickname.trim(), kind, channel.name,
            bankName.trim().ifBlank { null }, last4.ifBlank { null }, true, System.currentTimeMillis())
        db().transactions().addSource(source); revision.value++; source
    }
    suspend fun updatePaymentSource(id: String, nickname: String, kind: String, channel: Channel, bankName: String = "", last4: String = ""): PaymentSourceEntity = locked {
        require(nickname.trim().length in 2..40) { "Give this source a short name." }
        require(channel !in listOf(Channel.Unknown, Channel.CashManual, Channel.Other)) { "Choose UPI, card, or a bank transfer method." }
        require(last4.isBlank() || last4.matches(Regex("[0-9]{4}"))) { "Use four digits or leave it blank." }
        val dao = db().transactions()
        val old = requireNotNull(dao.source(id)) { "This payment source no longer exists." }
        require(old.active) { "This payment source is inactive." }
        require(dao.sourceUsageCount(id) == 0 || old.channel == channel.name) {
            "This source is already used by transactions. Keep its payment method or create a new source."
        }
        require(dao.activeSources().none { it.id != id && it.nickname.equals(nickname.trim(), ignoreCase = true) }) { "Use a different source name." }
        val updated = old.copy(nickname = nickname.trim(), kind = kind, channel = channel.name,
            bankName = bankName.trim().ifBlank { null }, last4 = last4.ifBlank { null })
        dao.updateSource(updated); revision.value++; updated
    }
    suspend fun deletePaymentSource(id: String) = locked { db().transactions().retireSource(id); revision.value++ }
    suspend fun updateTransactionPaymentSource(transactionId: String, sourceId: String): TransactionEntity = locked {
        val dao = db().transactions()
        require(!dao.hasActiveFinancialMatch(transactionId)) { "Undo the match decision in Review before editing this transaction." }
        val old = requireNotNull(dao.get(transactionId)) { "This transaction no longer exists." }
        val source = requireNotNull(dao.source(sourceId)) { "Choose an available payment source." }
        require(source.active) { "Choose an active payment source." }
        require(source.channel == old.channel) { "The payment source does not support this payment method." }
        if (old.paymentSourceId == sourceId) return@locked old
        val now = maxOf(System.currentTimeMillis(), old.updatedAt + 1)
        val updated = old.copy(paymentSourceId = sourceId, updatedAt = now)
        db().runInTransaction {
            dao.update(updated)
            dao.audit(listOf(CorrectionEntity(UUID.randomUUID().toString(), old.id, now,
                "paymentSourceId", old.paymentSourceId, sourceId)))
        }
        revision.value++
        updated
    }
    /** Uses the sender only while processing the SMS; it is never persisted. */
    private fun mappedSourceId(channel: String, hint: String?, sender: String, dao: TransactionDao): String? =
        PaymentSourceMatcher.resolve(channel, hint, sender, dao.activeSources())

    suspend fun get(id: String): TransactionEntity? = locked {
        if (database == null && !context.getDatabasePath(dbName).exists()) null else db().transactions().get(id)
    }

    fun captureAllowed(): Boolean = preferences.getBoolean("sms_disclosure", false) &&
        context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun historyPermissionGranted(): Boolean = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    suspend fun restoredHistoryNeedsReview(): Boolean = locked {
        (database != null || context.getDatabasePath(dbName).exists()) && db().transactions().metadata("foreign_restore") == "true"
    }

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
                        val review = parsed.decision == ParseDecision.NeedsReview || parsed.transactionType in listOf(TransactionType.Refund, TransactionType.Reversal) ||
                            dao?.metadata("foreign_restore") == "true"
                        val row = TransactionEntity(UUID.randomUUID().toString(), primary, SourceType.SMS.name, message.date, message.date,
                            parsed.amountMinor, parsed.currency, parsed.direction.name, parsed.status.name, parsed.channel.name,
                            parsed.transactionType.name, parsed.counterpartyLabel, parsed.maskedAccountHint,
                            confidence = parsed.confidence, reviewState = if (review) "NeedsReview" else "AutoRecorded",
                            parserVersion = parsed.parserVersion, createdAt = now, updatedAt = now,
                            ownership = if (parsed.transactionType == TransactionType.SelfTransfer) SpendingOwnership.SelfTransfer.name else SpendingOwnership.Personal.name,
                            personalShareMinor = parsed.amountMinor.takeIf { parsed.transactionType == TransactionType.SelfTransfer })
                        candidates += ImportCandidate(row, alternate, message.sender)
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
                    else {
                        val mapped = mappedSourceId(candidate.row.channel, candidate.row.maskedAccountHint, candidate.transientSender, dao)
                        val imported = candidate.row.copy(importBatchId = batchId, paymentSourceId = mapped,
                            reviewState = if (dao.metadata("foreign_restore") == "true") "NeedsReview" else candidate.row.reviewState)
                        if (dao.insert(CategoryRules.apply(imported, dao.rules())) != -1L) inserted++ else duplicates++
                    }
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
        val rows = dao.untouchedImport(batchId).filter { dao.repaymentsFor(it.id).isEmpty() && dao.allocationsFrom(it.id).isEmpty() }
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
        if (parsed.transactionType == TransactionType.SelfTransfer) {
            row = row.copy(ownership = SpendingOwnership.SelfTransfer.name, personalShareMinor = parsed.amountMinor)
        }
        val dao = db().transactions()
        row = row.copy(paymentSourceId = mappedSourceId(row.channel, row.maskedAccountHint, sms.sender, dao))
        row = CategoryRules.apply(row, dao.rules())
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
        preferences.edit().putLong("last_capture_at", now).apply()
        revision.value++
        // Insertion is committed. Notification failure must never roll it back; erase cannot race posting.
        try { onSaved(row) } catch (_: Exception) { /* OS notification availability is independent of capture. */ }
        true
    }

    suspend fun save(input: ManualInput, id: String? = null, newId: String = UUID.randomUUID().toString(),
        rememberedRule: RememberCategoryRule? = null): String = locked {
        input.validate()
        val dao = db().transactions()
        val old = id?.let { requireNotNull(dao.get(it)) { "This transaction no longer exists." } }
        require(old == null || !dao.hasActiveFinancialMatch(old.id)) { "Undo the match decision in Review before editing this transaction." }
        input.paymentSourceId?.let { sourceId ->
            val source = requireNotNull(dao.source(sourceId)) { "Choose an available payment source." }
            require(source.channel == input.channel.name) { "The payment source does not support this payment method." }
            require(source.active || old?.paymentSourceId == sourceId) { "Choose an active payment source." }
        }
        val now = System.currentTimeMillis()
        var row = TransactionEntity(id = old?.id ?: newId, sourceFingerprint = old?.sourceFingerprint,
            sourceType = old?.sourceType ?: SourceType.Manual.name, sourceTimestamp = old?.sourceTimestamp ?: input.timestamp,
            effectiveTimestamp = input.timestamp, amountMinor = input.amountMinor(), currency = old?.currency ?: "INR",
            direction = input.direction.name, status = input.status.name,
            channel = input.channel.name, transactionType = input.normalizedType().name, counterpartyLabel = input.label.trim().ifBlank { null },
            userNotes = input.notes.trim().ifBlank { null }, maskedAccountHint = input.accountHint.takeIf { it.isNotEmpty() }?.let { "••••$it" },
            confidence = old?.confidence ?: 0, reviewState = ReviewState.Confirmed.name, parserVersion = old?.parserVersion ?: 0,
            isUserCorrected = old != null, createdAt = old?.createdAt ?: now, updatedAt = now, importBatchId = old?.importBatchId,
            referenceHash = old?.referenceHash, linkedOriginalId = old?.linkedOriginalId,
            category = input.category.trim(), ownership = input.normalizedOwnership().name,
            groupLabel = input.groupLabel.trim().takeIf { input.normalizedOwnership() in setOf(SpendingOwnership.Group, SpendingOwnership.ForOther) && it.isNotBlank() }, personalShareMinor = input.personalShareMinor(),
            repaidMinor = input.repaidMinor(), paymentSourceId = input.paymentSourceId,
            repaymentExpected = input.repaymentExpected, duplicateOfId = old?.duplicateOfId, categoryNeedsReview = false)
        if (old != null) validateRepaymentEdit(dao, old, row)
        val rule = rememberedRule?.let { buildRememberedRule(dao, row, it) }
        db().runInTransaction {
            if (old == null) dao.insert(row) else {
                val linkSensitiveTypes = setOf("Refund", "Reversal", "SelfTransfer", "CardRepayment")
                val linkSensitiveTypeChanged = old.transactionType != row.transactionType &&
                    (old.transactionType in linkSensitiveTypes || row.transactionType in linkSensitiveTypes)
                val financialIdentityChanged = old.amountMinor != row.amountMinor || old.currency != row.currency ||
                    old.direction != row.direction || old.status != row.status || old.channel != row.channel ||
                    linkSensitiveTypeChanged || old.effectiveTimestamp != row.effectiveTimestamp ||
                    old.maskedAccountHint != row.maskedAccountHint
                if (financialIdentityChanged) {
                    dao.unlinkFrom(old.id)
                    row = row.copy(linkedOriginalId = null)
                }
                fun fields(r: TransactionEntity) = mapOf("amountMinor" to r.amountMinor?.toString(), "direction" to r.direction,
                    "status" to r.status, "channel" to r.channel, "transactionType" to r.transactionType, "effectiveTimestamp" to r.effectiveTimestamp.toString(),
                    "counterpartyLabel" to r.counterpartyLabel, "maskedAccountHint" to r.maskedAccountHint, "userNotes" to r.userNotes, "reviewState" to r.reviewState,
                    "category" to r.category, "ownership" to r.ownership, "groupLabel" to r.groupLabel,
                    "personalShareMinor" to r.personalShareMinor?.toString(), "repaidMinor" to r.repaidMinor.toString(), "paymentSourceId" to r.paymentSourceId,
                    "repaymentExpected" to r.repaymentExpected.toString())
                val before = fields(old)
                dao.update(row)
                dao.audit(fields(row).filter { (key, value) -> before[key] != value }.map { (key, value) ->
                    CorrectionEntity(UUID.randomUUID().toString(), row.id, now, key, before[key], value) })
            }
            syncLegacyRepayment(dao, row)
            rule?.let(dao::saveRule)
        }
        revision.value++
        row.id
    }

    /** Labels never confirm a payment or change its parsed financial fields. */
    suspend fun classify(id: String, category: String, ownership: SpendingOwnership,
        group: String = "", share: String = "", repaid: String = "", repaymentExpected: Boolean = true,
        rememberedRule: RememberCategoryRule? = null) = locked {
        val dao = db().transactions()
        val old = requireNotNull(dao.get(id)) { "This transaction no longer exists." }
        require(!dao.hasActiveFinancialMatch(id)) { "Undo the match decision in Review before editing this transaction." }
        val amount = requireNotNull(old.amountMinor) { "Review the amount first using Edit all details." }
        val input = ManualInput(java.math.BigDecimal.valueOf(amount, 2).toPlainString(), Direction.Debit,
            old.effectiveTimestamp, TransactionType.Other, category = category, ownership = ownership,
            groupLabel = group, personalShare = share, repaid = repaid, repaymentExpected = repaymentExpected)
        input.validate()
        val now = System.currentTimeMillis()
        val type = if (ownership == SpendingOwnership.SelfTransfer) "SelfTransfer"
            else if (old.transactionType == "SelfTransfer") "Other" else old.transactionType
        val row = old.copy(category = category.trim(), ownership = ownership.name,
            groupLabel = group.trim().takeIf { ownership in setOf(SpendingOwnership.Group, SpendingOwnership.ForOther) && it.isNotBlank() },
            personalShareMinor = input.personalShareMinor(), repaidMinor = input.repaidMinor(),
            repaymentExpected = repaymentExpected, categoryNeedsReview = false,
            transactionType = type, linkedOriginalId = old.linkedOriginalId.takeIf { type == old.transactionType },
            isUserCorrected = true, updatedAt = now)
        fun fields(r: TransactionEntity) = mapOf("category" to r.category, "ownership" to r.ownership,
            "groupLabel" to r.groupLabel, "personalShareMinor" to r.personalShareMinor?.toString(),
            "repaidMinor" to r.repaidMinor.toString(), "transactionType" to r.transactionType,
            "repaymentExpected" to r.repaymentExpected.toString())
        validateRepaymentEdit(dao, old, row)
        val rule = rememberedRule?.let { buildRememberedRule(dao, row, it) }
        db().runInTransaction {
            if (type != old.transactionType) dao.unlinkFrom(id)
            dao.update(row)
            val before = fields(old)
            dao.audit(fields(row).filter { (key, value) -> before[key] != value }.map { (key, value) ->
                CorrectionEntity(UUID.randomUUID().toString(), id, now, key, before[key], value) })
            syncLegacyRepayment(dao, row)
            rule?.let(dao::saveRule)
        }
        revision.value++
    }

    suspend fun delete(id: String) = locked {
        require(!db().transactions().hasActiveFinancialMatch(id)) { "Undo this transaction's match decision before deleting it. Both payment records must stay available." }
        require(db().transactions().repaymentsFor(id).isEmpty() && db().transactions().allocationsFrom(id).isEmpty()) {
            "Remove repayment links before deleting this transaction. Incoming payments are kept when unlinked."
        }
        db().runInTransaction { db().transactions().unlinkFrom(id); db().transactions().delete(id) }
        context.getSystemService(NotificationManager::class.java).cancel(id, 1)
        revision.value++
    }

    suspend fun eraseAll() = locked {
        pendingBackupDigest = null; pendingBackupRevision = null; pendingBackupFileDigest = null
        importEpoch = UUID.randomUUID().toString()
        eraseGeneration.value++
        // Disable capture before deletion; queued broadcasts recheck it inside this same mutex.
        check(preferences.edit().clear().commit())
        database?.close(); database = null
        val file = context.getDatabasePath(dbName)
        if (file.exists()) check(context.deleteDatabase(dbName)) { "Database deletion failed. Capture remains off." }
        secrets.erase()
        if (namespace == "finance") context.getSystemService(NotificationManager::class.java).cancelAll()
        if (namespace == "finance") `in`.financeministry.app.sms.ReviewReminder.cancel(context)
        if (namespace == "finance") `in`.financeministry.app.sms.RecurringPaymentReminder.cancel(context)
        revision.value++
    }

    suspend fun close() = locked { database?.close(); database = null }
}

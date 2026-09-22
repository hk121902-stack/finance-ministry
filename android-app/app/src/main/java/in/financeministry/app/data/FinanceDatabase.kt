package `in`.financeministry.app.data

import android.content.Context
import androidx.room.*
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "transactions", indices = [Index(value = ["sourceFingerprint"], unique = true)])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val sourceFingerprint: ByteArray? = null,
    val sourceType: String,
    val sourceTimestamp: Long,
    val effectiveTimestamp: Long,
    val amountMinor: Long?,
    val currency: String? = "INR",
    val direction: String,
    val status: String,
    val channel: String,
    val transactionType: String,
    val counterpartyLabel: String? = null,
    val maskedAccountHint: String? = null,
    val userNotes: String? = null,
    val confidence: Int = 0,
    val reviewState: String,
    val parserVersion: Int = 0,
    val isUserCorrected: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val referenceHash: ByteArray? = null,
    val linkedOriginalId: String? = null,
    val importBatchId: String? = null,
    val category: String = "Other",
    val ownership: String = "Personal",
    val groupLabel: String? = null,
    val personalShareMinor: Long? = null,
    val repaidMinor: Long = 0,
    val paymentSourceId: String? = null,
    @ColumnInfo(defaultValue = "1") val repaymentExpected: Boolean = true,
    val duplicateOfId: String? = null,
    @ColumnInfo(defaultValue = "0") val categoryNeedsReview: Boolean = false,
)

/** An allocation, not a second receipt. Legacy entries deliberately have no invented date. */
@Entity(tableName = "repayments", foreignKeys = [
    ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["expenseId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["incomingId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("expenseId"), Index("incomingId")])
data class RepaymentEntity(
    @PrimaryKey val id: String,
    val expenseId: String,
    val incomingId: String?,
    val amountMinor: Long,
    val receivedAt: Long?,
    val payer: String,
    val method: String,
    val createdAt: Long,
)

@Entity(tableName = "ledger_metadata")
data class LedgerMetadataEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "match_decisions", foreignKeys = [
    ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["firstId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["secondId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("firstId"), Index("secondId")])
data class MatchDecisionEntity(@PrimaryKey val id: String, val pairKey: String, val action: String,
    val firstId: String, val secondId: String, val beforeFirst: String, val beforeSecond: String,
    val afterFirst: String, val afterSecond: String, val createdAt: Long, val undoneAt: Long? = null)

@Entity(tableName = "budgets", indices = [Index(value = ["category"], unique = true)])
data class BudgetEntity(@PrimaryKey val id: String, val category: String, val monthlyLimitMinor: Long,
    val alertPercent: Int?, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "recurring_reminders", foreignKeys = [ForeignKey(entity = TransactionEntity::class,
    parentColumns = ["id"], childColumns = ["lastLinkedTransactionId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("lastLinkedTransactionId")])
data class RecurringReminderEntity(@PrimaryKey val id: String, val title: String, val amountMinor: Long?,
    val preferredDay: Int, val category: String, val active: Boolean, val lastLinkedTransactionId: String?,
    val lastLinkedAt: Long?, val createdAt: Long, val updatedAt: Long)

/** A local nickname used to map a payment channel to one of the user's sources. */
@Entity(tableName = "payment_sources")
data class PaymentSourceEntity(
    @PrimaryKey val id: String,
    val nickname: String,
    val kind: String,
    val channel: String,
    val bankName: String? = null,
    val last4: String? = null,
    val active: Boolean = true,
    val createdAt: Long,
)

@Entity(tableName = "import_batches")
data class ImportBatchEntity(@PrimaryKey val id: String, val createdAt: Long, val start: Long, val end: Long, val inserted: Int)

@Entity(tableName = "corrections", foreignKeys = [ForeignKey(entity = TransactionEntity::class,
    parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("transactionId")])
data class CorrectionEntity(@PrimaryKey val id: String, val transactionId: String, val correctedAt: Long,
    val fieldName: String, val previousValue: String?, val newValue: String?)

/** A result-set subtotal. This is deliberately separate from the monthly ledger totals. */
data class TransactionResultTotals(val count: Long, val debit: Long, val credit: Long)

@Dao
interface TransactionDao {
    @Query("SELECT * FROM budgets ORDER BY category, id") fun budgets(): List<BudgetEntity>
    @Query("SELECT * FROM budgets WHERE id = :id") fun budget(id: String): BudgetEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) fun addBudget(row: BudgetEntity)
    @Update fun updateBudget(row: BudgetEntity)
    @Query("DELETE FROM budgets WHERE id = :id") fun deleteBudget(id: String)
    @Query("SELECT * FROM recurring_reminders ORDER BY preferredDay, title, id") fun recurringReminders(): List<RecurringReminderEntity>
    @Query("SELECT * FROM recurring_reminders WHERE id = :id") fun recurringReminder(id: String): RecurringReminderEntity?
    @Insert fun addRecurringReminder(row: RecurringReminderEntity)
    @Update fun updateRecurringReminder(row: RecurringReminderEntity)
    @Query("DELETE FROM recurring_reminders WHERE id = :id") fun deleteRecurringReminder(id: String)
    @Query("SELECT * FROM match_decisions ORDER BY createdAt DESC, id DESC") fun matchDecisions(): List<MatchDecisionEntity>
    @Query("SELECT * FROM match_decisions WHERE id = :id") fun matchDecision(id: String): MatchDecisionEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM match_decisions WHERE undoneAt IS NULL AND action != 'KeepBoth' AND (firstId = :id OR secondId = :id))")
    fun hasActiveFinancialMatch(id: String): Boolean
    @Insert fun addMatchDecision(decision: MatchDecisionEntity)
    @Update fun updateMatchDecision(decision: MatchDecisionEntity)
    @Query("SELECT value FROM ledger_metadata WHERE `key` = :key") fun metadata(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun metadata(row: LedgerMetadataEntity)
    @Query("DELETE FROM ledger_metadata WHERE `key` = :key") fun removeMetadata(key: String)
    @Query("SELECT * FROM category_rules ORDER BY createdAt DESC") fun rules(): List<CategoryRuleEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveRule(rule: CategoryRuleEntity)
    @Query("DELETE FROM category_rules WHERE id = :id") fun deleteRule(id: String)
    @Query("SELECT * FROM transactions ORDER BY effectiveTimestamp DESC, id DESC") fun all(): List<TransactionEntity>
    @Query("SELECT * FROM repayments ORDER BY receivedAt DESC, createdAt DESC") fun repayments(): List<RepaymentEntity>
    @Query("SELECT * FROM repayments WHERE expenseId = :id ORDER BY receivedAt DESC, createdAt DESC") fun repaymentsFor(id: String): List<RepaymentEntity>
    @Query("SELECT * FROM repayments WHERE incomingId = :id") fun allocationsFrom(id: String): List<RepaymentEntity>
    @Insert fun addRepayment(row: RepaymentEntity)
    @Update fun updateRepayment(row: RepaymentEntity)
    @Query("DELETE FROM repayments WHERE id = :id") fun deleteRepayment(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(row: TransactionEntity): Long
    @Update fun update(row: TransactionEntity)
    @Insert fun audit(rows: List<CorrectionEntity>)
    @Query("SELECT * FROM transactions ORDER BY effectiveTimestamp DESC LIMIT 500") fun latest(): List<TransactionEntity>
    @Query("SELECT * FROM transactions WHERE ownership IN ('ForOther', 'Group')") fun repaymentCandidates(): List<TransactionEntity>
    @Query("SELECT COUNT(*) FROM transactions WHERE reviewState = 'NeedsReview'") suspend fun reviewCount(): Int
    @Query("SELECT * FROM transactions WHERE reviewState = 'NeedsReview' ORDER BY effectiveTimestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    fun reviewPage(limit: Int, offset: Int): List<TransactionEntity>
    @Query("SELECT * FROM transactions WHERE effectiveTimestamp >= :start AND effectiveTimestamp < :end AND (:reviewOnly = 0 OR reviewState = 'NeedsReview') AND (:direction = 'All' OR direction = :direction) AND (:purpose = 'All' OR (:purpose = 'Personal' AND ownership = 'Personal') OR (:purpose = 'Family' AND ownership = 'Family') OR (:purpose = 'ForOthers' AND ownership = 'ForOther') OR (:purpose = 'Group' AND ownership = 'Group') OR (:purpose = 'SelfTransfer' AND (ownership = 'SelfTransfer' OR transactionType = 'SelfTransfer'))) AND (:origin = 'All' OR (:origin = 'Manual' AND sourceType = 'Manual') OR (:origin = 'Edited' AND isUserCorrected = 1)) AND (:allCategories = 1 OR category IN (:categories)) AND (:allSources = 1 OR paymentSourceId IN (:sourceIds)) AND (:search = '' OR LOWER(COALESCE(counterpartyLabel, '')) LIKE '%' || LOWER(:search) || '%' OR LOWER(COALESCE(userNotes, '')) LIKE '%' || LOWER(:search) || '%') ORDER BY effectiveTimestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    fun page(purpose: String, origin: String, direction: String, reviewOnly: Boolean, start: Long, end: Long, limit: Int, offset: Int, allCategories: Boolean, categories: List<String>, allSources: Boolean, sourceIds: List<String>, search: String): List<TransactionEntity>
    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(CASE WHEN direction = 'Debit' AND duplicateOfId IS NULL AND currency = 'INR' AND amountMinor > 0 AND status = 'Successful' AND reviewState != 'NeedsReview' AND transactionType NOT IN ('SelfTransfer', 'CardRepayment') AND ownership != 'SelfTransfer' AND id NOT IN (SELECT linkedOriginalId FROM transactions WHERE linkedOriginalId IS NOT NULL AND status = 'Reversed' AND reviewState != 'NeedsReview') THEN COALESCE(amountMinor, 0) ELSE 0 END), 0) AS debit, COALESCE(SUM(CASE WHEN direction = 'Credit' AND duplicateOfId IS NULL AND currency = 'INR' AND amountMinor > 0 AND status = 'Successful' AND reviewState != 'NeedsReview' AND transactionType NOT IN ('SelfTransfer', 'CardRepayment') AND ownership != 'SelfTransfer' AND id NOT IN (SELECT linkedOriginalId FROM transactions WHERE linkedOriginalId IS NOT NULL AND status = 'Reversed' AND reviewState != 'NeedsReview') THEN COALESCE(amountMinor, 0) ELSE 0 END), 0) AS credit FROM transactions WHERE effectiveTimestamp >= :start AND effectiveTimestamp < :end AND (:reviewOnly = 0 OR reviewState = 'NeedsReview') AND (:direction = 'All' OR direction = :direction) AND (:purpose = 'All' OR (:purpose = 'Personal' AND ownership = 'Personal') OR (:purpose = 'Family' AND ownership = 'Family') OR (:purpose = 'ForOthers' AND ownership = 'ForOther') OR (:purpose = 'Group' AND ownership = 'Group') OR (:purpose = 'SelfTransfer' AND (ownership = 'SelfTransfer' OR transactionType = 'SelfTransfer'))) AND (:origin = 'All' OR (:origin = 'Manual' AND sourceType = 'Manual') OR (:origin = 'Edited' AND isUserCorrected = 1)) AND (:allCategories = 1 OR category IN (:categories)) AND (:allSources = 1 OR paymentSourceId IN (:sourceIds)) AND (:search = '' OR LOWER(COALESCE(counterpartyLabel, '')) LIKE '%' || LOWER(:search) || '%' OR LOWER(COALESCE(userNotes, '')) LIKE '%' || LOWER(:search) || '%')")
    fun filteredTotals(purpose: String, origin: String, direction: String, reviewOnly: Boolean, start: Long, end: Long, allCategories: Boolean, categories: List<String>, allSources: Boolean, sourceIds: List<String>, search: String): TransactionResultTotals
    @Query("SELECT * FROM transactions WHERE id = :id") fun get(id: String): TransactionEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE sourceFingerprint = :fingerprint)") fun hasFingerprint(fingerprint: ByteArray): Boolean
    @Insert fun insertBatch(batch: ImportBatchEntity)
    @Query("SELECT * FROM import_batches ORDER BY createdAt DESC LIMIT 1") fun latestImport(): ImportBatchEntity?
    @Query("SELECT * FROM transactions AS item WHERE importBatchId = :batchId AND isUserCorrected = 0 AND NOT EXISTS (SELECT 1 FROM corrections WHERE transactionId = item.id)")
    fun untouchedImport(batchId: String): List<TransactionEntity>
    @Query("DELETE FROM import_batches WHERE id = :batchId") fun deleteBatch(batchId: String)
    @Query("SELECT * FROM transactions WHERE effectiveTimestamp >= :start AND effectiveTimestamp < :end") fun between(start: Long, end: Long): List<TransactionEntity>
    @Query("DELETE FROM transactions WHERE id = :id") fun delete(id: String)
    @Query("SELECT * FROM corrections WHERE transactionId = :id") fun corrections(id: String): List<CorrectionEntity>
    @Query("SELECT * FROM transactions WHERE referenceHash = :referenceHash") fun byReference(referenceHash: ByteArray): List<TransactionEntity>
    @Query("SELECT * FROM transactions WHERE linkedOriginalId = :id") fun linkedTo(id: String): List<TransactionEntity>
    @Query("UPDATE transactions SET linkedOriginalId = NULL WHERE linkedOriginalId = :id") fun unlinkFrom(id: String)
    @Query("SELECT linkedOriginalId FROM transactions WHERE linkedOriginalId IS NOT NULL AND status = 'Reversed' AND reviewState != 'NeedsReview'") fun reversedOriginals(): List<String>
    @Insert(onConflict = OnConflictStrategy.ABORT) fun addSource(source: PaymentSourceEntity)
    @Update fun updateSource(source: PaymentSourceEntity)
    @Query("SELECT * FROM payment_sources WHERE active = 1 ORDER BY createdAt ASC") fun activeSources(): List<PaymentSourceEntity>
    @Query("SELECT * FROM payment_sources ORDER BY createdAt ASC") fun allSources(): List<PaymentSourceEntity>
    @Query("SELECT * FROM payment_sources WHERE id = :id") fun source(id: String): PaymentSourceEntity?
    @Query("SELECT COUNT(*) FROM transactions WHERE paymentSourceId = :id") fun sourceUsageCount(id: String): Int
    @Query("UPDATE payment_sources SET active = 0 WHERE id = :id") fun retireSource(id: String)
}

@Database(entities = [TransactionEntity::class, CorrectionEntity::class, ImportBatchEntity::class, PaymentSourceEntity::class, RepaymentEntity::class, CategoryRuleEntity::class, LedgerMetadataEntity::class, MatchDecisionEntity::class, BudgetEntity::class, RecurringReminderEntity::class], version = 7, exportSchema = true)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    private var connectionPassword: ByteArray? = null

    override fun close() {
        try { super.close() }
        finally { connectionPassword?.fill(0); connectionPassword = null }
    }

    companion object {
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS budgets (id TEXT NOT NULL, category TEXT NOT NULL, monthlyLimitMinor INTEGER NOT NULL, alertPercent INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_category ON budgets(category)")
                db.execSQL("CREATE TABLE IF NOT EXISTS recurring_reminders (id TEXT NOT NULL, title TEXT NOT NULL, amountMinor INTEGER, preferredDay INTEGER NOT NULL, category TEXT NOT NULL, active INTEGER NOT NULL, lastLinkedTransactionId TEXT, lastLinkedAt INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(lastLinkedTransactionId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_reminders_lastLinkedTransactionId ON recurring_reminders(lastLinkedTransactionId)")
            }
        }
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS match_decisions (id TEXT NOT NULL, pairKey TEXT NOT NULL, action TEXT NOT NULL, firstId TEXT NOT NULL, secondId TEXT NOT NULL, beforeFirst TEXT NOT NULL, beforeSecond TEXT NOT NULL, afterFirst TEXT NOT NULL, afterSecond TEXT NOT NULL, createdAt INTEGER NOT NULL, undoneAt INTEGER, PRIMARY KEY(id), FOREIGN KEY(firstId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(secondId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_decisions_firstId ON match_decisions(firstId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_match_decisions_secondId ON match_decisions(secondId)")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN repaymentExpected INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transactions ADD COLUMN categoryNeedsReview INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN duplicateOfId TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS ledger_metadata (`key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS category_rules (id TEXT NOT NULL, merchant TEXT NOT NULL, sourceId TEXT, category TEXT NOT NULL, enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS repayments (id TEXT NOT NULL, expenseId TEXT NOT NULL, incomingId TEXT, amountMinor INTEGER NOT NULL, receivedAt INTEGER, payer TEXT NOT NULL, method TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(expenseId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(incomingId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_repayments_expenseId ON repayments(expenseId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_repayments_incomingId ON repayments(incomingId)")
                db.execSQL("INSERT INTO repayments (id, expenseId, incomingId, amountMinor, receivedAt, payer, method, createdAt) SELECT 'legacy-' || id, id, NULL, repaidMinor, NULL, COALESCE(groupLabel, ''), 'Legacy', updatedAt FROM transactions WHERE repaidMinor > 0")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN category TEXT NOT NULL DEFAULT 'Other'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN ownership TEXT NOT NULL DEFAULT 'Personal'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN groupLabel TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN personalShareMinor INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN repaidMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transactions ADD COLUMN paymentSourceId TEXT")
                db.execSQL("UPDATE transactions SET ownership = 'SelfTransfer', personalShareMinor = amountMinor WHERE transactionType = 'SelfTransfer'")
                db.execSQL("CREATE TABLE IF NOT EXISTS payment_sources (id TEXT NOT NULL, nickname TEXT NOT NULL, kind TEXT NOT NULL, channel TEXT NOT NULL, bankName TEXT, last4 TEXT, active INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN importBatchId TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS import_batches (id TEXT NOT NULL, createdAt INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, inserted INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN referenceHash BLOB")
                db.execSQL("ALTER TABLE transactions ADD COLUMN linkedOriginalId TEXT")
            }
        }
        /** SQLCipher retains its password for future pooled connections until close. */
        fun open(context: Context, passphrase: ByteArray, name: String = "finance.db"): FinanceDatabase {
            var db: FinanceDatabase? = null
            val connectionPassword = passphrase.copyOf()
            try {
                System.loadLibrary("sqlcipher")
                Logger.setTarget(NoopTarget())
                db = Room.databaseBuilder(context, FinanceDatabase::class.java, name)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .openHelperFactory(SupportOpenHelperFactory(connectionPassword)).build()
                db.connectionPassword = connectionPassword
                db.openHelper.writableDatabase
                return db
            } catch (error: Exception) {
                try { db?.close() } finally { connectionPassword.fill(0) }
                throw error
            }
            finally { passphrase.fill(0) }
        }
    }
}

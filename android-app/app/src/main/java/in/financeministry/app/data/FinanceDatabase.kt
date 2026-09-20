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
)

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
    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(CASE WHEN direction = 'Debit' AND status = 'Successful' AND reviewState != 'NeedsReview' AND transactionType NOT IN ('SelfTransfer', 'CardRepayment') AND ownership != 'SelfTransfer' AND id NOT IN (SELECT linkedOriginalId FROM transactions WHERE linkedOriginalId IS NOT NULL AND status = 'Reversed' AND reviewState != 'NeedsReview') THEN COALESCE(amountMinor, 0) ELSE 0 END), 0) AS debit, COALESCE(SUM(CASE WHEN direction = 'Credit' AND status = 'Successful' AND reviewState != 'NeedsReview' AND transactionType NOT IN ('SelfTransfer', 'CardRepayment') AND ownership != 'SelfTransfer' AND id NOT IN (SELECT linkedOriginalId FROM transactions WHERE linkedOriginalId IS NOT NULL AND status = 'Reversed' AND reviewState != 'NeedsReview') THEN COALESCE(amountMinor, 0) ELSE 0 END), 0) AS credit FROM transactions WHERE effectiveTimestamp >= :start AND effectiveTimestamp < :end AND (:reviewOnly = 0 OR reviewState = 'NeedsReview') AND (:direction = 'All' OR direction = :direction) AND (:purpose = 'All' OR (:purpose = 'Personal' AND ownership = 'Personal') OR (:purpose = 'Family' AND ownership = 'Family') OR (:purpose = 'ForOthers' AND ownership = 'ForOther') OR (:purpose = 'Group' AND ownership = 'Group') OR (:purpose = 'SelfTransfer' AND (ownership = 'SelfTransfer' OR transactionType = 'SelfTransfer'))) AND (:origin = 'All' OR (:origin = 'Manual' AND sourceType = 'Manual') OR (:origin = 'Edited' AND isUserCorrected = 1)) AND (:allCategories = 1 OR category IN (:categories)) AND (:allSources = 1 OR paymentSourceId IN (:sourceIds)) AND (:search = '' OR LOWER(COALESCE(counterpartyLabel, '')) LIKE '%' || LOWER(:search) || '%' OR LOWER(COALESCE(userNotes, '')) LIKE '%' || LOWER(:search) || '%')")
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

@Database(entities = [TransactionEntity::class, CorrectionEntity::class, ImportBatchEntity::class, PaymentSourceEntity::class], version = 4, exportSchema = true)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    private var connectionPassword: ByteArray? = null

    override fun close() {
        try { super.close() }
        finally { connectionPassword?.fill(0); connectionPassword = null }
    }

    companion object {
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

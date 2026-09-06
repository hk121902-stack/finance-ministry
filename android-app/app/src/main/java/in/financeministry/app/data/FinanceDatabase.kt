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
)

@Entity(tableName = "corrections", foreignKeys = [ForeignKey(entity = TransactionEntity::class,
    parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("transactionId")])
data class CorrectionEntity(@PrimaryKey val id: String, val transactionId: String, val correctedAt: Long,
    val fieldName: String, val previousValue: String?, val newValue: String?)

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(row: TransactionEntity): Long
    @Update fun update(row: TransactionEntity)
    @Insert fun audit(rows: List<CorrectionEntity>)
    @Query("SELECT * FROM transactions ORDER BY effectiveTimestamp DESC LIMIT 500") fun latest(): List<TransactionEntity>
    @Query("SELECT * FROM transactions WHERE (:filter = 'All' OR (:filter = 'Review' AND reviewState = 'NeedsReview') OR (:filter = 'Manual' AND sourceType = 'Manual') OR (:filter = 'Edited' AND isUserCorrected = 1)) ORDER BY effectiveTimestamp DESC, id DESC LIMIT :limit OFFSET :offset")
    fun page(filter: String, limit: Int, offset: Int): List<TransactionEntity>
    @Query("SELECT * FROM transactions WHERE id = :id") fun get(id: String): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE effectiveTimestamp >= :start AND effectiveTimestamp < :end") fun between(start: Long, end: Long): List<TransactionEntity>
    @Query("DELETE FROM transactions WHERE id = :id") fun delete(id: String)
    @Query("SELECT * FROM corrections WHERE transactionId = :id") fun corrections(id: String): List<CorrectionEntity>
    @Query("SELECT * FROM transactions WHERE referenceHash = :referenceHash") fun byReference(referenceHash: ByteArray): List<TransactionEntity>
    @Query("SELECT * FROM transactions WHERE linkedOriginalId = :id") fun linkedTo(id: String): List<TransactionEntity>
    @Query("UPDATE transactions SET linkedOriginalId = NULL WHERE linkedOriginalId = :id") fun unlinkFrom(id: String)
    @Query("SELECT linkedOriginalId FROM transactions WHERE linkedOriginalId IS NOT NULL AND status = 'Reversed' AND reviewState != 'NeedsReview'") fun reversedOriginals(): List<String>
}

@Database(entities = [TransactionEntity::class, CorrectionEntity::class], version = 2, exportSchema = true)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    private var connectionPassword: ByteArray? = null

    override fun close() {
        try { super.close() }
        finally { connectionPassword?.fill(0); connectionPassword = null }
    }

    companion object {
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
                    .addMigrations(MIGRATION_1_2)
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

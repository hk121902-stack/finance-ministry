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
    @Query("SELECT * FROM transactions WHERE id = :id") fun get(id: String): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE effectiveTimestamp >= :start AND effectiveTimestamp < :end") fun between(start: Long, end: Long): List<TransactionEntity>
    @Query("DELETE FROM transactions WHERE id = :id") fun delete(id: String)
    @Query("SELECT * FROM corrections WHERE transactionId = :id") fun corrections(id: String): List<CorrectionEntity>
}

@Database(entities = [TransactionEntity::class, CorrectionEntity::class], version = 1, exportSchema = true)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao

    companion object {
        /** Call on IO; opening is forced before the password/factory buffer is zeroed. */
        fun open(context: Context, passphrase: ByteArray, name: String = "finance.db"): FinanceDatabase {
            var db: FinanceDatabase? = null
            try {
                System.loadLibrary("sqlcipher")
                Logger.setTarget(NoopTarget())
                db = Room.databaseBuilder(context, FinanceDatabase::class.java, name)
                    .openHelperFactory(SupportOpenHelperFactory(passphrase)).build()
                db.openHelper.writableDatabase
                return db
            } catch (error: Exception) { db?.close(); throw error }
            finally { passphrase.fill(0) }
        }
    }
}

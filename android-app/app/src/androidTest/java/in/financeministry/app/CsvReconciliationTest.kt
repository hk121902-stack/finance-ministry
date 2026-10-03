package `in`.financeministry.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CsvReconciliationTest {
    @Test fun export_identifies_excluded_rows_and_reports_dashboard_contributions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repo = TransactionRepository(context, "test_${UUID.randomUUID().toString().replace("-", "")}")
        fun row(id: String) = TransactionEntity(id, sourceType = "SMS", sourceTimestamp = 1788600000000,
            effectiveTimestamp = 1788600000000, amountMinor = 10000, direction = "Debit", status = "Successful",
            channel = "UPI", transactionType = "Unknown", reviewState = "AutoRecorded", createdAt = 1, updatedAt = 1)
        try {
            repo.withLedger { db -> listOf(row("personal"), row("failed").copy(status = "Failed"),
                row("review").copy(reviewState = "NeedsReview"), row("bill").copy(transactionType = "CardRepayment"),
                row("duplicate").copy(duplicateOfId = "personal"), row("transfer").copy(ownership = "SelfTransfer"),
                row("group").copy(ownership = "Group", repaymentExpected = true, personalShareMinor = 2500),
                row("income").copy(direction = "Credit")).forEach { db.transactions().insert(it) } }
            val csv = repo.csvReport(1788599999999, 1788600000001)
            val lines = csv.trim().split("\r\n")
            val headers = lines.first().split(",")
            listOf("Record ID", "Status", "Excluded from totals reason", "Money out contribution INR", "Money in contribution INR", "Your spending contribution INR")
                .forEach { assertTrue("Missing CSV column: $it", it in headers) }
            fun values(id: String) = lines.drop(1).map { it.removePrefix("\"").removeSuffix("\"").split("\",\"") }
                .single { it[headers.indexOf("Record ID")] == id }
            fun cell(id: String, column: String) = values(id)[headers.indexOf(column)]
            assertEquals("Failed", cell("failed", "Status"))
            assertEquals("NeedsReview", cell("review", "Review state"))
            listOf("failed", "review", "bill", "duplicate", "transfer").forEach {
                assertEquals("0.00", cell(it, "Money out contribution INR"))
                assertTrue(cell(it, "Excluded from totals reason").isNotBlank())
            }
            assertEquals("100.00", cell("group", "Money out contribution INR"))
            assertEquals("25.00", cell("group", "Your spending contribution INR"))
            assertEquals("100.00", cell("income", "Money in contribution INR"))
            assertEquals("", cell("personal", "Excluded from totals reason"))
        } finally { repo.eraseAll(); repo.close() }
    }
}

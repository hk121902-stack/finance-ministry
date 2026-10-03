package `in`.financeministry.app

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.core.model.IncomingSms
import `in`.financeministry.app.data.TransactionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate
import java.util.UUID

class UnsafeSmsLedgerTest {
    @Test fun unsafe_sms_does_not_inflate_ledger_totals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = TransactionRepository(context, "test_${UUID.randomUUID().toString().replace("-", "")}")
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECEIVE_SMS)
            check(repository.preferences.edit().putBoolean("sms_disclosure", true).commit())
            val messages = listOf(
                "INR 250.50 debited from your account via UPI",
                "Your account has not been debited INR 500.00.",
                "Hi, I was debited INR 125.00 yesterday. Can you check?",
                "If INR 500.00 is debited from your account, call customer care.",
            )
            messages.forEachIndexed { index, body -> repository.ingest(IncomingSms("SYNTHETIC", 1788600000000L + index, body)) {} }
            val snapshot = repository.snapshot(today = LocalDate.of(2026, 9, 1))
            assertEquals(BigInteger.valueOf(25050), snapshot.debit)
            assertEquals(BigInteger.ZERO, snapshot.credit)
            assertEquals(2, snapshot.rows.count { it.reviewState == "NeedsReview" })
            assertEquals(1, snapshot.rows.count { it.reviewState != "NeedsReview" })
        } finally { repository.eraseAll(); repository.close() }
    }
}

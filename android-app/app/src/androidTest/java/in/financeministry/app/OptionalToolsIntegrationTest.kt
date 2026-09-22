package `in`.financeministry.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.financeministry.app.core.model.Direction
import `in`.financeministry.app.core.model.TransactionType
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OptionalToolsIntegrationTest {
    @Test fun budgets_and_recurring_reminders_are_local_validated_and_backup_portable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sourceName = "tools-${System.nanoTime()}"
        val restoredName = "$sourceName-restored"
        val source = TransactionRepository(context, sourceName)
        val restored = TransactionRepository(context, restoredName)
        val password = "portable-tools-password".toCharArray()
        try {
            val budget = source.saveBudget("Food", "6000", alertPercent = 80)
            assertEquals(600_000, budget.monthlyLimitMinor)
            assertEquals(80, budget.alertPercent)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { source.saveBudget("Food", "0", 80) } }

            val reminder = source.saveRecurringReminder("Rent", "9000", 31, "Flat expenses")
            val paymentId = source.save(ManualInput("9000", Direction.Debit, System.currentTimeMillis(),
                TransactionType.Other, label = "Rent", category = "Flat expenses"))
            source.linkRecurringPayment(reminder.id, paymentId)
            assertEquals(paymentId, source.recurringReminders().single().lastLinkedTransactionId)
            assertNotNull(source.get(paymentId))

            val encrypted = source.createEncryptedBackup(password.copyOf())
            val preview = restored.previewBackup(encrypted, password.copyOf())
            assertEquals(1, preview.budgetCount)
            assertEquals(1, preview.recurringReminderCount)
            restored.restoreBackup(preview, protectCurrent = false)
            assertEquals(600_000, restored.budgets().single().monthlyLimitMinor)
            assertEquals(paymentId, restored.recurringReminders().single().lastLinkedTransactionId)
        } finally {
            source.eraseAll(); restored.eraseAll()
            context.deleteDatabase("$sourceName.db"); context.deleteDatabase("$restoredName.db")
            context.getSharedPreferences("${sourceName}_settings", 0).edit().clear().commit()
            context.getSharedPreferences("${restoredName}_settings", 0).edit().clear().commit()
        }
    }
}

package `in`.financeministry.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CustomCategoryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun ledger() = TransactionRepository(context, "category_${UUID.randomUUID()}")

    @Test fun a_new_category_persists_and_rejects_case_insensitive_duplicates() = runBlocking {
        val repository = ledger()
        try {
            assertEquals("School fees", repository.addCategory("  School fees  "))
            repository.close()
            assertTrue("School fees" in repository.categories())
            try { repository.addCategory("school FEES"); fail("Duplicate category was accepted") }
            catch (_: IllegalArgumentException) { }
            try { repository.addCategory("Food"); fail("Built-in category was duplicated") }
            catch (_: IllegalArgumentException) { }
            try { repository.addCategory("Bad+filter"); fail("Filter delimiter was accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun a_custom_category_works_in_transactions_filters_rules_and_optional_tools() = runBlocking {
        val repository = ledger()
        try {
            repository.addCategory("School fees")
            val id = repository.save(ManualInput("125", Direction.Debit, System.currentTimeMillis(),
                TransactionType.Other, label = "School payment", category = "School fees"))
            assertEquals(id, repository.snapshot(filter = "Category:School fees").rows.single().id)
            repository.saveCategoryRule("School payment", null, "School fees")
            assertEquals("School fees", repository.categoryRules().single().category)
            val budget = repository.saveBudget("School fees", "1000", null)
            assertEquals("School fees", budget.category)
            val reminder = repository.saveRecurringReminder("School fee", "125", 1, "School fees")
            assertEquals("School fees", reminder.category)
            val preview = repository.previewBatch(setOf(id), category = "School fees")
            assertEquals(1, preview.unchangedCount)
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun income_and_investment_are_labels_without_changing_money_flow() = runBlocking {
        val repository = ledger()
        try {
            repository.save(ManualInput("50", Direction.Credit, System.currentTimeMillis(),
                TransactionType.Other, label = "Salary", category = "Income"))
            repository.save(ManualInput("20", Direction.Debit, System.currentTimeMillis(),
                TransactionType.Other, label = "Fund", category = "Investment"))
            val result = repository.snapshot()
            assertEquals(5000L, result.credit.toLong())
            assertEquals(2000L, result.debit.toLong())
            assertEquals(1, repository.snapshot(filter = "Category:Income").rows.size)
            assertEquals(1, repository.snapshot(filter = "Category:Investment").rows.size)
        } finally { repository.eraseAll(); repository.close() }
    }

    @Test fun encrypted_backup_restores_unused_custom_categories() = runBlocking {
        val source = ledger()
        val target = ledger()
        val password = "test-only-password-long".toCharArray()
        try {
            source.addCategory("School fees")
            assertTrue(source.hasBackupData())
            val backup = source.createEncryptedBackup(password)
            target.restoreBackup(target.previewBackup(backup, password), protectCurrent = false)
            assertTrue("School fees" in target.categories())
        } finally {
            password.fill('\u0000')
            source.eraseAll(); source.close(); target.eraseAll(); target.close()
        }
    }

    @Test fun existing_format_one_backup_remains_restorable() = runBlocking {
        val source = ledger()
        val target = ledger()
        val password = "test-only-password-long".toCharArray()
        try {
            source.save(ManualInput("25", Direction.Credit, System.currentTimeMillis(),
                TransactionType.Other, label = "Prior receipt", category = "Other"))
            val plain = BackupCipher.decrypt(source.createEncryptedBackup(password), password)
            val previous = JSONObject(plain.toString(Charsets.UTF_8))
            previous.put("format", 1)
            previous.getJSONObject("data").remove("customCategories")
            plain.fill(0)
            val legacy = BackupCipher.encrypt(previous.toString().toByteArray(), password)
            target.restoreBackup(target.previewBackup(legacy, password), protectCurrent = false)
            assertEquals("Prior receipt", target.snapshot().rows.single().counterpartyLabel)
            assertEquals(transactionCategories, target.categories())
        } finally {
            password.fill('\u0000')
            source.eraseAll(); source.close(); target.eraseAll(); target.close()
        }
    }
}

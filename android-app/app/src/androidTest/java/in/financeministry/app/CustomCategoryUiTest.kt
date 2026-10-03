package `in`.financeministry.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import `in`.financeministry.app.data.TransactionRepository
import `in`.financeministry.app.data.categories
import `in`.financeministry.app.feature.TransactionForm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CustomCategoryUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun manual_entry_can_create_and_use_a_category_without_losing_the_draft() {
        val repository = TransactionRepository(ApplicationProvider.getApplicationContext<Application>(), "category_ui_${UUID.randomUUID()}")
        try {
            rule.setContent { MaterialTheme { TransactionForm(repository, null, onDone = {}) } }
            rule.onNodeWithText("Amount (INR)").performTextInput("125.00")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.waitForIdle()
            rule.onNodeWithText("Add category").performScrollTo().assertIsDisplayed().performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("New category name").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("New category name").performTextInput("School fees")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.waitForIdle()
            rule.onNodeWithText("Save category").assertIsDisplayed().performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Category: School fees").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Save transaction").assertIsDisplayed().performClick()
            rule.waitUntil(15_000) { runBlocking { repository.snapshot().rows.size == 1 } }
            val saved = runBlocking { repository.snapshot().rows.single() }
            assertEquals(12500L, saved.amountMinor)
            assertEquals("School fees", saved.category)
            assertTrue(runBlocking { "School fees" in repository.categories() })
        } finally { runBlocking { repository.eraseAll(); repository.close() } }
    }
}

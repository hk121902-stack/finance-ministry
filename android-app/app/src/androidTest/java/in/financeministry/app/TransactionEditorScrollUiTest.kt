package `in`.financeministry.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import `in`.financeministry.app.data.*
import `in`.financeministry.app.feature.APP_TOUR_VERSION_KEY
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class TransactionEditorScrollUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun withEditor(check: (TransactionRepository, TransactionEntity) -> Unit) {
        val repo = TransactionRepository(ApplicationProvider.getApplicationContext<Application>(), "scroll_ui_${UUID.randomUUID()}")
        val onboarding = repo.preferences.getBoolean("onboarding_complete", false)
        val tourVersion = repo.preferences.getInt(APP_TOUR_VERSION_KEY, 0)
        val now = System.currentTimeMillis()
        val fixture = TransactionEntity("scroll-fixture", sourceType = "Manual", sourceTimestamp = now,
            effectiveTimestamp = now, amountMinor = 1818, direction = "Debit", status = "Successful",
            channel = "CashManual", transactionType = "Other", counterpartyLabel = "Scroll fixture",
            category = "Food", reviewState = "Confirmed", createdAt = now, updatedAt = now)
        try {
            repo.preferences.edit().putBoolean("onboarding_complete", true)
                .putInt(APP_TOUR_VERSION_KEY, BuildConfig.VERSION_CODE).commit()
            runBlocking { repo.withLedger { it.transactions().insert(fixture) } }
            rule.setContent { MaterialTheme { LedgerApp(repo, null, consumeRequest = {}) } }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("₹18.18").fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Scroll fixture"))
            rule.onNodeWithText("Scroll fixture").performClick()
            rule.onNodeWithText("Edit / confirm").performScrollTo().performClick()
            rule.waitUntil(15_000) {
                rule.onAllNodes(hasText("Save transaction") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            check(repo, fixture)
        } finally {
            runBlocking { repo.eraseAll(); repo.close() }
            repo.preferences.edit().putBoolean("onboarding_complete", onboarding)
                .putInt(APP_TOUR_VERSION_KEY, tourVersion).commit()
        }
    }

    private fun pullDownAtTop() {
        rule.onNodeWithText("Edit / confirm transaction").performScrollTo()
        val bounds = rule.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode().boundsInWindow
        val x = (bounds.left + bounds.width * .7f).toInt()
        val start = (bounds.top + bounds.height * .25f).toInt()
        val end = (bounds.top + bounds.height * .85f).toInt()
        // Use Android's real-time gesture injection: the sheet lives in a separate window.
        shell("input swipe $x $start $x $end 500")
        rule.waitForIdle()
    }

    private fun shell(command: String): String {
        val result = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    // Re-enabling sheet dragging lets the form's top-edge scroll dismiss the editor.
    @Test fun downward_scroll_at_top_keeps_untouched_dashboard_editor_open() = withEditor { repo, fixture ->
        pullDownAtTop()
        rule.onNodeWithText("Edit / confirm transaction").assertIsDisplayed()
        rule.onNodeWithText("18.18").assertExists()
        assertEquals(fixture, runBlocking { repo.get(fixture.id) })
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Open overview").assertIsDisplayed()
    }

    @Test fun form_still_scrolls_to_lower_fields_and_back() = withEditor { _, _ ->
        rule.onNodeWithText("Advanced details").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Edit / confirm transaction").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save transaction").assertIsDisplayed()
    }

    @Test fun downward_scroll_preserves_draft_and_cancel_still_requires_confirmation() = withEditor { repo, fixture ->
        rule.onNodeWithText("Amount (INR)").performTextReplacement("19.19")
        Espresso.closeSoftKeyboard()
        pullDownAtTop()
        rule.onNodeWithText("19.19").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Keep editing").performClick()
        rule.onNodeWithText("19.19").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Discard changes").performClick()
        rule.onNodeWithContentDescription("Open overview").assertIsDisplayed()
        assertEquals(fixture, runBlocking { repo.get(fixture.id) })
    }

    @Test fun android_back_closes_an_untouched_editor() = withEditor { repo, fixture ->
        Espresso.pressBack()
        rule.onNodeWithContentDescription("Open overview").assertIsDisplayed()
        assertEquals(fixture, runBlocking { repo.get(fixture.id) })
    }

    @Test fun android_back_does_not_discard_edits_without_confirmation() = withEditor { repo, fixture ->
        rule.onNodeWithText("Family").performScrollTo().performClick()
        Espresso.pressBack()
        rule.onNodeWithText("Keep editing").performClick()
        rule.onNodeWithText("Family").assertIsSelected()
        assertEquals(fixture, runBlocking { repo.get(fixture.id) })
        rule.onNodeWithText("Save transaction").performClick()
        rule.waitUntil(15_000) { runBlocking { repo.get(fixture.id)?.ownership == "Family" } }
        assertEquals(1818L, runBlocking { repo.get(fixture.id)!!.amountMinor })
        assertEquals("Food", runBlocking { repo.get(fixture.id)!!.category })
    }

    @Test fun untouched_editor_stays_open_with_soft_keyboard_visible() = withEditor { repo, fixture ->
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val keyboardSetting = shell("settings get secure show_ime_with_hard_keyboard").trim()
        val originalFlags = automation.serviceInfo.flags
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            rule.onNodeWithText("Amount (INR)").performClick()
            rule.waitUntil(5_000) { automation.windows.any {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
            pullDownAtTop()
            rule.onNodeWithText("Edit / confirm transaction").assertExists()
            rule.onNodeWithText("18.18").assertExists()
            rule.onNodeWithText("Save transaction").assertIsDisplayed()
            assertEquals(fixture, runBlocking { repo.get(fixture.id) })
            Espresso.closeSoftKeyboard()
            rule.onNodeWithText("Cancel").performClick()
        } finally {
            if (keyboardSetting == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard ${keyboardSetting.toInt()}")
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }
}

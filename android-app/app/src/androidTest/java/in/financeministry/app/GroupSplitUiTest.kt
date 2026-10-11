package `in`.financeministry.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import `in`.financeministry.app.feature.RepaymentHistoryPanel
import `in`.financeministry.app.feature.RepaymentsScreen
import `in`.financeministry.app.feature.TransactionForm
import `in`.financeministry.app.feature.GroupSplitEditor
import `in`.financeministry.app.feature.RepaymentEditor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.time.LocalDate
import org.junit.Assert.*
import java.io.File

class GroupSplitUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun repo() = TransactionRepository(ApplicationProvider.getApplicationContext<Application>(), "group_ui_${UUID.randomUUID()}")
    private fun expense(repo: TransactionRepository) = runBlocking { repo.save(ManualInput("900", Direction.Debit, System.currentTimeMillis(),
        TransactionType.Other, label = "Dinner", category = "Food", ownership = SpendingOwnership.Group, groupLabel = "Friends", personalShare = "300")) }
    private fun capture(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = instrumentation.targetContext.resources.configuration
        val theme = if (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        val file = File(instrumentation.targetContext.getExternalFilesDir("group-ux"), "$theme-${config.screenWidthDp}dp-$name")
        file.outputStream().use { check(instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun addPerson(name: String) {
        rule.waitUntil(15000) { rule.onAllNodes(hasText("New local name") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("New local name").performScrollTo().performTextInput(name)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Add person").performScrollTo().performClick()
        rule.waitUntil(15000) { rule.onAllNodesWithText("Remove $name from split").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun group_expense_has_a_discoverable_people_split_action() {
        val repo = TransactionRepository(ApplicationProvider.getApplicationContext<Application>(), "group_ui_${UUID.randomUUID()}")
        try {
            val id = runBlocking { repo.save(ManualInput("900", Direction.Debit, System.currentTimeMillis(),
                TransactionType.Other, label = "Dinner", category = "Food", ownership = SpendingOwnership.Group,
                groupLabel = "Friends", personalShare = "300")) }
            val expense = runBlocking { repo.get(id)!! }
            rule.setContent { FinanceMinistryTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(16.dp)) { RepaymentHistoryPanel(repo, expense, true) }
            } } }
            rule.onNodeWithText("Split with people").assertIsDisplayed()
        } finally { runBlocking { repo.eraseAll(); repo.close() } }
    }

    @Test fun equal_then_custom_split_validates_and_saves_named_shares() {
        val r = repo()
        try {
            val id = expense(r)
            rule.setContent { FinanceMinistryTheme { Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(16.dp)) { RepaymentHistoryPanel(r, runBlocking { r.get(id)!! }, true) }
            } } }
            rule.waitUntil(15000) { rule.onAllNodes(hasText("Split with people") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Split with people").performClick()
            addPerson("Alice"); addPerson("Bob")
            rule.onAllNodesWithText("₹300.00").assertCountEquals(3)
            capture("equal-split.png")
            rule.onNodeWithText("Remove Bob from split").performScrollTo().performClick()
            rule.onAllNodesWithText("₹450.00").assertCountEquals(2)
            rule.onNodeWithText("Choose an existing person").performScrollTo().performClick()
            rule.onAllNodes(isToggleable()).assertCountEquals(2)
            rule.onAllNodes(isToggleable())[1].performClick()
            rule.onNodeWithText("Done").performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithText("₹300.00").fetchSemanticsNodes().size == 3 }
            rule.onNodeWithText("Custom").performScrollTo().performClick()
            rule.onNodeWithText("Share for You (INR)").performScrollTo().performTextReplacement("200")
            rule.onNodeWithText("Share for Alice (INR)").performScrollTo().performTextReplacement("400")
            rule.onNodeWithText("Share for Bob (INR)").performScrollTo().performTextReplacement("299")
            rule.onNodeWithText("Apply split").assertIsNotEnabled()
            rule.onNodeWithText("Remaining to assign: ₹1.00").assertExists()
            rule.onNodeWithText("Share for Bob (INR)").performTextReplacement("300")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            capture("custom-split.png")
            rule.onNodeWithText("Apply split").assertIsEnabled().performClick()
            rule.waitUntil(15000) { runBlocking { r.groupSharesFor(id).size == 2 } }
            assertEquals(20000L, runBlocking { r.get(id)!!.personalShareMinor })
            assertEquals(listOf(30000L, 40000L), runBlocking { r.groupSharesFor(id).map { it.amountMinor }.sorted() })
        } finally { runBlocking { r.eraseAll(); r.close() } }
    }

    @Test fun person_drilldown_records_partial_cash_and_keeps_other_balance_unchanged() {
        val r = repo()
        try {
            val id = expense(r)
            val a = runBlocking { r.addLocalMember("Alice") }; val b = runBlocking { r.addLocalMember("Bob") }
            runBlocking { r.saveGroupSplit(id, 30000, listOf(MemberShareInput(a.id, 30000), MemberShareInput(b.id, 30000)), r.get(id)!!.updatedAt) }
            rule.setContent { FinanceMinistryTheme { RepaymentsScreen(r, LocalDate.now(), {}, {}) } }
            rule.waitUntil(15000) { rule.onAllNodesWithText("Still owed · all dates").fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Alice"))
            capture("people-balances.png")
            rule.onNodeWithText("Alice").performClick()
            rule.onNodeWithText("Record money received").performScrollTo().performClick()
            rule.onNodeWithText("Received cash").performClick()
            rule.onNodeWithText("Repayment amount (INR)").performTextReplacement("100")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            capture("individual-settlement.png")
            rule.onNodeWithText("Save repayment").assertIsDisplayed().performClick()
            rule.waitUntil(15000) { runBlocking { r.repaymentsFor(id).size == 1 } }
            val repayments = runBlocking { r.repaymentsFor(id) }
            assertEquals(a.id, repayments.single().memberId)
            assertEquals(20000L, GroupSplitAccounting.owed(runBlocking { r.groupSharesFor(id).single { it.memberId == a.id } }, repayments))
            assertEquals(30000L, GroupSplitAccounting.owed(runBlocking { r.groupSharesFor(id).single { it.memberId == b.id } }, repayments))
            assertEquals(30000L, runBlocking { r.snapshot().personalSpend.toLong() })
        } finally { runBlocking { r.eraseAll(); r.close() } }
    }

    @Test fun manual_expense_can_save_the_split_atomically_without_a_second_edit() {
        val r = repo()
        var done = false
        try {
            rule.setContent { FinanceMinistryTheme { Surface(Modifier.fillMaxSize()) { TransactionForm(r, null, { done = true }) } } }
            rule.onNodeWithText("Amount (INR)").performTextInput("900")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithText("Group").performScrollTo().performClick()
            rule.onNodeWithText("Group name").performScrollTo().performTextInput("Friends")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithText("Split with people").performScrollTo().performClick()
            addPerson("Alice"); addPerson("Bob")
            rule.onNodeWithText("Apply split").performClick()
            rule.onNodeWithText("Save transaction").performClick()
            rule.waitUntil(15000) { done }
            val row = runBlocking { r.snapshot().rows.single() }
            assertEquals(30000L, row.personalShareMinor); assertEquals(2, runBlocking { r.groupSharesFor(row.id).size })
            assertEquals(60000L, RepaymentAccounting.owed(row))
        } finally { runBlocking { r.eraseAll(); r.close() } }
    }

    @Test fun custom_draft_survives_restoration_and_discard_does_not_change_the_expense() {
        val r = repo(); val showing = mutableStateOf(true)
        try {
            val id = expense(r)
            val a = runBlocking { r.addLocalMember("Alice") }; val b = runBlocking { r.addLocalMember("Bob") }
            val inputs = listOf(MemberShareInput(a.id, 30000), MemberShareInput(b.id, 30000))
            runBlocking { r.saveGroupSplit(id, 30000, inputs, r.get(id)!!.updatedAt) }
            val restoration = StateRestorationTester(rule)
            restoration.setContent { FinanceMinistryTheme {
                if (showing.value) GroupSplitEditor(r, 90000, 30000, inputs, { _, _ -> fail("Discard must not save") }, { showing.value = false })
            } }
            rule.waitUntil(15000) { rule.onAllNodes(hasText("Apply split") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Share for You (INR)").performScrollTo().performTextReplacement("250")
            rule.onNodeWithText("Share for Alice (INR)").performScrollTo().performTextReplacement("350")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            restoration.emulateSavedInstanceStateRestore()
            rule.onNodeWithText("250").assertExists(); rule.onNodeWithText("350").assertExists()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("Keep editing").performClick()
            rule.onNodeWithText("250").assertExists()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("Discard changes").performClick()
            assertFalse(showing.value)
            assertEquals(30000L, runBlocking { r.get(id)!!.personalShareMinor })
        } finally { runBlocking { r.eraseAll(); r.close() } }
    }

    @Test fun existing_person_can_be_reused_and_renamed_without_reallocating_identity() {
        val r = repo(); val showing = mutableStateOf(true)
        try {
            val id = expense(r); val a = runBlocking { r.addLocalMember("Alice") }
            rule.setContent { FinanceMinistryTheme {
                if (showing.value) GroupSplitEditor(r, 90000, 30000, emptyList(),
                    { own, shares -> r.saveGroupSplit(id, own, shares, r.get(id)!!.updatedAt) }, { showing.value = false })
            } }
            rule.waitUntil(15000) { rule.onAllNodes(hasText("Choose an existing person") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Choose an existing person").performScrollTo().performClick()
            rule.onNodeWithText("Rename").performClick()
            rule.onNodeWithText("Local name").performTextReplacement("Alice home")
            rule.onNodeWithText("Save name").performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Alice home").fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(isToggleable()).performClick()
            rule.onNodeWithText("Done").performClick()
            rule.onAllNodesWithText("₹450.00").assertCountEquals(2)
            try {
                rule.waitUntil(15000) { rule.onAllNodes(hasText("Apply split") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) { capture("rename-transition-failure.png"); throw failure }
            rule.onNodeWithText("Apply split").assertIsDisplayed()
            capture("reused-person.png")
            rule.onNodeWithText("Apply split").performClick()
            rule.waitUntil(15000) { !showing.value }
            assertEquals(a.id, runBlocking { r.groupSharesFor(id).single().memberId })
            assertEquals("Alice home", runBlocking { r.localMembers().single().name })
        } finally { runBlocking { r.eraseAll(); r.close() } }
    }

    @Test fun settled_person_is_visible_when_another_person_in_the_same_expense_still_owes() {
        val r = repo()
        try {
            val id = expense(r); val a = runBlocking { r.addLocalMember("Alice") }; val b = runBlocking { r.addLocalMember("Bob") }
            runBlocking {
                r.saveGroupSplit(id, 30000, listOf(MemberShareInput(a.id, 30000), MemberShareInput(b.id, 30000)), r.get(id)!!.updatedAt)
                r.recordRepayment(id, "300", System.currentTimeMillis(), "Alice", memberId = a.id)
            }
            rule.setContent { FinanceMinistryTheme { RepaymentsScreen(r, LocalDate.now(), {}, {}) } }
            rule.waitUntil(15000) { rule.onAllNodesWithText("Still owed · all dates").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Settled").performScrollTo().performClick()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Alice"))
            rule.onNodeWithText("Alice").assertIsDisplayed()
            rule.onNodeWithText("No settled expenses in this view.").assertDoesNotExist()
            rule.onNodeWithText("Bob").assertDoesNotExist()
            capture("settled-person.png")
        } finally { runBlocking { r.eraseAll(); r.close() } }
    }

    @Test fun prefilled_individual_settlement_can_cancel_untouched_and_save_stays_above_keyboard() {
        val r = repo(); val showing = mutableStateOf(true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
        val oldKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            val id = expense(r); val a = runBlocking { r.addLocalMember("Alice") }
            runBlocking { r.saveGroupSplit(id, 30000, listOf(MemberShareInput(a.id, 60000)), r.get(id)!!.updatedAt) }
            val row = runBlocking { r.get(id)!! }
            rule.setContent { FinanceMinistryTheme {
                if (showing.value) RepaymentEditor(r, row, null, { showing.value = false }, a.id)
            } }
            rule.waitUntil(15000) { rule.onAllNodesWithText("600.00").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithText("Discard repayment changes?").assertDoesNotExist()
            rule.runOnIdle { showing.value = true }
            rule.waitUntil(15000) { rule.onAllNodesWithText("600.00").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Received cash").performScrollTo().performClick()
            rule.onNodeWithText("Repayment amount (INR)").performScrollTo().performClick().performTextReplacement("100")
            val automation = instrumentation.uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            rule.waitUntil(10000) { automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            val keyboard = android.graphics.Rect()
            automation.windows.first { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }.getBoundsInScreen(keyboard)
            val save = rule.onNodeWithText("Save repayment").fetchSemanticsNode().boundsInWindow
            assertTrue("Save overlaps keyboard", save.bottom <= keyboard.top)
            rule.onNodeWithText("Save repayment").assertIsDisplayed()
            capture("individual-keyboard.png")
        } finally {
            shell(if (oldKeyboard == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $oldKeyboard")
            runBlocking { r.eraseAll(); r.close() }
        }
    }
}

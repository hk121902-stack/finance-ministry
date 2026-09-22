package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MatchingUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        rule.waitForIdle()
        val screenshot = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        // Shared Downloads survives Gradle uninstalling the test app after the run.
        val resolver = rule.activity.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/finance-ministry-validation")
        }
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(resolver.openOutputStream(uri)).use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Test fun review_compares_duplicate_records_confirms_and_undoes_from_history() {
        val r = (rule.activity.application as FinanceMinistryApp).container.repository
        val source = runBlocking { r.addPaymentSource("Match UI ${System.nanoTime().toString().takeLast(5)}", "Bank account", Channel.UPI) }
        val input = ManualInput("450", Direction.Debit, System.currentTimeMillis(), TransactionType.Other,
            channel = Channel.UPI, label = "UI cafe", category = "Food", notes = "Shared meal", paymentSourceId = source.id)
        val a = runBlocking { r.save(input) }; val b = runBlocking { r.save(input) }
        try {
            rule.onNodeWithContentDescription("Open review tab").performClick()
            rule.onNodeWithText("Possible matches").performClick()
            rule.onNodeWithTag("compare-${TransactionMatching.pairKey(a, b)}").performScrollTo().performClick()
            rule.onNodeWithText("Same registered payment source").assertExists()
            rule.onNodeWithText("Change retained details").assertExists()
            rule.onNodeWithText("Count first record").assertDoesNotExist()
            capture("match-comparison.png")
            rule.onNodeWithText("Change retained details").performScrollTo().performClick()
            rule.onNodeWithText("Count first record").performScrollTo().performClick()
            rule.onNodeWithText("Confirm same payment").performScrollTo().performClick()
            rule.waitUntil(15000) { runBlocking { r.get(a)!!.duplicateOfId != null || r.get(b)!!.duplicateOfId != null } }
            assertNotNull(runBlocking { r.get(a) }); assertNotNull(runBlocking { r.get(b) })
            rule.onNodeWithText("Decision history").performClick()
            capture("match-decision-history.png")
            val decision = runBlocking { r.matchDecisions().single { setOf(it.firstId, it.secondId) == setOf(a, b) } }
            rule.onNodeWithTag("undo-${decision.id}").performScrollTo().performClick()
            rule.onNodeWithText("Confirm undo").performClick()
            rule.waitUntil(15000) { runBlocking { r.get(a)!!.duplicateOfId == null && r.get(b)!!.duplicateOfId == null } }
            rule.onNodeWithText("Decision undone. Both records kept.").assertExists()
        } finally {
            runBlocking {
                r.matchDecisions().filter { it.undoneAt == null && it.firstId in setOf(a, b) }.forEach { r.undoMatch(it.id) }
                r.delete(a); r.delete(b); r.deletePaymentSource(source.id)
            }
        }
    }

    @Test fun transfer_confirmation_preserves_both_legs_and_is_reversible_in_review() {
        val r = (rule.activity.application as FinanceMinistryApp).container.repository
        val unique = System.nanoTime().toString().takeLast(5)
        val from = runBlocking { r.addPaymentSource("From UI $unique", "Bank account", Channel.UPI) }
        val to = runBlocking { r.addPaymentSource("To UI $unique", "Bank account", Channel.UPI) }
        val input = ManualInput("2000", Direction.Debit, System.currentTimeMillis(), TransactionType.Other,
            channel = Channel.UPI, label = "UI transfer", paymentSourceId = from.id)
        val a = runBlocking { r.save(input) }; val b = runBlocking { r.save(input.copy(direction = Direction.Credit, paymentSourceId = to.id)) }
        try {
            rule.onNodeWithContentDescription("Open review tab").performClick()
            rule.onNodeWithText("Possible matches").performClick()
            rule.onNodeWithTag("compare-${TransactionMatching.pairKey(a, b)}").performScrollTo().performClick()
            rule.onNodeWithText("Did this money move between your own accounts?").assertExists()
            capture("match-transfer.png")
            rule.onNodeWithText("Yes, self transfer").performScrollTo().performClick()
            rule.waitUntil(15000) { runBlocking { r.get(a)!!.ownership == "SelfTransfer" && r.get(b)!!.ownership == "SelfTransfer" } }
            rule.waitUntil(15000) {
                rule.onAllNodesWithText("Undo last decision").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Undo last decision").performScrollTo().performClick()
            rule.onNodeWithText("Confirm undo").performClick()
            rule.waitUntil(15000) { runBlocking { r.get(a)!!.ownership == "Personal" && r.get(b)!!.ownership == "Personal" } }
        } finally {
            runBlocking {
                r.matchDecisions().filter { it.undoneAt == null && it.firstId in setOf(a, b) }.forEach { r.undoMatch(it.id) }
                r.delete(a); r.delete(b); r.deletePaymentSource(from.id); r.deletePaymentSource(to.id)
            }
        }
    }

    @Test fun repayment_conflict_is_explained_and_review_notification_waits_for_comparison_to_close() {
        val r = (rule.activity.application as FinanceMinistryApp).container.repository
        val source = runBlocking { r.addPaymentSource("Conflict UI ${System.nanoTime().toString().takeLast(5)}", "Bank account", Channel.UPI) }
        val input = ManualInput("300", Direction.Debit, System.currentTimeMillis(), TransactionType.Other,
            channel = Channel.UPI, label = "UI friend", paymentSourceId = source.id,
            ownership = SpendingOwnership.ForOther, groupLabel = "Friend")
        val a = runBlocking { r.save(input) }; val b = runBlocking { r.save(input) }
        val repayment = runBlocking { r.recordRepayment(a, "50", input.timestamp, "Friend") }
        try {
            rule.onNodeWithContentDescription("Open review tab").performClick()
            rule.onNodeWithText("Possible matches").performClick()
            rule.onNodeWithTag("compare-${TransactionMatching.pairKey(a, b)}").performScrollTo().performClick()
            rule.onNodeWithText("Confirm same payment").performScrollTo().performClick()
            rule.waitUntil(15000) { rule.onAllNodes(hasText("This pair has repayment links.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertNull(runBlocking { r.get(a)!!.duplicateOfId }); assertNull(runBlocking { r.get(b)!!.duplicateOfId })
            capture("match-repayment-conflict.png")
            rule.runOnUiThread { rule.activity.requestReview() }
            rule.onNodeWithText("Compare possible duplicate").assertExists()
            rule.onNodeWithText("Cancel").performScrollTo().performClick()
            rule.onNodeWithText("Review queue").assertExists()
        } finally {
            runBlocking { r.removeRepayment(repayment.id); r.delete(requireNotNull(repayment.incomingId)); r.delete(a); r.delete(b); r.deletePaymentSource(source.id) }
        }
    }
}

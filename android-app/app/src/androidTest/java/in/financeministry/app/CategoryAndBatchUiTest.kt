package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CategoryAndBatchUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun batch_preview_applies_only_selection_and_can_be_undone() {
        val r = (rule.activity.application as FinanceMinistryApp).container.repository
        val ids = runBlocking { listOf("Batch first", "Batch second", "Batch untouched").map { label ->
            r.save(ManualInput("25", Direction.Debit, System.currentTimeMillis(), TransactionType.Other, label = label)) } }
        try {
            rule.onNodeWithContentDescription("Open transactions").performClick()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Select transactions"))
            rule.onNodeWithText("Select transactions").performClick()
            rule.onNodeWithContentDescription("Select Batch first").performClick()
            rule.onNodeWithContentDescription("Select Batch second").performClick()
            rule.onNodeWithText("Category").performClick()
            rule.onNodeWithText("Batch category: Other").performClick()
            rule.onNodeWithText("Food").performClick()
            rule.onNodeWithText("Preview changes").performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Apply update").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Other", runBlocking { r.get(ids.first())!!.category })
            rule.onNodeWithText("Apply update").performClick()
            rule.waitUntil(15000) { runBlocking { r.get(ids.first())!!.category == "Food" } }
            assertEquals("Food", runBlocking { r.get(ids[1])!!.category })
            assertEquals("Other", runBlocking { r.get(ids[2])!!.category })
            rule.onNodeWithText("Undo").performClick()
            rule.waitUntil(15000) { runBlocking { r.get(ids.first())!!.category == "Other" } }
            assertEquals("Other", runBlocking { r.get(ids[1])!!.category })
        } finally { runBlocking { ids.forEach { r.delete(it) } } }
    }

    @Test fun category_rule_can_be_created_disabled_and_deleted_in_settings() {
        val r = (rule.activity.application as FinanceMinistryApp).container.repository
        try {
            rule.onNodeWithText("Settings").performClick()
            rule.onNodeWithText("Category rules").performScrollTo().performClick()
            rule.onNodeWithText("New rule").performClick()
            rule.onNodeWithText("Exact merchant").performTextInput("Rule test merchant")
            rule.onNodeWithText("Rule category: Other").performClick()
            rule.onNodeWithText("Food").performClick()
            rule.onNodeWithText("Save rule").performClick()
            rule.waitUntil(15000) { runBlocking { r.categoryRules().any { it.merchant == "Rule test merchant" } } }
            rule.onNodeWithContentDescription("Enable rule Rule test merchant").performScrollTo().performClick()
            rule.waitUntil(15000) { runBlocking { r.categoryRules().single { it.merchant == "Rule test merchant" }.enabled.not() } }
            rule.onNodeWithContentDescription("Delete rule Rule test merchant").performScrollTo().performClick()
            rule.onNodeWithText("Delete rule").performClick()
            rule.waitUntil(15000) { runBlocking { r.categoryRules().none { it.merchant == "Rule test merchant" } } }
        } finally { runBlocking { r.categoryRules().filter { it.merchant == "Rule test merchant" }.forEach { r.deleteCategoryRule(it.id) } } }
    }
}

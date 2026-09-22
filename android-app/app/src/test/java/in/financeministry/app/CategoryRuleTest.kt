package `in`.financeministry.app

import `in`.financeministry.app.data.*
import org.junit.Assert.*
import org.junit.Test

class CategoryRuleTest {
    private fun row() = TransactionEntity("t", sourceType = "SMS", sourceTimestamp = 1, effectiveTimestamp = 1,
        amountMinor = 10000, direction = "Debit", status = "Successful", channel = "UPI", transactionType = "Other",
        counterpartyLabel = " FRESH BASKET ", reviewState = "NeedsReview", createdAt = 1, updatedAt = 1, paymentSourceId = "hdfc")
    @Test fun exact_merchant_rule_changes_only_category_without_confirming_payment() {
        val rule = CategoryRuleEntity("r", "Fresh Basket", "hdfc", "Food", true, 1)
        val result = CategoryRules.apply(row(), listOf(rule))
        assertEquals("Food", result.category)
        assertEquals("NeedsReview", result.reviewState)
        assertEquals("Personal", result.ownership)
        assertEquals("Other", CategoryRules.apply(row().copy(counterpartyLabel = "Fresh Basket Other"), listOf(rule)).category)
        assertEquals("Other", CategoryRules.apply(row().copy(paymentSourceId = "other"), listOf(rule)).category)
    }
    @Test fun conflicting_rules_and_manual_corrections_are_never_overwritten() {
        val rules = listOf(CategoryRuleEntity("a", "Fresh Basket", null, "Food", true, 1),
            CategoryRuleEntity("b", "Fresh Basket", "hdfc", "Travel", true, 1))
        assertEquals("Other", CategoryRules.apply(row(), rules).category)
        assertTrue(CategoryRules.apply(row(), rules).categoryNeedsReview)
        assertTrue(CategoryRules.conflicts(row(), rules))
        assertEquals("Other", CategoryRules.apply(row().copy(isUserCorrected = true), rules.take(1)).category)
        assertEquals("Other", CategoryRules.apply(row(), listOf(rules.first().copy(enabled = false))).category)
    }
}

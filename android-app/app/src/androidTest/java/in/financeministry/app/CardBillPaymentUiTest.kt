package `in`.financeministry.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import `in`.financeministry.app.data.*
import `in`.financeministry.app.feature.TransactionForm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CardBillPaymentUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun repository() = TransactionRepository(ApplicationProvider.getApplicationContext<Application>(), "bill_ui_${UUID.randomUUID()}").also {
        it.preferences.edit().putBoolean("onboarding_complete", true).commit()
    }

    @Test fun manual_bill_payment_is_saved_and_excluded() {
        val repo = repository()
        try {
            rule.setContent { MaterialTheme { TransactionForm(repo, null, onDone = {}) } }
            rule.onNodeWithText("Amount (INR)").performTextInput("1000")
            rule.onNodeWithText("Card bill payment").performClick()
            rule.onNodeWithText("Bank payment").assertIsSelected()
            rule.onNodeWithText("Save transaction").performClick()
            rule.waitUntil(15_000) { runBlocking { repo.snapshot().rows.size == 1 } }
            assertEquals("CardRepayment", runBlocking { repo.snapshot().rows.single().transactionType })
            assertEquals("0", runBlocking { repo.snapshot().debit.toString() })
        } finally { runBlocking { repo.eraseAll(); repo.close() } }
    }

    @Test fun existing_card_credit_can_be_corrected_from_details() {
        val repo = repository()
        try {
            val now = System.currentTimeMillis()
            runBlocking { repo.withLedger { db -> db.transactions().insert(TransactionEntity("receipt", sourceType = "SMS",
                sourceTimestamp = now, effectiveTimestamp = now, amountMinor = 100000, direction = "Credit", status = "Successful",
                channel = "Card", transactionType = "MerchantPayment", reviewState = "AutoRecorded", createdAt = now, updatedAt = now)) } }
            rule.setContent { MaterialTheme { LedgerApp(repo, "receipt" to false, consumeRequest = {}) } }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Mark as card bill payment").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Mark as card bill payment").performScrollTo().performClick()
            rule.onNodeWithText("Mark bill payment").performClick()
            rule.waitUntil(15_000) { runBlocking { repo.get("receipt")!!.transactionType == "CardRepayment" } }
            rule.onNodeWithText("Card bill payment · Successful").assertExists()
            assertEquals("0", runBlocking { repo.snapshot().credit.toString() })
        } finally { runBlocking { repo.eraseAll(); repo.close() } }
    }

    @Test fun bill_payment_filter_is_available_and_can_be_cleared() {
        val repo = repository()
        try {
            val now = System.currentTimeMillis()
            runBlocking { repo.withLedger { db -> listOf("CardRepayment", "MerchantPayment").forEach { type ->
                db.transactions().insert(TransactionEntity(type, sourceType = "SMS", sourceTimestamp = now,
                    effectiveTimestamp = now, amountMinor = 100000, direction = "Credit", status = "Successful",
                    channel = "Card", transactionType = type, counterpartyLabel = type, reviewState = "AutoRecorded",
                    createdAt = now, updatedAt = now))
            } } }
            rule.setContent { MaterialTheme { LedgerApp(repo, null, consumeRequest = {}) } }
            rule.onNodeWithText("Transactions").performClick()
            rule.onNodeWithContentDescription("Filter transactions").performClick()
            rule.onNodeWithText("Card bill payments").performScrollTo().performClick()
            rule.onNodeWithText("Apply").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("1 transaction").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("MerchantPayment").assertDoesNotExist()
            rule.onNodeWithContentDescription("Clear transaction filters").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("2 transactions").fetchSemanticsNodes().isNotEmpty() }
        } finally { runBlocking { repo.eraseAll(); repo.close() } }
    }
}

package `in`.financeministry.app

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.data.*
import `in`.financeministry.app.feature.HistoricalImportPanel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class HistoricalImportUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun erase_clears_unconfirmed_preview() {
        val context = rule.activity
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_SMS)
        val repository = TransactionRepository(context, "import_ui_${UUID.randomUUID()}")
        try {
            rule.activity.runOnUiThread {
                rule.activity.setContent {
                    MaterialTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            HistoricalImportPanel(repository, HistoricalSmsSource { _, emit ->
                                emit(HistoricalSms("TEST", System.currentTimeMillis() - 1000, 0, "INR 42 debited via UPI"))
                            })
                        }
                    }
                }
            }
            rule.onNodeWithText("Import last 3 months").performClick()
            rule.onNodeWithText("I understand — scan").performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Import preview").fetchSemanticsNodes().isNotEmpty() }
            runBlocking { repository.eraseAll() }
            rule.waitUntil(5000) { rule.onAllNodesWithText("Import preview").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("Import 1 transactions").assertDoesNotExist()
        } finally { runBlocking { repository.eraseAll(); repository.close() } }
    }
}

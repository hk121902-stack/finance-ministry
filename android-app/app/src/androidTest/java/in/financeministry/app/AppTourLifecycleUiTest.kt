package `in`.financeministry.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import `in`.financeministry.app.data.TransactionRepository
import `in`.financeministry.app.feature.APP_TOUR_VERSION_KEY
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AppTourLifecycleUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun skip_persists_update_reoffers_and_settings_replays_from_review() {
        val repository = TransactionRepository(ApplicationProvider.getApplicationContext<Application>(), "tour_ui_${UUID.randomUUID()}")
        val launch = mutableIntStateOf(0)
        try {
            rule.setContent { key(launch.intValue) { FinanceMinistryTheme { LedgerApp(repository, null, consumeRequest = {}) } } }
            rule.onNodeWithText("Your private ledger").assertIsDisplayed()
            rule.onNodeWithText("Skip for now").performClick()
            rule.runOnIdle {
                assertEquals(BuildConfig.VERSION_CODE, repository.preferences.getInt(APP_TOUR_VERSION_KEY, 0))
                assertFalse(repository.captureAllowed())
                assertFalse(repository.preferences.getBoolean("review_reminder", false))
                launch.intValue++ // Fresh ledger composition, not restored rotation state.
            }
            rule.onNodeWithText("Your private ledger").assertDoesNotExist()
            rule.runOnIdle {
                repository.preferences.edit().putInt(APP_TOUR_VERSION_KEY, BuildConfig.VERSION_CODE - 1).commit()
                launch.intValue++
            }
            rule.onNodeWithText("Your private ledger").assertIsDisplayed()
            rule.onNodeWithText("After your update · rediscover your tools").assertIsDisplayed()
            rule.onNodeWithText("Skip for now").performClick()
            rule.onNodeWithContentDescription("Open transactions").performClick()
            rule.onNodeWithContentDescription("Open review tab").performClick()
            rule.onNodeWithText("Settings").performClick()
            rule.onNodeWithText("App tour").performScrollTo().performClick()
            rule.onNodeWithText("Your private ledger").assertIsDisplayed()
            rule.onNodeWithText("Skip for now").performClick()
        } finally { runBlocking { repository.eraseAll(); repository.close() } }
    }
}

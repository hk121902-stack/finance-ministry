package `in`.financeministry.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import `in`.financeministry.app.feature.FirstUseGuide
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FirstUseGuideUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun tour_can_advance_go_back_and_skip_without_starting_setup() {
        var completed = 0
        var setupActions = 0
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FirstUseGuide(false, false, false, onDone = { completed++ },
                        onAdd = { setupActions++ }, onOpenSms = { setupActions++ },
                        onOpenSources = { setupActions++ }, onOpenReminder = { setupActions++ })
                }
            }
        }
        rule.onNodeWithText("Next").performScrollTo().performClick()
        rule.onNodeWithText("Back").performScrollTo().performClick()
        rule.onNodeWithText("Your private ledger").assertIsDisplayed()
        rule.onNodeWithText("Next").performScrollTo().performClick()
        rule.onNodeWithText("Skip for now").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, completed); assertEquals(0, setupActions) }
    }

    @Test fun every_chapter_has_details_and_completion_does_not_enable_setup() {
        var completed = 0
        var setupActions = 0
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FirstUseGuide(false, false, false, onDone = { completed++ },
                        onAdd = { setupActions++ }, onOpenSms = { setupActions++ },
                        onOpenSources = { setupActions++ }, onOpenReminder = { setupActions++ })
                }
            }
        }
        var chapters = 0
        while (chapters++ < 20) {
            rule.onNodeWithText("More detail").performScrollTo().performClick()
            rule.onNodeWithText("Less detail").assertIsDisplayed().performClick()
            if (rule.onAllNodesWithText("Next").fetchSemanticsNodes().isEmpty()) break
            rule.onNodeWithText("Next").performScrollTo().performClick()
        }
        rule.onNodeWithText("Optional setup").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Start using app").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, completed); assertEquals(0, setupActions) }
    }

    @Test fun recreation_keeps_the_current_chapter_and_expanded_detail() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FirstUseGuide(false, false, false, onDone = {}, onAdd = {},
                        onOpenSms = {}, onOpenSources = {}, onOpenReminder = {})
                }
            }
        }
        rule.onNodeWithText("Next").performScrollTo().performClick()
        rule.onNodeWithText("More detail").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Record it your way").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Less detail").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Skip for now").performScrollTo().assertIsDisplayed()
    }
}

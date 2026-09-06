package `in`.financeministry.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Host kills the background process between stages; never use on a personal ledger. */
class ProcessRestartSmsTest {
    @Test fun receiver_restarts_after_background_process_kill() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("restartStage")
        assumeTrue("Requires isolated process-restart driver", stage in listOf("prepare", "verify"))
        val app = ApplicationProvider.getApplicationContext<FinanceMinistryApp>()
        val repository = app.container.repository
        val prefs = repository.preferences
        if (stage == "prepare") {
            assertTrue(repository.snapshot().rows.none { it.amountMinor == 70123L })
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.grantRuntimePermission(app.packageName, android.Manifest.permission.RECEIVE_SMS)
            automation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            prefs.edit().putBoolean("restart_test_consent", prefs.getBoolean("sms_disclosure", false))
                .putBoolean("restart_test_notifications", prefs.getBoolean("notifications", true))
                .putBoolean("sms_disclosure", true).putBoolean("notifications", true).commit()
        } else try {
            val row = repository.snapshot().rows.single { it.amountMinor == 70123L }
            assertEquals("SMS", row.sourceType)
            assertEquals("Credit", row.direction)
            assertEquals("AutoRecorded", row.reviewState)
            assertTrue(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.tag == row.id })
            assertEquals(4300L, repository.get("synthetic-official-upgrade")!!.amountMinor)
        } finally {
            repository.snapshot().rows.filter { it.amountMinor == 70123L }.forEach { repository.delete(it.id) }
            prefs.edit().putBoolean("sms_disclosure", prefs.getBoolean("restart_test_consent", false))
                .putBoolean("notifications", prefs.getBoolean("restart_test_notifications", true))
                .remove("restart_test_consent").remove("restart_test_notifications").commit()
        }
        repository.close()
    }
}

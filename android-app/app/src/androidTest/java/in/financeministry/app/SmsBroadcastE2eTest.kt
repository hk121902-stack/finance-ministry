package `in`.financeministry.app

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The host sends a synthetic SMS through the emulator console when the ready file appears. */
class SmsBroadcastE2eTest {
    @Test fun system_sms_reaches_encrypted_ledger_and_notification() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires host emulator-console driver", InstrumentationRegistry.getArguments().getString("runSmsE2e") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>() as FinanceMinistryApp
        val repository = app.container.repository
        val prefs = repository.preferences
        val oldConsent = prefs.getBoolean("sms_disclosure", false)
        val oldNotifications = prefs.getBoolean("notifications", true)
        val before = repository.snapshot().rows.map { it.id }.toSet()
        val ready = java.io.File(app.filesDir, "synthetic_sms_test_ready")
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECEIVE_SMS)
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        try {
            prefs.edit().putBoolean("sms_disclosure", true).putBoolean("notifications", true).commit()
            ready.writeText("ready")
            val deadline = System.currentTimeMillis() + 45000
            var found = repository.snapshot().rows.firstOrNull { it.id !in before && it.amountMinor == 31415L }
            while (found == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(150)
                found = repository.snapshot().rows.firstOrNull { it.id !in before && it.amountMinor == 31415L }
            }
            assertNotNull("Synthetic emulator SMS was not recorded", found)
            assertEquals("SMS", found!!.sourceType)
            assertEquals("Debit", found.direction)
            assertEquals("AutoRecorded", found.reviewState)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == found.id })
        } finally {
            ready.delete()
            prefs.edit().putBoolean("sms_disclosure", oldConsent).putBoolean("notifications", oldNotifications).commit()
            repository.snapshot().rows.filter { it.id !in before && it.amountMinor == 31415L }.forEach { repository.delete(it.id) }
        }
    }
}

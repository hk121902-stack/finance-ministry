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
        val args = InstrumentationRegistry.getArguments()
        val expectedAmount = args.getString("expectedAmountMinor")?.toLong() ?: 31415L
        val expectedDirection = args.getString("expectedDirection") ?: "Debit"
        val repetitions = (args.getString("smsRepetitions")?.toInt() ?: 1).also { require(it in 1..20) }
        val multipart = args.getString("expectMultipart") == "true"
        val observedAt = java.util.concurrent.atomic.AtomicLong(0)
        val segments = java.util.concurrent.atomic.AtomicInteger(0)
        val observer = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val parts = android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isNullOrEmpty()) return
                observedAt.compareAndSet(0, android.os.SystemClock.elapsedRealtime())
                segments.set(parts.size)
            }
        }
        val filter = android.content.IntentFilter(android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply { priority = 999 }
        val timings = mutableListOf<Long>()
        val run = java.util.UUID.randomUUID().toString()
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECEIVE_SMS)
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        app.registerReceiver(observer, filter, android.Manifest.permission.BROADCAST_SMS, null, android.content.Context.RECEIVER_EXPORTED)
        try {
            prefs.edit().putBoolean("sms_disclosure", true).putBoolean("notifications", true).commit()
            repeat(repetitions) { index ->
            observedAt.set(0); segments.set(0)
            ready.writeText("$run:${index + 1}")
            val deadline = System.currentTimeMillis() + 45000
            var found = repository.snapshot().rows.firstOrNull { it.id !in before && it.amountMinor == expectedAmount }
            while (found == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(150)
                found = repository.snapshot().rows.firstOrNull { it.id !in before && it.amountMinor == expectedAmount }
            }
            assertNotNull("Synthetic emulator SMS was not recorded", found)
            assertEquals("SMS", found!!.sourceType)
            assertEquals(expectedDirection, found.direction)
            assertEquals("AutoRecorded", found.reviewState)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == found.id })
            val observerDeadline = android.os.SystemClock.elapsedRealtime() + 2000
            while (observedAt.get() == 0L && android.os.SystemClock.elapsedRealtime() < observerDeadline) Thread.sleep(25)
            assertTrue("Broadcast observer did not receive SMS", observedAt.get() > 0)
            if (multipart) assertTrue("Expected multiple delivered SMS segments", segments.get() > 1)
            val elapsed = android.os.SystemClock.elapsedRealtime() - observedAt.get()
            timings += elapsed
            assertEquals(1, repository.snapshot().rows.count { it.id !in before && it.amountMinor == expectedAmount })
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putLong("amountMinor", found.amountMinor!!); putString("direction", found.direction)
                putString("transactionStatus", found.status); putString("channel", found.channel)
                putBoolean("hasAccountHint", found.maskedAccountHint != null)
                putBoolean("hasCounterpartyLabel", found.counterpartyLabel != null)
                putInt("smsSample", index + 1); putInt("deliveredSegments", segments.get()); putLong("observedCaptureMs", elapsed)
            })
            ready.delete()
            repository.delete(found.id)
            }
            val sorted = timings.sorted()
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putInt("samples", sorted.size)
                putLong("medianObservedCaptureMs", sorted[sorted.size / 2])
                putLong("p95ObservedCaptureMs", sorted[kotlin.math.ceil(sorted.size * 0.95).toInt() - 1])
                putLong("maxObservedCaptureMs", sorted.last())
            })
            assertTrue("Observed broadcast-to-visible capture exceeded five seconds: ${sorted.last()} ms", sorted.all { it < 5000 })
        } finally {
            ready.delete()
            app.unregisterReceiver(observer)
            prefs.edit().putBoolean("sms_disclosure", oldConsent).putBoolean("notifications", oldNotifications).commit()
            repository.snapshot().rows.filter { it.id !in before && it.amountMinor == expectedAmount }.forEach { repository.delete(it.id) }
        }
    }
}

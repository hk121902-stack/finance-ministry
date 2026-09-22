package `in`.financeministry.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.financeministry.app.sms.RecurringPaymentReminder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecurringReminderIntegrationTest {
    @Test fun daily_due_check_can_be_scheduled_and_cancelled_without_recording_a_payment() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        RecurringPaymentReminder.cancel(context)
        try {
            RecurringPaymentReminder.schedule(context)
            assertTrue(RecurringPaymentReminder.isScheduled(context))
        } finally { RecurringPaymentReminder.cancel(context) }
        assertFalse(RecurringPaymentReminder.isScheduled(context))
    }
}

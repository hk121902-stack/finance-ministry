package `in`.financeministry.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import `in`.financeministry.app.FinanceMinistryApp
import `in`.financeministry.app.core.model.IncomingSms
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val repository = (context.applicationContext as FinanceMinistryApp).container.repository
        if (!repository.captureAllowed()) return
        val pending = goAsync()
        try {
            worker.execute {
                try {
                    val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    if (parts.isNullOrEmpty()) return@execute
                    val first = parts.first()
                    // Framework order is the multipart order; segment timestamps can differ.
                    check(parts.all { it.originatingAddress == first.originatingAddress })
                    val sms = IncomingSms(first.originatingAddress ?: "", first.timestampMillis, parts.joinToString("") { it.messageBody ?: "" })
                    runBlocking { repository.ingest(sms) { TransactionNotifications.post(context, it, repository.preferences.getBoolean("notifications", true)) } }
                } catch (_: Exception) {
                    repository.preferences.edit().putBoolean("capture_error", true).apply()
                } finally { pending.finish() }
            }
        } catch (_: Exception) { pending.finish() }
    }
    companion object { private val worker = Executors.newSingleThreadExecutor() }
}

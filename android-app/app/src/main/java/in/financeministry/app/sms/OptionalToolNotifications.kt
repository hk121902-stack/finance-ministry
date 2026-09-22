package `in`.financeministry.app.sms

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import `in`.financeministry.app.FinanceMinistryApp
import `in`.financeministry.app.MainActivity
import `in`.financeministry.app.R
import `in`.financeministry.app.data.*
import `in`.financeministry.app.money
import kotlinx.coroutines.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Calendar

object BudgetAlerts {
    private const val CHANNEL = "budget_alerts"
    suspend fun evaluate(context: Context, repository: TransactionRepository, month: LocalDate = LocalDate.now().withDayOfMonth(1)) {
        val preferences = repository.preferences
        for (status in repository.budgetStatuses(month)) {
            val key = BudgetAlertDecision.notificationKey(status, month) ?: continue
            val pref = "budget_alert_${status.budget.id}"
            if (preferences.getString(pref, null) == key) continue
            if (post(context, status)) preferences.edit().putString(pref, key).apply()
        }
    }
    private fun post(context: Context, status: BudgetStatus): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Optional alerts for category spending targets"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, status.budget.id.hashCode(), Intent(context, MainActivity::class.java)
            .setData(Uri.parse("finance://optional/budgets")).putExtra("open_optional_tools", "Budgets")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val threshold = requireNotNull(status.budget.alertPercent)
        manager.notify(status.budget.id, 2, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${status.budget.category} budget reached $threshold%")
            .setContentText("${money(status.spentMinor)} of ${money(status.budget.monthlyLimitMinor)} · spending target, not account balance")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build())
        return true
    }
}

object RecurringPaymentReminder {
    internal const val ACTION = "in.financeministry.app.RECURRING_PAYMENT_REMINDER"
    private const val REQUEST = 4801
    private const val CHANNEL = "payment_reminders"
    private val gate = Any()
    fun schedule(context: Context) = synchronized(gate) {
        val intent = Intent(context, RecurringPaymentReminderReceiver::class.java).setAction(ACTION)
        val pending = PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
    }
    fun sync(context: Context, active: Boolean) { if (active) schedule(context) else cancel(context) }
    fun cancel(context: Context) = synchronized(gate) {
        val pending = PendingIntent.getBroadcast(context, REQUEST, Intent(context, RecurringPaymentReminderReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) { context.getSystemService(AlarmManager::class.java).cancel(pending); pending.cancel() }
    }
    fun isScheduled(context: Context): Boolean = PendingIntent.getBroadcast(context, REQUEST,
        Intent(context, RecurringPaymentReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
    suspend fun deliver(context: Context, repository: TransactionRepository, today: LocalDate = LocalDate.now()) {
        val zone = ZoneId.systemDefault()
        repository.recurringReminders().filter { reminder ->
            reminder.active && RecurringSchedule.dueDate(today.year, today.monthValue, reminder.preferredDay) == today &&
                reminder.lastLinkedAt?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)) } != YearMonth.from(today)
        }.forEach { reminder -> post(context, reminder, today) }
    }
    private fun post(context: Context, reminder: RecurringReminderEntity, today: LocalDate) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val preferences = context.getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
        val key = "recurring_notified_${reminder.id}"
        if (preferences.getString(key, null) == today.toString()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Payment reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "User-created reminders that never initiate payments"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return
        val open = PendingIntent.getActivity(context, reminder.id.hashCode(), Intent(context, MainActivity::class.java)
            .setData(Uri.parse("finance://optional/reminders")).putExtra("open_optional_tools", "Payment reminders")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(reminder.id, 3, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title).setContentText("Reminder only${reminder.amountMinor?.let { " · ${money(it)}" } ?: ""}. Nothing was paid or recorded.")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build())
        preferences.edit().putString(key, today.toString()).apply()
    }
}

class RecurringPaymentReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            val repository = (context.applicationContext as FinanceMinistryApp).container.repository
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { RecurringPaymentReminder.sync(context, repository.recurringReminders().any { it.active }) }
                finally { pending.finish() }
            }
            return
        }
        if (intent.action != RecurringPaymentReminder.ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = (context.applicationContext as FinanceMinistryApp).container.repository
                RecurringPaymentReminder.deliver(context, repository)
            } finally {
                try { RecurringPaymentReminder.schedule(context) } finally { pending.finish() }
            }
        }
    }
}

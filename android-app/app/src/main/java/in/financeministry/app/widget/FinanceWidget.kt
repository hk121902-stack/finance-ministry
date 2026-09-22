package `in`.financeministry.app.widget

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import `in`.financeministry.app.FinanceMinistryApp
import `in`.financeministry.app.MainActivity
import `in`.financeministry.app.R
import `in`.financeministry.app.money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.math.BigInteger

object WidgetPresentation {
    fun summary(enabled: Boolean, locked: Boolean, personalSpendMinor: Long?): String = when {
        !enabled -> "Quick add"
        locked -> "Unlock to view summary"
        personalSpendMinor == null -> "Summary unavailable"
        else -> "This month · ${money(personalSpendMinor)}"
    }
}

class FinanceWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { renderAll(context) } finally { pending.finish() }
            }
        } else super.onReceive(context, intent)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FinanceWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, FinanceWidget::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }

        private suspend fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FinanceWidget::class.java))
            if (ids.isEmpty()) return
            val preferences = context.getSharedPreferences("finance_settings", Context.MODE_PRIVATE)
            val enabled = preferences.getBoolean("widget_summary", false)
            val locked = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
            val spend = if (enabled && !locked) try {
                val repository = (context.applicationContext as FinanceMinistryApp).container.repository
                repository.snapshot(today = LocalDate.now().withDayOfMonth(1)).personalSpend
                    .takeIf { it.signum() >= 0 && it <= BigInteger.valueOf(Long.MAX_VALUE) }?.toLong()
            } catch (_: Exception) { null } else null
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.finance_widget)
                views.setTextViewText(R.id.widget_summary, WidgetPresentation.summary(enabled, locked, spend))
                views.setTextViewText(R.id.widget_updated, if (enabled && !locked) "Updated just now" else "Amounts stay private by default")
                val add = Intent(context, MainActivity::class.java).putExtra("open_add", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                views.setOnClickPendingIntent(R.id.widget_add, PendingIntent.getActivity(context, id, add,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                manager.updateAppWidget(id, views)
            }
        }
    }
}

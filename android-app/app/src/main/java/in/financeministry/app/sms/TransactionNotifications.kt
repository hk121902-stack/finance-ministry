package `in`.financeministry.app.sms

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import `in`.financeministry.app.MainActivity
import `in`.financeministry.app.R
import `in`.financeministry.app.data.TransactionEntity
import `in`.financeministry.app.feature.transactionFlowLabel
import `in`.financeministry.app.feature.friendly
import java.math.BigDecimal

object TransactionNotifications {
    const val CHANNEL = "recorded_transactions"
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun post(context: Context, row: TransactionEntity, enabled: Boolean) {
        if (!enabled || (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Recorded transactions", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Confirms saved transactions and opens corrections"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return
        fun action(edit: Boolean, quick: Boolean = false): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).setData(Uri.parse("finance://transaction/${row.id}?edit=$edit&quick=$quick"))
                .putExtra("transaction_id", row.id).putExtra("edit", edit).putExtra("quick_classify", quick)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val amount = row.amountMinor?.let { "₹${BigDecimal.valueOf(it, 2).toPlainString()}" } ?: "Amount needs review"
        val view = action(false)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (row.reviewState == "NeedsReview") "Transaction needs review" else if (row.transactionType == "CardRepayment") "Card bill payment recorded" else "Transaction recorded")
            .setContentText("$amount · ${transactionFlowLabel(row)} · ${friendly(row.status)}")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(view)
            .setPublicVersion(Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Finance Ministry transaction").build())
            .addAction(Notification.Action.Builder(null, "View", view).build())
            .addAction(Notification.Action.Builder(null, "Categorize", action(true, true)).build())
            .addAction(Notification.Action.Builder(null, "Edit", action(true)).build()).build()
        manager.notify(row.id, 1, notification)
    }
}

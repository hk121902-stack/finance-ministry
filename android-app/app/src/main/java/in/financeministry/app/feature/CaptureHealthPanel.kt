package `in`.financeministry.app.feature

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.data.TransactionRepository
import `in`.financeministry.app.sms.ReviewReminder
import `in`.financeministry.app.sms.TransactionNotifications
import `in`.financeministry.app.transactionTime

/** Device facts only: no SMS bodies, account identifiers, uploads or delivery guarantees. */
@Composable
fun CaptureHealthPanel(repository: TransactionRepository, refreshGeneration: Int,
    onAdd: () -> Unit, onImport: () -> Unit, onManageCapture: () -> Unit, onReminder: () -> Unit) {
    val context = LocalContext.current
    val preferences = repository.preferences
    var preferenceGeneration by remember { mutableIntStateOf(0) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> preferenceGeneration++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val smsAllowed = remember(refreshGeneration) {
        context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    }
    val captureEnabled = remember(refreshGeneration, preferenceGeneration) { repository.captureAllowed() }
    val recordingNotificationsAvailable = remember(refreshGeneration) { TransactionNotifications.available(context) }
    val reminderAvailable = remember(refreshGeneration) { ReviewReminder.available(context) }
    val lastCapture = remember(preferenceGeneration) { preferences.getLong("last_capture_at", 0) }
    val errorDetected = remember(preferenceGeneration) { preferences.getBoolean("capture_error", false) }
    val notificationsEnabled = remember(preferenceGeneration) { preferences.getBoolean("notifications", true) }
    val reminderEnabled = remember(preferenceGeneration) { preferences.getBoolean("review_reminder", false) }
    val reminderTime = remember(preferenceGeneration) {
        String.format(java.util.Locale.ROOT, "%02d:%02d", preferences.getInt("review_reminder_hour", 20), preferences.getInt("review_reminder_minute", 0))
    }
    fun openSettings(intent: Intent) {
        try { context.startActivity(intent); settingsError = null }
        catch (_: Exception) { settingsError = "Could not open Android settings. Open Settings → Apps → Finance Ministry on your phone." }
    }
    fun appSettings() = openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    Text("Permission and capture status on this device.", style = MaterialTheme.typography.bodyMedium)
    HealthFact("SMS capture", if (captureEnabled) "Enabled" else "Paused",
        if (captureEnabled) "Incoming supported bank alerts can be recorded." else "Manual entry remains available.")
    TextButton(onClick = onManageCapture) { Text("Manage SMS capture") }
    HealthFact("SMS permission", if (smsAllowed) "Allowed" else "Not allowed")
    if (!smsAllowed) TextButton(onClick = ::appSettings) { Text("Open Android permission settings") }
    HealthFact("Recording notifications", when {
        !notificationsEnabled -> "Off in app"
        !recordingNotificationsAvailable -> "Blocked by Android"
        else -> "Allowed"
    }, "Notifications do not control SMS capture.")
    if (!notificationsEnabled) TextButton(onClick = onManageCapture) { Text("Manage recording notifications") }
    if (!recordingNotificationsAvailable) TextButton(onClick = {
        openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }) { Text("Open Android notification settings") }
    HealthFact("Last recorded SMS", if (lastCapture > 0) transactionTime(lastCapture) else "None recorded yet",
        "No recent capture does not prove a payment was missed.")
    HealthFact("Recording errors", if (errorDetected) "A recording error was detected" else "None detected",
        if (errorDetected) "Check your recent payments and add any missing transaction. This is an observed error, not a count of missed payments."
        else "Only errors observed by the app can be reported.")
    Text("Android may delay or withhold messages. The app cannot detect every SMS it never receives.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Missed a payment?", style = MaterialTheme.typography.titleMedium)
    Button(onClick = onAdd) { Text("Add missing transaction") }
    OutlinedButton(onClick = onImport) { Text("Import last 3 months") }
    Text("Background restrictions vary by phone. Check this app’s battery and background settings if capture stops. No need to disable Play Protect.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = ::appSettings) { Text("Open app settings") }
    HealthFact("Review reminder", if (reminderEnabled) "Daily around $reminderTime" else "Off",
        if (reminderEnabled && !reminderAvailable) "Android notifications are blocked for reminders."
        else "Reminds you only when saved transactions need review; Android can delay delivery.")
    TextButton(onClick = onReminder) { Text("Edit review schedule") }
    Text("These diagnostics stay on this device and contain no raw messages or account identifiers.", style = MaterialTheme.typography.bodySmall)
    settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable private fun HealthFact(label: String, value: String, note: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

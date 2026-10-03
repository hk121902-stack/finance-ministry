package `in`.financeministry.app.feature

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Offline policy: available even before permissions, without an app network connection. */
@Composable
fun PrivacyPanel(onErase: () -> Unit, busy: Boolean) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    fun open(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { error = "No browser available. Support: github.com/hk121902-stack/finance-ministry" }
    }
    Text("Privacy and support", style = MaterialTheme.typography.titleMedium)
    Text("Finance Ministry is an independent open-source app, not a government service or bank. Maintained by hk121902-stack. Policy updated 3 October 2026.")
    Text("Local processing", style = MaterialTheme.typography.titleSmall)
    Text("No sign-up, app backend, advertising or analytics. The app has no Internet permission and cannot initiate payments. Optional SMS permissions are requested only after disclosure. Android delivers incoming messages broadly; filtering happens on your device. OTPs and non-transaction messages are rejected, but parsing can be wrong. Review and correct your ledger.")
    Text("Optional permissions", style = MaterialTheme.typography.titleSmall)
    Text("RECEIVE_SMS captures new alerts. READ_SMS imports the last three calendar months after a separate disclosure and preview. Import never changes or sends your messages. Normalized preview data stays in memory until confirmation or cancellation. Untouched imported records can be undone. POST_NOTIFICATIONS is separate; manual entry works without SMS or notifications. Capture can be paused in settings.")
    Text("Stored data", style = MaterialTheme.typography.titleSmall)
    Text("The encrypted database stores amounts, currency, direction, status, categories, dates, masked account hints, parsed recipient labels, source mappings, notes, corrections, parser metadata and local repayment/refund links. Raw SMS bodies, senders and raw transaction references are not stored or uploaded. Device-keyed fingerprints detect repeats. Do not paste OTPs, full account numbers or messages into notes.")
    Text("Security and visibility", style = MaterialTheme.typography.titleSmall)
    Text("SQLCipher encrypts the ledger and Android Keystore protects local keys. Database and settings are excluded from Android backup and device-transfer rules; OEM behavior is not guaranteed. Debug alpha builds permit authorized debugging and are not production security-certified. Notifications show normalized payment details; private lock-screen visibility is requested, but your Android settings control visibility. Your Messages app may independently display the original SMS. Optional reminders never make payments or create transactions.")
    Text("Export, recovery and deletion", style = MaterialTheme.typography.titleSmall)
    Text("Encrypted backups go to the location you choose in Android's file picker. Their password is not uploaded and cannot be recovered. CSV reports are unencrypted and are not restore backups. The chosen file provider may sync files under its own policies. Restore replaces the ledger only after validation and confirmation, and pauses capture and reminders. Deleting a transaction removes its correction history. Erase all deletes local data, keys and settings and pauses capture. Uninstall also deletes your ledger; exported files are not deleted by erasure or uninstall.")
    Text("Support and third parties", style = MaterialTheme.typography.titleSmall)
    Text("GitHub handles repository visits, downloads and reports under its own policies. Share synthetic samples only—never real SMS or account details. Report vulnerabilities privately through GitHub security advisories, not public issues. If private reporting is unavailable, open a minimal issue requesting a private channel, without exploit details. This solo-maintained alpha has no guaranteed support response time.")
    TextButton(onClick = { open("https://github.com/hk121902-stack/finance-ministry/issues") }) { Text("Help and bug reports") }
    TextButton(onClick = { open("https://github.com/hk121902-stack/finance-ministry/security/advisories/new") }) { Text("Report a security issue privately") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    TextButton(onClick = onErase, enabled = !busy) { Text("Erase all local data", color = MaterialTheme.colorScheme.error) }
}

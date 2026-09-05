package `in`.financeministry.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.data.*
import `in`.financeministry.app.feature.TransactionForm
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun money(minor: Long?): String = minor?.let { "₹${BigDecimal.valueOf(it, 2).toPlainString()}" } ?: "Amount unknown"
fun transactionTime(millis: Long): String = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

@Composable
fun LedgerApp(repository: TransactionRepository, request: Pair<String, Boolean>?, consumeRequest: () -> Unit) {
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsState()
    var snapshot by remember { mutableStateOf<LedgerSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var form by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var disclosure by remember { mutableStateOf(false) }
    var eraseDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(repository.preferences.getBoolean("notifications", true)) }
    var captureEnabled by remember { mutableStateOf(repository.captureAllowed()) }
    var filter by rememberSaveable { mutableStateOf("All") }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { captureEnabled = repository.captureAllowed() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    BackHandler(enabled = selected != null && !form) { if (!busy) { selected = null; selectedId = null } }
    LaunchedEffect(selectedId) {
        if (selectedId != null && selected?.id != selectedId) {
            try { selected = repository.get(selectedId!!); if (selected == null) { selectedId = null; form = false; error = "This transaction no longer exists." } }
            catch (_: Exception) { error = "Cannot open this transaction."; selectedId = null; form = false }
        }
    }
    LaunchedEffect(revision) {
        try { snapshot = repository.snapshot() } catch (_: Exception) { error = "Cannot open encrypted storage. Existing data was preserved. Erase all only if you intend to delete it." }
    }
    LaunchedEffect(request) {
        if (request != null) {
            try { selected = repository.get(request.first); if (selected == null) error = "This transaction no longer exists." else { selectedId = selected!!.id; form = request.second } }
            catch (_: Exception) { error = "Cannot open this transaction. Data was preserved." }
            consumeRequest()
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Finance Ministry", style = MaterialTheme.typography.headlineMedium)
            Text("Private alpha · On-device only", style = MaterialTheme.typography.labelMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (selectedId != null && selected == null) { Text("Loading transaction…") }
            else if (form) {
                TransactionForm(repository, selected, onDone = { form = false; selected = null; selectedId = null; error = null })
            } else if (selected != null) {
                val row = selected!!
                Text(money(row.amountMinor), style = MaterialTheme.typography.headlineSmall)
                Text("${row.direction} · ${row.status} · ${row.reviewState}")
                Text("${row.transactionType} · ${row.channel} · ${row.sourceType}")
                Text(transactionTime(row.effectiveTimestamp))
                row.counterpartyLabel?.let { Text(it) }; row.maskedAccountHint?.let { Text(it) }; row.userNotes?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { form = true }) { Text("Edit / confirm") }
                    OutlinedButton(onClick = { selected = null; selectedId = null }) { Text("Back") }
                }
                TextButton(onClick = { deleteDialog = true }) { Text("Delete transaction") }
            } else {
                Button(onClick = { selected = null; selectedId = null; form = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("+ Add transaction") }
                snapshot?.let {
                    Text("This month: spent ₹${BigDecimal(it.debit, 2).toPlainString()} · received ₹${BigDecimal(it.credit, 2).toPlainString()}")
                    Text("Excludes transfers, needs-review, failed, reversed and pending records.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = {
                    if (captureEnabled) { repository.preferences.edit().putBoolean("sms_disclosure", false).apply(); captureEnabled = false } else disclosure = true
                }, enabled = !busy) { Text(if (captureEnabled) "Pause SMS capture" else "Enable SMS capture") }
                Row {
                    Text("Recording notifications", Modifier.weight(1f).padding(top = 12.dp))
                    Switch(checked = notifications, onCheckedChange = {
                        notifications = it; repository.preferences.edit().putBoolean("notifications", it).apply()
                        if (it && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                if (notifications && Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow Android notifications") }
                if (repository.preferences.getBoolean("capture_error", false)) Text("A message could not be recorded. Add it manually if needed.", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("All", "Review", "Manual", "Edited").forEach { label ->
                        FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                    }
                }
                val rows = snapshot?.rows.orEmpty().filter { when (filter) {
                    "Review" -> it.reviewState == "NeedsReview"; "Manual" -> it.sourceType == "Manual"; "Edited" -> it.isUserCorrected; else -> true
                } }
                if (rows.isEmpty()) Text("No transactions yet. Add one, or enable capture for new messages.")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth().clickable { selected = row; selectedId = row.id }) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${money(row.amountMinor)} · ${row.direction}", style = MaterialTheme.typography.titleMedium)
                                Text("${row.status} · ${row.sourceType} · ${if (row.isUserCorrected) "Corrected" else row.reviewState}")
                                Text(transactionTime(row.effectiveTimestamp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text("Latest 500 records · No historical SMS import", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { eraseDialog = true }, enabled = !busy) { Text("Erase all local data") }
            }
        }
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("Read new SMS on this device?") },
        text = { Text("Android gives this app access to incoming SMS, including non-financial messages. Processing stays on this device. We reject OTPs and non-transactions and store normalized financial fields in encrypted storage. Raw messages and senders are not stored or uploaded. SMS permission is optional; manual entry always works. No payment or bank connection is involved.") },
        confirmButton = { TextButton(onClick = { repository.preferences.edit().putBoolean("sms_disclosure", true).apply(); disclosure = false; smsPermission.launch(Manifest.permission.RECEIVE_SMS) }) { Text("I understand — continue") } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Not now") } })
    if (eraseDialog) AlertDialog(onDismissRequest = { if (!busy) eraseDialog = false }, title = { Text("Erase all local data?") },
        text = { Text("Permanently deletes transactions, corrections, encryption keys and settings. There is no backup or undo. SMS capture will be off.") },
        confirmButton = { TextButton(onClick = { busy = true; scope.launch {
            try { repository.eraseAll(); selected = null; selectedId = null; form = false; snapshot = null; captureEnabled = false; notifications = true; error = null }
            catch (_: Exception) { error = "Erasure did not fully finish. Capture is off; retry before re-enabling it." }
            finally { busy = false; eraseDialog = false }
        } }, enabled = !busy) { Text("Erase permanently") } },
        dismissButton = { TextButton(onClick = { eraseDialog = false }, enabled = !busy) { Text("Cancel") } })
    if (deleteDialog) AlertDialog(onDismissRequest = { if (!busy) deleteDialog = false }, title = { Text("Delete this transaction?") },
        text = { Text("The record and correction history will be permanently removed.") },
        confirmButton = { TextButton(onClick = { if (!busy) { busy = true; scope.launch {
            try { repository.delete(selected!!.id); selected = null; selectedId = null } catch (_: Exception) { error = "Delete failed. Please retry." }
            finally { busy = false; deleteDialog = false }
        } } }, enabled = !busy) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }, enabled = !busy) { Text("Cancel") } })
}

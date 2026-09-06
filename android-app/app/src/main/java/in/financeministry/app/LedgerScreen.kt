package `in`.financeministry.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import `in`.financeministry.app.feature.friendly
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun money(minor: Long?): String = minor?.let { "₹${BigDecimal.valueOf(it, 2).toPlainString()}" } ?: "Amount unknown"
fun transactionTime(millis: Long): String = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

@Composable
fun LedgerApp(repository: TransactionRepository, request: Pair<String, Boolean>?, refreshGeneration: Int = 0, consumeRequest: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsState()
    var snapshot by remember { mutableStateOf<LedgerSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var form by rememberSaveable { mutableStateOf(false) }
    var dirtyForm by rememberSaveable { mutableStateOf(false) }
    var requestWarning by remember { mutableStateOf(false) }
    var requestApproval by remember { mutableIntStateOf(0) }
    var approvedRequest by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var selected by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var disclosure by remember { mutableStateOf(false) }
    var eraseDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(repository.preferences.getBoolean("notifications", true)) }
    var captureEnabled by remember { mutableStateOf(repository.captureAllowed()) }
    var notificationAvailable by remember { mutableStateOf(`in`.financeministry.app.sms.TransactionNotifications.available(context)) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var reviewAvailable by remember { mutableStateOf(false) }
    BackHandler(enabled = settings && !form && selectedId == null) { settings = false }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { captureEnabled = repository.captureAllowed() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAvailable = `in`.financeministry.app.sms.TransactionNotifications.available(context)
    }
    LaunchedEffect(refreshGeneration) {
        captureEnabled = repository.captureAllowed()
        notifications = repository.preferences.getBoolean("notifications", true)
        notificationAvailable = `in`.financeministry.app.sms.TransactionNotifications.available(context)
    }
    BackHandler(enabled = selected != null && !form) { if (!busy) { selected = null; selectedId = null } }
    LaunchedEffect(selectedId) {
        if (selectedId != null && selected?.id != selectedId) {
            try { selected = repository.get(selectedId!!); if (selected == null) { selectedId = null; form = false; error = "This transaction no longer exists." } }
            catch (_: Exception) { error = "Cannot open this transaction."; selectedId = null; form = false }
        }
    }
    LaunchedEffect(revision, offset, filter, refreshGeneration) {
        loading = true
        try {
            val result = repository.snapshot(offset, filter)
            snapshot = result
            reviewAvailable = repository.snapshot(0, "Review").rows.isNotEmpty()
            if (result.rows.isEmpty() && offset > 0) offset = maxOf(0, offset - 100)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Cannot open encrypted storage. Existing data was preserved. Erase all only if you intend to delete it." }
        finally { loading = false }
    }
    LaunchedEffect(request, requestApproval) {
        if (request != null) {
            if (form && dirtyForm && approvedRequest != request) { requestWarning = true; return@LaunchedEffect }
            approvedRequest = null
            dirtyForm = false
            selected = null; selectedId = null; form = false; settings = false
            try { selected = repository.get(request.first); if (selected == null) error = "This transaction no longer exists." else { selectedId = selected!!.id; form = request.second; error = null } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Cannot open this transaction. Data was preserved." }
            consumeRequest()
        }
    }
    Surface(Modifier.fillMaxSize()) {
        val ledgerScroll = rememberScrollState()
        val pageModifier = Modifier.safeDrawingPadding().fillMaxSize().padding(16.dp)
        Column(if (settings || (selected != null && !form)) pageModifier.verticalScroll(ledgerScroll) else pageModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!form && selectedId == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (settings) "Settings" else "Your ledger", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { settings = !settings }) { Text(if (settings) "Home" else "Settings") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (selectedId != null && selected == null) { Text("Loading transaction…") }
            else if (form) {
                TransactionForm(repository, selected, onDone = { dirtyForm = false; form = false; selected = null; selectedId = null; offset = 0; error = null }, onDirtyChange = { dirtyForm = it })
            } else if (selected != null) {
                val row = selected!!
                Text(money(row.amountMinor), style = MaterialTheme.typography.headlineSmall)
                Text("${friendly(row.direction)} · ${friendly(row.status)}")
                Text("${friendly(row.transactionType)} · ${friendly(row.channel)} · ${friendly(row.sourceType)}")
                if (row.reviewState == "NeedsReview") Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Check this transaction", style = MaterialTheme.typography.titleMedium)
                        Text(when {
                            row.amountMinor == null -> "We couldn’t identify a reliable amount. Enter it to finish recording."
                            row.direction == "Unknown" -> "Choose whether money went out, came in, or moved between accounts."
                            row.status == "Unknown" -> "Check whether this payment completed before confirming."
                            else -> "Check the details before including this transaction in your totals."
                        })
                    }
                } else Text(friendly(row.reviewState), style = MaterialTheme.typography.bodySmall)
                Text(transactionTime(row.effectiveTimestamp))
                row.linkedOriginalId?.let { originalId ->
                    TextButton(onClick = { selected = null; selectedId = originalId }) { Text("View linked original transaction") }
                    Text("Linked by matching reference, account, channel and full amount.", style = MaterialTheme.typography.bodySmall)
                }
                row.counterpartyLabel?.let { Text(it) }; row.maskedAccountHint?.let { Text(it) }; row.userNotes?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { form = true }) { Text("Edit / confirm") }
                    OutlinedButton(onClick = { selected = null; selectedId = null }) { Text("Back") }
                }
                TextButton(onClick = { deleteDialog = true }) { Text("Delete transaction") }
            } else if (settings) {
                Text("Finance Ministry · Private alpha", style = MaterialTheme.typography.titleMedium)
                Text("Your ledger stays encrypted on this device. No bank connection or payment access.")
                OutlinedButton(onClick = {
                    if (captureEnabled) { repository.preferences.edit().putBoolean("sms_disclosure", false).apply(); captureEnabled = false } else disclosure = true
                }, enabled = !busy) { Text(if (captureEnabled) "Pause SMS capture" else "Enable SMS capture") }
                Text(if (captureEnabled) "SMS capture active" else "SMS capture paused or permission unavailable · Manual entry works", style = MaterialTheme.typography.bodySmall)
                Row {
                    Text("Recording notifications", Modifier.weight(1f).padding(top = 12.dp))
                    Switch(checked = notifications, onCheckedChange = {
                        notifications = it; repository.preferences.edit().putBoolean("notifications", it).apply()
                        if (it && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                if (notifications && !notificationAvailable) {
                    Text("Android notifications are blocked. SMS capture can still record transactions.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Open notification settings") }
                    if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow Android notifications") }
                }
                HorizontalDivider()
                `in`.financeministry.app.feature.HistoricalImportPanel(repository)
                Text("Data and privacy", style = MaterialTheme.typography.titleMedium)
                Text("There is no backup or recovery after erasing data. Uninstalling the app loses your ledger.")
                TextButton(onClick = { eraseDialog = true }, enabled = !busy) { Text("Erase all local data", color = MaterialTheme.colorScheme.error) }
            } else {
                Button(onClick = { selected = null; selectedId = null; form = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("+ Add transaction") }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    snapshot?.let {
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Recorded this month", style = MaterialTheme.typography.labelLarge)
                            Text("₹${BigDecimal(it.debit, 2).toPlainString()}", style = MaterialTheme.typography.headlineLarge)
                            Text("Money out · Received ₹${BigDecimal(it.credit, 2).toPlainString()}")
                            HorizontalDivider()
                            Text("Today: spent ₹${BigDecimal(it.dailyDebit, 2).toPlainString()} · received ₹${BigDecimal(it.dailyCredit, 2).toPlainString()}", style = MaterialTheme.typography.bodySmall)
                            Text("Excludes transfers and unconfirmed, failed, pending or reversed payments. SMS coverage is incomplete.", style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                }
                item {
                    TextButton(onClick = { settings = true }) { Text(if (captureEnabled) "SMS capture enabled · Manage" else "SMS capture off · Set up") }
                    if (notifications && !notificationAvailable) Text("Notifications are blocked. Enable them in Settings.", style = MaterialTheme.typography.bodySmall)
                    if (repository.preferences.getBoolean("capture_error", false)) Text("A message could not be recorded. Add it manually if needed.", color = MaterialTheme.colorScheme.error)
                    if (reviewAvailable) OutlinedButton(onClick = { if (filter != "Review" || offset != 0) { filter = "Review"; offset = 0; snapshot = null } }, modifier = Modifier.fillMaxWidth()) { Text("Review transactions that need checking") }
                }
                item {
                Text("Transactions", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("All", "Review", "Manual", "Edited").forEach { label ->
                        FilterChip(selected = filter == label, onClick = { if (filter != label || offset != 0) { filter = label; offset = 0; snapshot = null } }, label = { Text(label) })
                    }
                }
                }
                val rows = snapshot?.rows.orEmpty()
                if (loading) item { Text("Loading transactions…") }
                else if (rows.isEmpty()) item { Text(if (filter == "Review") "You’re all caught up. No saved transactions need review." else "No transactions here yet. Add one manually or enable new SMS capture in Settings.") }
                rows.groupBy { Instant.ofEpochMilli(it.effectiveTimestamp).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, rows) ->
                    item("day-$day") { Text(day.format(DateTimeFormatter.ofPattern("d MMM yyyy")), style = MaterialTheme.typography.labelLarge) }
                    items(rows, key = { it.id }) { row ->
                        Card(Modifier.fillMaxWidth().clickable { selected = row; selectedId = row.id }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(row.counterpartyLabel ?: if (row.sourceType == "Manual") "Manual transaction" else "${friendly(row.channel)} transaction", style = MaterialTheme.typography.titleMedium)
                                Text("${money(row.amountMinor)} · ${friendly(row.direction)}", style = MaterialTheme.typography.titleLarge)
                                Text("${friendly(row.status)} · ${if (row.isUserCorrected) "Edited by you" else friendly(row.reviewState)}", style = MaterialTheme.typography.bodySmall)
                                Text(transactionTime(row.effectiveTimestamp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { offset = maxOf(0, offset - 100); snapshot = null }, enabled = !loading && offset > 0) { Text("Newer") }
                    Text("Page ${offset / 100 + 1}", Modifier.padding(top = 12.dp))
                    TextButton(onClick = { offset += 100; snapshot = null }, enabled = !loading && snapshot?.hasOlder == true && offset <= Int.MAX_VALUE - 201) { Text("Older") }
                }
                Text("All saved history · Import past SMS from Settings", style = MaterialTheme.typography.bodySmall)
                }
                }
            }
        }
    }
    if (requestWarning) AlertDialog(onDismissRequest = { requestWarning = false; consumeRequest() },
        title = { Text("Open another transaction?") }, text = { Text("Your current changes have not been saved.") },
        confirmButton = { TextButton(onClick = { approvedRequest = request; requestWarning = false; requestApproval++ }) { Text("Discard and open") } },
        dismissButton = { TextButton(onClick = { requestWarning = false; consumeRequest() }) { Text("Keep editing") } })
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

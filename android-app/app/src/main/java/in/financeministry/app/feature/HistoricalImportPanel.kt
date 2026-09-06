package `in`.financeministry.app.feature

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.data.*
import `in`.financeministry.app.money
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoricalImportPanel(repository: TransactionRepository, source: HistoricalSmsSource? = null) {
    val generation by repository.eraseGeneration.collectAsState()
    key(repository, generation) { ImportPanelContent(repository, source) }
}

@Composable
private fun ImportPanelContent(repository: TransactionRepository, source: HistoricalSmsSource?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsState()
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var processed by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var disclosure by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf(false) }
    var lastBatch by remember { mutableStateOf<ImportBatchEntity?>(null) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()) }
    fun date(value: Long) = dateFormat.format(Instant.ofEpochMilli(value))
    fun scan() {
        if (busy) return
        preview = null; message = null; processed = 0; busy = true
        job = scope.launch {
            try {
                preview = repository.previewImport(source ?: AndroidHistoricalSmsSource(context), progress = { processed = it })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { message = "Android did not allow inbox access. Manual entry and new SMS capture are unchanged." }
            catch (_: Exception) { message = "Could not finish the scan. Nothing was imported. Check SMS permission and try again." }
            finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else message = "Inbox permission was not granted. You can still add transactions manually or use new SMS capture."
    }
    LaunchedEffect(revision) {
        try { lastBatch = repository.latestImport() } catch (_: Exception) { lastBatch = null }
    }
    HorizontalDivider()
    Text("Past transactions", style = MaterialTheme.typography.titleMedium)
    Text("Optionally scan SMS still on this phone from the last three calendar months. Nothing is uploaded; original SMS are never changed.")
    OutlinedButton(onClick = { disclosure = true }, enabled = !busy) { Text("Import last 3 months") }
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Working… $processed messages scanned")
        TextButton(onClick = { job?.cancel(); preview = null; message = "Cancelled. Any unfinished import is rolled back. A completed batch remains available to undo below." }) { Text("Cancel import") }
    }
    message?.let { Text(it) }
    preview?.let { ready ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Import preview", style = MaterialTheme.typography.titleMedium)
                Text("${date(ready.window.start)} – ${date(ready.window.end)}")
                Text("${ready.scanned} scanned · ${ready.ignored} ignored")
                Text("${ready.ready} ready · ${ready.needsReview} need review · ${ready.duplicates} duplicates skipped")
                Text("Unconfirmed records stay out of totals. Existing corrections are preserved. No per-transaction notifications will be sent.")
                ready.rows.take(5).forEach { row -> Text("${date(row.effectiveTimestamp)} · ${money(row.amountMinor)} · ${friendly(row.direction)}") }
                if (ready.rows.size > 5) Text("Showing the first 5 of ${ready.rows.size} transactions.")
                Button(onClick = {
                    if (!busy) {
                        busy = true
                        job = scope.launch {
                            try {
                                val result = repository.commitImport(ready)
                                preview = null
                                message = "Imported ${result.inserted} transactions. Skipped ${result.duplicates} duplicates. Review uncertain records from Home."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { preview = null; message = "Import could not finish. Start a new scan; existing records were preserved." }
                            finally { busy = false }
                        }
                    }
                }, enabled = !busy && ready.rows.isNotEmpty()) { Text("Import ${ready.rows.size} transactions") }
                TextButton(onClick = { preview = null }, enabled = !busy) { Text("Discard preview") }
            }
        }
    }
    lastBatch?.let { batch ->
        Text("Last import: ${batch.inserted} records · ${date(batch.createdAt)}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { undo = true }, enabled = !busy) { Text("Undo last import") }
    }
    if (disclosure) {
        val window = ImportWindow.lastThreeMonths()
        AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("Read existing SMS?") },
            text = { Text("Android grants access to the whole SMS inbox, including personal messages and OTPs. This scan only reads ${date(window.start)} through ${date(window.end)} and filters on-device. Only normalized financial fields are imported after your confirmation; raw SMS and senders are not saved. Import is optional and separate from new SMS capture. Deleted SMS, RCS and other apps are not included.") },
            confirmButton = { TextButton(onClick = { disclosure = false; if (repository.historyPermissionGranted()) scan() else permission.launch(Manifest.permission.READ_SMS) }) { Text("I understand — scan") } },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Not now") } })
    }
    if (undo) AlertDialog(onDismissRequest = { undo = false }, title = { Text("Undo last import?") },
        text = { Text("Remove only untouched transactions from that import. Records you edited and all pre-existing records are kept. Your phone's SMS are not deleted.") },
        confirmButton = { TextButton(onClick = {
            val batch = lastBatch ?: return@TextButton
            undo = false; busy = true
            job = scope.launch {
                try { message = "Removed ${repository.undoImport(batch.id)} imported records. Edited records were preserved."; preview = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Undo could not finish. Please try again." }
                finally { busy = false }
            }
        }) { Text("Undo import") } }, dismissButton = { TextButton(onClick = { undo = false }) { Text("Keep records") } })
}

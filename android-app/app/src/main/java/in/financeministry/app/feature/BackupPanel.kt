package `in`.financeministry.app.feature

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.data.*
import `in`.financeministry.app.transactionTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun BackupPanel(repository: TransactionRepository, month: LocalDate, onBusyChange: (Boolean) -> Unit = {}, onRestored: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var passwordAction by rememberSaveable { mutableStateOf<String?>(null) }
    // Passwords, decrypted previews and output bytes deliberately never enter saved instance state.
    var pendingBackup by remember { mutableStateOf<PreparedBackup?>(null) }
    var pendingCsv by remember { mutableStateOf<ByteArray?>(null) }
    var restoreUri by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var replaceConfirm by remember { mutableStateOf(false) }
    var csvDialog by rememberSaveable { mutableStateOf(false) }
    var hasData by remember { mutableStateOf(false) }
    var lastBackup by remember { mutableLongStateOf(repository.preferences.getLong("last_backup_at", 0)) }
    var backupLocation by remember { mutableStateOf(repository.preferences.getString("last_backup_location", null)) }
    SideEffect { onBusyChange(busy) }
    DisposableEffect(Unit) { onDispose { onBusyChange(false) } }
    LaunchedEffect(revision) {
        try { hasData = repository.hasBackupData() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not check current storage."; hasData = true }
    }
    fun documentName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).take(120) else null
        } ?: "Selected document"
    } catch (_: Exception) { "Selected document" }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val prepared = pendingBackup
        pendingBackup = null
        if (uri == null) { prepared?.bytes?.fill(0); message = "Backup cancelled. No completed backup was recorded." }
        else if (prepared == null) error = "Backup preparation expired. Create a new backup; an empty file may remain in the chosen location."
        else {
            busy = true
            scope.launch {
                try {
                    repository.writePreparedBackup(prepared) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open this destination." } }
                    val location = withContext(Dispatchers.IO) { "${documentName(uri)} · ${uri.authority ?: "Chosen storage provider"}" }
                    repository.preferences.edit().putString("last_backup_location", location).apply()
                    backupLocation = location; lastBackup = repository.preferences.getLong("last_backup_at", 0)
                    message = "Encrypted backup saved. Keep its password separately."; error = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Backup was not confirmed complete. Your ledger is unchanged. Try another destination; a partial file may remain." }
                finally { prepared.bytes.fill(0); busy = false }
            }
        }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val bytes = pendingCsv
        pendingCsv = null
        if (uri == null) { bytes?.fill(0); message = "CSV export cancelled." }
        else if (bytes == null) error = "Export preparation expired. Export again; an empty file may remain in the chosen location."
        else {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                        .use { it.write(bytes); it.flush() } }
                    message = "CSV saved. It is a readable report, not a restore backup."; error = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { error = "CSV could not be saved completely. A partial file may remain; your ledger is unchanged." }
                finally { bytes.fill(0); busy = false }
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) message = "Restore cancelled. Your ledger is unchanged."
        else { restoreUri = uri.toString(); passwordAction = "restore"; preview = null }
    }
    Text("Encrypted backup", style = MaterialTheme.typography.titleMedium)
    Text("Transactions, corrections, payment sources, category rules and repayment history. No Android keys or permissions are copied.", style = MaterialTheme.typography.bodySmall)
    Text(if (lastBackup > 0) "Last completed backup: ${transactionTime(lastBackup)}" else "No completed backup yet.", style = MaterialTheme.typography.bodySmall)
    backupLocation?.let { Text("Location: $it", style = MaterialTheme.typography.bodySmall) }
    Button(enabled = !busy, onClick = { message = null; error = null; passwordAction = "backup" }) { Text("Create encrypted backup") }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Restore backup", style = MaterialTheme.typography.titleMedium)
    Text("Replaces this ledger; it does not merge records. You choose the file through Android. Capture and reminders are switched off after restoration.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = !busy, onClick = { message = null; error = null; openBackup.launch(arrayOf("*/*")) }) { Text("Choose backup file") }
    preview?.let { ready ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Restore preview", style = MaterialTheme.typography.titleMedium)
                Text("Created ${transactionTime(ready.createdAt)}", style = MaterialTheme.typography.bodySmall)
                Text("${ready.transactionCount} transactions · ${ready.repaymentCount} repayments")
                Text("${ready.sourceCount} payment sources · ${ready.ruleCount} category rules", style = MaterialTheme.typography.bodySmall)
                Text("${ready.budgetCount} budgets · ${ready.recurringReminderCount} payment reminders", style = MaterialTheme.typography.bodySmall)
                if (hasData) {
                    Text("Protect your current ledger first. Replacement requires a completed backup of its current contents.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { message = null; error = null; passwordAction = "backup" }) { Text("Back up current ledger") }
                }
                Text("After restoring from another installation, historical imports need review because old SMS-matching keys cannot transfer.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy, onClick = { replaceConfirm = true }) { Text("Replace current ledger") }
                TextButton(enabled = !busy, onClick = { preview = null; restoreUri = null }) { Text("Discard preview") }
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Export CSV", style = MaterialTheme.typography.titleMedium)
    Text("A readable report for a chosen period. Anyone with the file can read its financial information. It cannot restore the app.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = !busy, onClick = { message = null; error = null; csvDialog = true }) { Text("Export CSV report") }
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working… keep the app open.", style = MaterialTheme.typography.bodySmall) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    passwordAction?.let { action ->
        BackupPasswordDialog(creating = action == "backup", busy = busy, onCancel = { if (!busy) passwordAction = null }) { password ->
            busy = true; error = null
            scope.launch {
                try {
                    if (action == "backup") {
                        pendingBackup = repository.prepareBackup(password)
                        passwordAction = null
                        saveBackup.launch("finance-ministry-${LocalDate.now()}.fmbackup")
                    } else {
                        val uri = Uri.parse(requireNotNull(restoreUri) { "Choose the backup file again." })
                        val bytes = withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use { BackupInput.readBounded(it) } }
                        try { preview = repository.previewBackup(bytes, password) } finally { bytes.fill(0) }
                        passwordAction = null; message = "Backup validated. Nothing has been replaced."
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (e: IllegalArgumentException) { error = e.message ?: "Check the password and backup file."; passwordAction = null }
                catch (_: Exception) { error = "Could not read or prepare the backup. Your ledger was not replaced."; passwordAction = null }
                finally { password.fill('\u0000'); busy = false }
            }
        }
    }
    if (replaceConfirm) AlertDialog(onDismissRequest = { if (!busy) replaceConfirm = false }, title = { Text("Replace this ledger?") },
        text = { Text("This replaces transactions, sources, rules and repayment history with the validated backup. There is no automatic merge. Keep a separate current backup.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
            try {
                val result = repository.restoreBackup(requireNotNull(preview), protectCurrent = true)
                preview = null; restoreUri = null; replaceConfirm = false; lastBackup = 0; backupLocation = null
                message = if (result.settingsApplied) "Ledger restored. SMS capture and reminders are off; review Settings before enabling them."
                    else "Ledger restored. Settings recovery is pending; close and reopen the app before continuing."
                error = null; onRestored()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "Restore did not finish. Check the current ledger before retrying."; replaceConfirm = false }
            finally { busy = false }
        } }) { Text(if (busy) "Restoring…" else "Confirm replacement") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { replaceConfirm = false }) { Text("Cancel") } })
    if (csvDialog) CsvPeriodDialog(month, busy, onDismiss = { if (!busy) csvDialog = false }) { start, end ->
        busy = true; error = null
        scope.launch {
            try {
                pendingCsv = repository.csvReport(start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()).toByteArray(Charsets.UTF_8)
                require(pendingCsv!!.size <= BackupCipher.MAX_BYTES) { "Report is too large. Choose a shorter period." }
                csvDialog = false; saveCsv.launch("finance-ministry-$start-to-$end.csv")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "Could not prepare report."; pendingCsv?.fill(0); pendingCsv = null; csvDialog = false }
            finally { busy = false }
        }
    }
}

@Composable private fun BackupPasswordDialog(creating: Boolean, busy: Boolean, onCancel: () -> Unit, onSubmit: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text(if (creating) "Protect your backup" else "Unlock backup") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (creating) "Use at least 12 characters. This password cannot be recovered; keep it somewhere safe." else "Enter the password used when this backup was created.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(password, { password = it.take(1024) }, enabled = !busy, label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            if (creating) OutlinedTextField(confirmation, { confirmation = it.take(1024) }, enabled = !busy, label = { Text("Confirm backup password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            when {
                password.length < if (creating) 12 else 1 -> error = "Enter ${if (creating) "at least 12 characters" else "the backup password"}."
                creating && password != confirmation -> error = "Passwords do not match."
                else -> { val chars = password.toCharArray(); password = ""; confirmation = ""; onSubmit(chars) }
            }
        }) { Text(if (busy) "Working…" else if (creating) "Choose save location" else "Validate backup") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") } })
}

@Composable private fun CsvPeriodDialog(month: LocalDate, busy: Boolean, onDismiss: () -> Unit, onExport: (LocalDate, LocalDate) -> Unit) {
    var start by rememberSaveable { mutableStateOf(month.withDayOfMonth(1).toString()) }
    var end by rememberSaveable { mutableStateOf(month.withDayOfMonth(1).plusMonths(1).minusDays(1).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Export readable report") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("CSV is not encrypted. Only share it with people you trust. Dates use this device's timezone.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(start, { start = it.take(10) }, label = { Text("Start date · yyyy-MM-dd") }, singleLine = true)
            OutlinedTextField(end, { end = it.take(10) }, label = { Text("End date · yyyy-MM-dd") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            try { val first = LocalDate.parse(start); val last = LocalDate.parse(end); require(first <= last); onExport(first, last) }
            catch (_: Exception) { error = "Enter valid dates with the end on or after the start." }
        }) { Text("Choose CSV location") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}

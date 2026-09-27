package `in`.financeministry.app.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.core.model.transactionCategories
import `in`.financeministry.app.data.*
import kotlinx.coroutines.launch

@Composable
fun BatchCleanupActions(repository: TransactionRepository, rows: List<TransactionEntity>, selected: List<String>, onClearSelection: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf("Other") }
    var sourceId by rememberSaveable { mutableStateOf("") }
    var sources by remember { mutableStateOf<List<PaymentSourceEntity>>(emptyList()) }
    var preview by remember { mutableStateOf<BatchPreview?>(null) }
    var undo by remember { mutableStateOf<BatchUndo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val revision by repository.revision.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) {
        try { sources = repository.activePaymentSources() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load payment sources." }
    }
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${selected.size} selected · ${rows.size} on this page", style = MaterialTheme.typography.labelLarge)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            undo?.let { token -> TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { repository.undoBatch(token); undo = null; message = "Update undone."; error = null }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (e: Exception) { error = e.message ?: "Could not undo." }
                    finally { busy = false }
                }
            }) { Text("Undo") } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { mode = "category"; preview = null }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.weight(1f)) { Text("Category") }
                OutlinedButton(onClick = { mode = "source"; preview = null }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.weight(1f)) { Text("Payment source") }
            }
            if (selected.isNotEmpty()) TextButton(onClick = onClearSelection, enabled = !busy) { Text("Clear selection") }
        }
    mode?.let { kind ->
        AlertDialog(onDismissRequest = { if (!busy) { mode = null; preview = null } }, title = { Text(if (kind == "category") "Change category" else "Assign payment source") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${selected.size} selected transactions")
                if (kind == "category") CompactChoice("Batch category", category, transactionCategories.map { it to it }) { category = it; preview = null }
                else {
                    CompactChoice("Batch source", sourceId, sources.map { it.id to it.nickname }) { sourceId = it; preview = null }
                    if (sources.isEmpty()) Text("Add a payment source in Settings first.")
                }
                Text("Review the preview before applying. No payment is confirmed by this action.", style = MaterialTheme.typography.bodySmall)
                preview?.let { plan ->
                    Text("${plan.changeCount} will change · ${plan.unchangedCount} already match · ${plan.skipped.size} skipped", style = MaterialTheme.typography.titleSmall)
                    plan.skipped.forEach { (id, reason) -> Text("${rows.firstOrNull { it.id == id }?.counterpartyLabel ?: "Payment"}: $reason", style = MaterialTheme.typography.bodySmall) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = {
                TextButton(enabled = !busy && (kind == "category" || sourceId.isNotBlank()), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val current = preview
                            if (current == null) {
                                preview = repository.previewBatch(selected.toSet(), category.takeIf { kind == "category" }, sourceId.takeIf { kind == "source" })
                                error = null
                            } else {
                                undo = repository.applyBatch(current)
                                message = "Updated ${undo!!.changedIds.size} transactions. ${current.skipped.size} skipped."
                                mode = null; preview = null; onClearSelection(); error = null
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (e: Exception) { error = e.message ?: "Could not apply update."; preview = null }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Working…" else if (preview == null) "Preview changes" else "Apply update") }
            }, dismissButton = { TextButton(enabled = !busy, onClick = { mode = null; preview = null }) { Text("Cancel") } })
    }
}

package `in`.financeministry.app.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.core.model.transactionCategories
import `in`.financeministry.app.data.*
import kotlinx.coroutines.launch

@Composable
fun CategoryRulesPanel(repository: TransactionRepository, onConflict: (String) -> Unit) {
    val revision by repository.revision.collectAsState()
    var rules by remember { mutableStateOf<List<CategoryRuleEntity>>(emptyList()) }
    var sources by remember { mutableStateOf<List<PaymentSourceEntity>>(emptyList()) }
    var conflicts by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) {
        try { rules = repository.categoryRules(); sources = repository.paymentSources(); conflicts = repository.categoryConflicts(); loaded = true }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load category rules." }
    }
    Text("Future payments only. Exact merchant matches can be limited to a payment source. Rules never change purpose, amounts or financial confirmation.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { creating = true }, enabled = !busy) { Text("New rule") }
    if (loaded && rules.isEmpty()) Text("No rules yet. You can also remember a category when editing a transaction.")
    val conflictingIds = CategoryRules.conflictingRuleIds(rules)
    rules.forEach { rule ->
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.merchant, style = MaterialTheme.typography.titleMedium)
                    Text("${rule.category} · ${rule.sourceId?.let { id -> sources.firstOrNull { it.id == id }?.nickname ?: "Unavailable source" } ?: "All sources"}", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = rule.enabled, modifier = Modifier.semantics { contentDescription = "Enable rule ${rule.merchant}" }, enabled = !busy,
                    onCheckedChange = { enabled -> busy = true; scope.launch {
                        try { repository.saveCategoryRule(rule.merchant, rule.sourceId, rule.category, enabled, rule.id); error = null }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (e: Exception) { error = e.message ?: "Could not update rule." }
                        finally { busy = false }
                    } })
            }
            if (rule.id in conflictingIds) Text("Overlaps another enabled rule with a different category. Matching new payments will ask you to choose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = { editing = rule.id }, enabled = !busy) { Text("Edit") }
                TextButton(onClick = { deleting = rule.id }, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "Delete rule ${rule.merchant}" }) { Text("Delete") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        }
    }
    if (conflicts.isNotEmpty()) {
        Text("Category choices needed", style = MaterialTheme.typography.titleMedium)
        Text("These payments matched conflicting rules when recorded. Their financial status is unchanged.", style = MaterialTheme.typography.bodySmall)
        conflicts.forEach { row -> TextButton(onClick = { onConflict(row.id) }) { Text("Choose category · ${row.counterpartyLabel ?: "Payment"}") } }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val existing = rules.firstOrNull { it.id == editing }
    if (creating || existing != null) RuleEditor(repository, existing, sources) { creating = false; editing = null }
    deleting?.let { id -> AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("Delete this rule?") },
        text = { Text("Previously recorded transactions will not change.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
            try { repository.deleteCategoryRule(id); deleting = null; error = null }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not delete rule." }
            finally { busy = false }
        } }) { Text("Delete rule") } }, dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text("Cancel") } }) }
}

@Composable private fun RuleEditor(repository: TransactionRepository, existing: CategoryRuleEntity?, sources: List<PaymentSourceEntity>, onDone: () -> Unit) {
    var merchant by rememberSaveable(existing?.id) { mutableStateOf(existing?.merchant.orEmpty()) }
    var category by rememberSaveable(existing?.id) { mutableStateOf(existing?.category ?: "Other") }
    var sourceId by rememberSaveable(existing?.id) { mutableStateOf(existing?.sourceId) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDone() }, title = { Text(if (existing == null) "New category rule" else "Edit category rule") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(merchant, { merchant = it.take(60) }, label = { Text("Exact merchant") }, singleLine = true)
            CompactChoice("Rule category", category, transactionCategories.map { it to it }) { category = it }
            CompactChoice("Rule source", sourceId ?: "", listOf("" to "All sources") + sources.filter { it.active || it.id == sourceId }.map { it.id to it.nickname }) { sourceId = it.ifBlank { null } }
            Text("Matches the whole name, ignoring letter case and surrounding spaces. Existing transactions stay unchanged.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
            try { repository.saveCategoryRule(merchant, sourceId, category, existing?.enabled ?: true, existing?.id); onDone() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message ?: "Could not save rule." }
            finally { busy = false }
        } }) { Text(if (busy) "Saving…" else "Save rule") } },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") } })
}

@Composable internal fun CompactChoice(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: ${options.firstOrNull { it.first == value }?.second ?: "Choose"}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); expanded = false }) }
        }
    }
}

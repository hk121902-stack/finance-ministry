package `in`.financeministry.app.feature

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import `in`.financeministry.app.LedgerNavigation
import `in`.financeministry.app.data.*
import `in`.financeministry.app.money
import `in`.financeministry.app.transactionTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MatchingReviewScreen(repository: TransactionRepository, initialHistory: Boolean = false,
    onDetails: () -> Unit, onNavigate: (String) -> Unit, onSettings: () -> Unit,
    onTransaction: (String) -> Unit, onBusyChange: (Boolean) -> Unit) {
    val revision by repository.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var pairs by remember { mutableStateOf<List<MatchSuggestion>>(emptyList()) }
    var scanTruncated by remember { mutableStateOf(false) }
    var decisions by remember { mutableStateOf<List<MatchDecisionEntity>>(emptyList()) }
    var sources by remember { mutableStateOf<List<PaymentSourceEntity>>(emptyList()) }
    var history by rememberSaveable { mutableStateOf(initialHistory) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var undoId by rememberSaveable { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastDecision by rememberSaveable { mutableStateOf<String?>(null) }
    SideEffect { onBusyChange(busy || selectedKey != null || undoId != null) }
    DisposableEffect(Unit) { onDispose { onBusyChange(false) } }
    LaunchedEffect(revision, retry) {
        try {
            val scan = repository.matchSuggestionScan(); pairs = scan.suggestions; scanTruncated = scan.truncated
            decisions = repository.matchDecisions(); sources = repository.paymentSources()
            loaded = true
            if (selectedKey != null && pairs.none { it.key == selectedKey }) selectedKey = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load comparisons. Your ledger is unchanged." }
    }
    BackHandler(enabled = selectedKey == null && undoId == null) {
        if (!busy) { if (history) history = false else onDetails() }
    }
    fun decide(pair: MatchSuggestion, action: MatchAction, survivor: String?, category: String?, notes: String?) {
        busy = true; error = null; message = null
        scope.launch {
            try {
                val saved = repository.resolveMatch(pair, action, survivor, category, notes)
                selectedKey = null; lastDecision = saved.id
                message = "Decision saved. Both records kept."
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: IllegalArgumentException) { error = failure.message }
            catch (_: Exception) { error = "Could not save the decision. Refresh before retrying." }
            finally { busy = false }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Review", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onSettings, enabled = !busy) { Text("Settings") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = false, onClick = onDetails, enabled = !busy, label = { Text("Needs details") })
                FilterChip(selected = true, onClick = { history = false }, enabled = !busy, label = { Text("Possible matches") })
            }
            TextButton(onClick = { history = !history; error = null }, enabled = !busy) { Text(if (history) "Back to suggestions" else "Decision history") }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(if (history) "Your decisions" else "Suggestions, not confirmed matches", style = MaterialTheme.typography.titleLarge)
                    Text(if (history) "Both records stay in the ledger. Undo restores the prior treatment; later edits and repayment links are protected."
                        else "These payments already follow the normal totals rules and may be counted twice. Needs details is separate and excluded from totals.", style = MaterialTheme.typography.bodySmall)
                }
                message?.let { text -> item {
                    Text(text)
                    if (lastDecision != null && decisions.any { it.id == lastDecision && it.undoneAt == null }) {
                        TextButton(onClick = { undoId = lastDecision }, enabled = !busy) { Text("Undo last decision") }
                    }
                } }
                error?.let { text -> item {
                    Text(text, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { error = null; retry++ }, enabled = !busy) { Text("Refresh comparisons") }
                } }
                if (!loaded) item { Text("Loading comparisons…") }
                else if (history) {
                    if (decisions.isEmpty()) item { Text("No decisions yet.") }
                    items(decisions, key = { it.id }) { decision ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(when (decision.action) { "SamePayment" -> "Same payment · one record counted"; "SelfTransfer" -> "Own-account transfer"; else -> "Kept as separate payments" }, style = MaterialTheme.typography.titleMedium)
                            Text(transactionTime(decision.createdAt), style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onTransaction(decision.firstId) }, enabled = !busy) { Text("First record") }
                                TextButton(onClick = { onTransaction(decision.secondId) }, enabled = !busy) { Text("Second record") }
                            }
                            if (decision.undoneAt == null) OutlinedButton(onClick = { undoId = decision.id }, enabled = !busy,
                                modifier = Modifier.testTag("undo-${decision.id}")) { Text("Undo decision") }
                            else Text("Undone · ${transactionTime(decision.undoneAt)}", style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                } else {
                    if (pairs.isEmpty()) item { Text("No possible matches found. This does not prove the ledger has no duplicates or transfers.") }
                    if (scanTruncated) item { Text("Showing a bounded scan of possible matches. Resolve these, then refresh to continue.", style = MaterialTheme.typography.bodySmall) }
                    items(pairs, key = { it.key }) { pair ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (pair.kind == MatchKind.Duplicate) "Possible duplicate" else "Possible own-account transfer", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text("${pair.first.counterpartyLabel ?: "Payment"} · ${money(pair.first.amountMinor)}", style = MaterialTheme.typography.titleMedium)
                            Text("${sourceName(pair.first, sources)} ↔ ${sourceName(pair.second, sources)}", style = MaterialTheme.typography.bodySmall)
                            Text("${transactionTime(pair.first.effectiveTimestamp)} · ${friendly(pair.first.sourceType)} / ${friendly(pair.second.sourceType)}", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { error = null; selectedKey = pair.key }, enabled = !busy,
                                modifier = Modifier.testTag("compare-${pair.key}")) { Text("Compare payments") }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            LedgerNavigation("Review") { if (!busy) { if (it == "Review") onDetails() else onNavigate(it) } }
        }
    }
    val selected = pairs.firstOrNull { it.key == selectedKey }
    selected?.let { pair ->
        MatchComparisonSheet(pair, sources, busy, error, onDismiss = { if (!busy) { selectedKey = null; error = null } },
            onConfirm = { action, survivor, category, notes -> decide(pair, action, survivor, category, notes) })
    }
    if (undoId != null) AlertDialog(onDismissRequest = { if (!busy) undoId = null }, title = { Text("Undo this decision?") },
        text = { Text("Both records remain. Their previous counting and classification will be restored. Repayment links must be reviewed first.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val id = requireNotNull(undoId); busy = true; error = null
            scope.launch {
                try { repository.undoMatch(id); undoId = null; lastDecision = null; message = "Decision undone. Both records kept." }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Could not undo the decision."; undoId = null }
                finally { busy = false }
            }
        }) { Text("Confirm undo") } }, dismissButton = { TextButton(onClick = { undoId = null }, enabled = !busy) { Text("Cancel") } })
}

private fun sourceName(row: TransactionEntity, sources: List<PaymentSourceEntity>) =
    sources.firstOrNull { it.id == row.paymentSourceId }?.nickname ?: "${friendly(row.channel)} · ${row.maskedAccountHint ?: "Source not assigned"}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MatchComparisonSheet(pair: MatchSuggestion, sources: List<PaymentSourceEntity>, busy: Boolean,
    error: String?, onDismiss: () -> Unit, onConfirm: (MatchAction, String?, String?, String?) -> Unit) {
    var survivor by rememberSaveable(pair.key) { mutableStateOf(pair.first.id) }
    var category by rememberSaveable(pair.key) { mutableStateOf(pair.first.id) }
    var notes by rememberSaveable(pair.key) { mutableStateOf(pair.first.id) }
    var editingRetainedDetails by rememberSaveable(pair.key) { mutableStateOf(false) }
    val rows = listOf(pair.first, pair.second)
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (pair.kind == MatchKind.Duplicate) "Compare possible duplicate" else "Compare possible transfer", style = MaterialTheme.typography.headlineSmall)
            rows.forEachIndexed { index, row ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.counterpartyLabel ?: "Payment", style = MaterialTheme.typography.titleMedium)
                        Text(money(row.amountMinor), style = MaterialTheme.typography.titleMedium)
                    }
                    Text("${if (index == 0) "First" else "Second"} · ${friendly(row.sourceType)} · ${transactionTime(row.effectiveTimestamp)}", style = MaterialTheme.typography.bodySmall)
                    Text(sourceName(row, sources), style = MaterialTheme.typography.bodySmall)
                }
                if (index == 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            pair.evidence.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (pair.kind == MatchKind.Duplicate) {
                val winner = rows.first { it.id == survivor }
                val keptCategory = rows.first { it.id == category }.category
                val keptNotes = rows.first { it.id == notes }.userNotes ?: "No notes"
                Text("${if (survivor == pair.first.id) "First" else "Second"} record will count · $keptCategory · $keptNotes", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { editingRetainedDetails = !editingRetainedDetails }, enabled = !busy) {
                    Text(if (editingRetainedDetails) "Hide retained details" else "Change retained details")
                }
                if (editingRetainedDetails) {
                    Text("Which record should count?", style = MaterialTheme.typography.titleMedium)
                    rows.forEachIndexed { index, row ->
                        MatchChoice(if (index == 0) "Count first record" else "Count second record", survivor == row.id, !busy) {
                            survivor = row.id; category = row.id; notes = row.id
                        }
                    }
                    Text("Keep category", style = MaterialTheme.typography.titleMedium)
                    rows.forEachIndexed { index, row -> MatchChoice("${if (index == 0) "First" else "Second"}: ${row.category}", category == row.id, !busy) { category = row.id } }
                    Text("Keep notes", style = MaterialTheme.typography.titleMedium)
                    rows.forEachIndexed { index, row -> MatchChoice("${if (index == 0) "First" else "Second"} notes: ${row.userNotes ?: "None"}", notes == row.id, !busy) { notes = row.id } }
                }
                Text("Counts ${money(winner.amountMinor)} once. Purpose and split stay with the counted record: ${friendly(winner.ownership)}, your share ${money(RepaymentAccounting.personal(winner))}. The other record remains in history as an excluded duplicate.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { onConfirm(MatchAction.SamePayment, survivor, category, notes) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Confirm same payment") }
            } else {
                Text("Did this money move between your own accounts?", style = MaterialTheme.typography.titleMedium)
                Text("Both records stay in history. Confirmation excludes both from spending and money-in/out totals. Equal amounts alone do not prove a transfer.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { onConfirm(MatchAction.SelfTransfer, null, null, null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Yes, self transfer") }
            }
            OutlinedButton(onClick = { onConfirm(MatchAction.KeepBoth, null, null, null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (pair.kind == MatchKind.Duplicate) "Keep both" else "Separate payments") }
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable private fun MatchChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

package `in`.financeministry.app.feature

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
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.data.*
import `in`.financeministry.app.money
import `in`.financeministry.app.transactionTime
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

@Composable
fun RepaymentHistoryPanel(repository: TransactionRepository, expense: TransactionEntity, eligible: Boolean) {
    val revision by repository.revision.collectAsState()
    var history by remember(expense.id) { mutableStateOf<List<RepaymentEntity>>(emptyList()) }
    var add by rememberSaveable(expense.id) { mutableStateOf(false) }
    var editId by rememberSaveable(expense.id) { mutableStateOf<String?>(null) }
    var unlinkId by rememberSaveable(expense.id) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(expense.id, revision) {
        try { history = repository.repaymentsFor(expense.id); error = null }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load repayment history." }
    }
    if (expense.direction != "Debit") return
    if (expense.repaymentExpected && expense.ownership in listOf("ForOther", "Group") || history.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("Repayment history", style = MaterialTheme.typography.titleMedium)
        if (eligible && RepaymentAccounting.principal(expense) > 0) {
            Text("Expected ${money(RepaymentAccounting.principal(expense))} · Received ${money(expense.repaidMinor)}", style = MaterialTheme.typography.bodySmall)
            if (RepaymentAccounting.owed(expense) > 0) Button(onClick = { add = true }) { Text("Record repayment") }
            else Text("Settled", style = MaterialTheme.typography.bodySmall)
        } else Text("Confirm an eligible expense before recording a repayment.", style = MaterialTheme.typography.bodySmall)
        if (history.isEmpty()) Text("No repayments recorded yet.", style = MaterialTheme.typography.bodySmall)
        history.forEach { entry ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(money(entry.amountMinor), style = MaterialTheme.typography.titleSmall)
                Text(entry.receivedAt?.let(::transactionTime) ?: "Date unknown · legacy repayment", style = MaterialTheme.typography.bodySmall)
                Text("${entry.payer.ifBlank { "No name" }} · ${if (entry.method == "Legacy") "Previously recorded" else if (entry.method == "Cash") "Cash receipt" else "Linked incoming payment"}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { editId = entry.id }, enabled = !busy && eligible) { Text("Edit repayment") }
                    TextButton(onClick = { unlinkId = entry.id }, enabled = !busy) { Text("Unlink") }
                }
            }
        }
    } else if (!expense.repaymentExpected && expense.ownership in listOf("ForOther", "Group")) {
        Text("Gift or treat · no repayment expected", style = MaterialTheme.typography.bodySmall)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (add || editId != null) {
        val entry = history.firstOrNull { it.id == editId }
        if (add || entry != null) RepaymentEditor(repository, expense, entry, onDismiss = { add = false; editId = null })
    }
    unlinkId?.let { id ->
        AlertDialog(onDismissRequest = { if (!busy) unlinkId = null }, title = { Text("Unlink repayment?") },
            text = { Text("This amount becomes owed again. The incoming transaction is kept; no money is deleted or moved.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { repository.removeRepayment(id); unlinkId = null; error = null }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (e: Exception) { error = e.message ?: "Could not unlink repayment." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Unlinking…" else "Unlink repayment") } },
            dismissButton = { TextButton(onClick = { unlinkId = null }, enabled = !busy) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepaymentEditor(repository: TransactionRepository, expense: TransactionEntity, existing: RepaymentEntity?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable(existing?.id) { mutableStateOf(existing?.amountMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "") }
    var payer by rememberSaveable(existing?.id) { mutableStateOf(existing?.payer ?: expense.groupLabel.orEmpty()) }
    var date by rememberSaveable(existing?.id) { mutableStateOf(existing?.receivedAt ?: System.currentTimeMillis()) }
    var dateKnown by rememberSaveable(existing?.id) { mutableStateOf(existing == null || existing.receivedAt != null) }
    var method by rememberSaveable(existing?.id) { mutableStateOf(if (existing == null) "Linked" else existing.method) }
    var incomingId by rememberSaveable(existing?.id) { mutableStateOf(existing?.incomingId) }
    var credits by remember { mutableStateOf<List<Pair<TransactionEntity, Long>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val initialAmount = existing?.amountMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()
    val dirty = amount != initialAmount || payer != (existing?.payer ?: expense.groupLabel.orEmpty()) ||
        incomingId != existing?.incomingId || (existing != null && (dateKnown != (existing.receivedAt != null) || dateKnown && date != existing.receivedAt))
    val close = { if (!busy) { if (dirty) discard = true else onDismiss() }; Unit }
    LaunchedEffect(expense.id) {
        try { credits = repository.availableRepaymentCredits(); loaded = true }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load incoming payments. Close and retry." }
    }
    val pickerTheme = if (androidx.compose.foundation.isSystemInDarkTheme()) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.imePadding()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (existing == null) "Record repayment" else "Edit repayment", style = MaterialTheme.typography.titleLarge)
            Text("Still owed ${money(RepaymentAccounting.owed(expense))}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(amount, { amount = it }, label = { Text("Repayment amount (INR)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(payer, { payer = it.take(40) }, label = { Text("From · local name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (existing == null) {
                FilterChip(selected = method == "Linked", onClick = { method = "Linked" }, label = { Text("Link an existing money-in") })
                FilterChip(selected = method == "Cash", onClick = { method = "Cash"; incomingId = null }, label = { Text("Record cash repayment") })
            }
            if (existing == null && method == "Linked") {
                Text("Linking allocates an existing receipt. It does not add another money-in.", style = MaterialTheme.typography.bodySmall)
                if (!loaded) Text("Loading incoming payments…")
                else if (credits.isEmpty()) Text("No eligible incoming payments with money available to allocate.")
                credits.forEach { (row, available) ->
                    FilterChip(selected = incomingId == row.id, onClick = { incomingId = row.id; date = row.effectiveTimestamp; dateKnown = true },
                        label = { Column { Text(row.counterpartyLabel ?: "Incoming payment"); Text("${money(available)} unallocated · ${transactionTime(row.effectiveTimestamp)}", style = MaterialTheme.typography.bodySmall) } })
                }
            }
            if (existing?.incomingId != null || existing == null && method == "Linked") {
                if (existing != null || incomingId != null) Text("Receipt date: ${transactionTime(date)}", style = MaterialTheme.typography.bodySmall)
                if (existing != null) Text("Only this allocation changes. To correct the incoming amount or date, unlink its repayments first and edit that transaction.", style = MaterialTheme.typography.bodySmall)
            } else {
                if (existing?.method == "Legacy") Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = dateKnown, onCheckedChange = { dateKnown = it })
                    Text("I know the repayment date", Modifier.weight(1f))
                }
                if (dateKnown) TextButton(onClick = {
                    val local = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault())
                    android.app.DatePickerDialog(context, pickerTheme, { _, y, m, d ->
                        date = local.withYear(y).withMonth(1).withDayOfMonth(1).withMonth(m + 1).withDayOfMonth(d).toInstant().toEpochMilli()
                    }, local.year, local.monthValue - 1, local.dayOfMonth).show()
                }) { Text("Received: ${transactionTime(date)}") }
                Text(if (existing?.method == "Legacy") "Updating this history does not create an incoming transaction."
                    else "Saving creates one manual cash receipt and links it to this expense.", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy && (existing != null || method == "Cash" || incomingId != null), modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch {
                    try {
                        if (existing != null) repository.editRepayment(existing.id, amount, date.takeIf { dateKnown }, payer)
                        else repository.recordRepayment(expense.id, amount, date, payer, incomingId.takeIf { method == "Linked" })
                        onDismiss()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (e: IllegalArgumentException) { error = e.message ?: "Check the repayment details." }
                    catch (_: Exception) { error = "Could not save repayment. Close and check the history before retrying." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Saving…" else "Save repayment") }
            TextButton(onClick = close, enabled = !busy) { Text("Cancel") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard repayment changes?") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Discard changes") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

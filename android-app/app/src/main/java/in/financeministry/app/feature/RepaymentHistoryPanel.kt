package `in`.financeministry.app.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
    var memberNames by remember(expense.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var add by rememberSaveable(expense.id) { mutableStateOf(false) }
    var editId by rememberSaveable(expense.id) { mutableStateOf<String?>(null) }
    var unlinkId by rememberSaveable(expense.id) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(expense.id, revision) {
        try { history = repository.repaymentsFor(expense.id); memberNames = repository.localMembers().associate { it.id to it.name }; error = null }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load repayment history." }
    }
    if (expense.direction != "Debit") return
    if (expense.ownership == "Group" && expense.repaymentExpected) GroupSharesPanel(repository, expense, eligible)
    if (expense.repaymentExpected && expense.ownership in listOf("ForOther", "Group") || history.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text("Repayment history", style = MaterialTheme.typography.titleMedium)
        if (eligible && RepaymentAccounting.principal(expense) > 0) {
            Text("Expected ${money(RepaymentAccounting.principal(expense))} · Received ${money(expense.repaidMinor)}", style = MaterialTheme.typography.bodySmall)
            if (RepaymentAccounting.owed(expense) > 0) Button(onClick = { add = true }) { Text("Record money received") }
            else Text("Settled", style = MaterialTheme.typography.bodySmall)
        } else Text("Confirm an eligible expense before recording a repayment.", style = MaterialTheme.typography.bodySmall)
        if (history.isEmpty()) Text("No repayments recorded yet.", style = MaterialTheme.typography.bodySmall)
        history.forEach { entry ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(money(entry.amountMinor), style = MaterialTheme.typography.titleSmall)
                Text(entry.receivedAt?.let(::transactionTime) ?: "Date unknown · legacy repayment", style = MaterialTheme.typography.bodySmall)
                Text("${memberNames[entry.memberId] ?: entry.payer.ifBlank { "No name" }} · ${if (entry.method == "Legacy") "Previously recorded" else if (entry.method == "Cash") "Cash receipt" else "Linked incoming payment"}", style = MaterialTheme.typography.bodySmall)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RepaymentEditor(repository: TransactionRepository, expense: TransactionEntity, existing: RepaymentEntity?, onDismiss: () -> Unit,
    initialMemberId: String? = null) {
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
    var choosingPayment by rememberSaveable(existing?.id) { mutableStateOf(false) }
    var memberId by rememberSaveable(expense.id, existing?.id) { mutableStateOf(existing?.memberId ?: initialMemberId) }
    var shares by remember { mutableStateOf<List<GroupShareEntity>>(emptyList()) }
    var members by remember { mutableStateOf<List<LocalMemberEntity>>(emptyList()) }
    var repayments by remember { mutableStateOf<List<RepaymentEntity>>(emptyList()) }
    var search by rememberSaveable(existing?.id) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    var initialAmount by rememberSaveable(expense.id, existing?.id) { mutableStateOf(amount) }
    var initialPayer by rememberSaveable(expense.id, existing?.id) { mutableStateOf(payer) }
    var initialized by rememberSaveable(expense.id, existing?.id) { mutableStateOf(false) }
    val initialDate = rememberSaveable(existing?.id) { date }
    val dirty = amount != initialAmount || payer != initialPayer ||
        incomingId != existing?.incomingId || method != (existing?.method ?: "Linked") ||
        dateKnown != (existing == null || existing.receivedAt != null) || dateKnown && date != initialDate || memberId != (existing?.memberId ?: initialMemberId)
    val close = { if (!busy) { if (dirty) discard = true else onDismiss() }; Unit }
    LaunchedEffect(expense.id) {
        try {
            credits = repository.availableRepaymentCredits()
            shares = repository.groupSharesFor(expense.id); members = repository.localMembers(); repayments = repository.repaymentsFor(expense.id)
            if (!initialized) {
                if (memberId != null) {
                    payer = members.firstOrNull { it.id == memberId }?.name ?: payer
                    if (existing == null && amount.isBlank()) shares.firstOrNull { it.memberId == memberId }?.let {
                        amount = shareDecimal(GroupSplitAccounting.owed(it, repayments))
                    }
                }
                initialAmount = amount; initialPayer = payer; initialized = true
            }
            loaded = true
        }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load incoming payments. Close and retry." }
    }
    val pickerTheme = if (androidx.compose.foundation.isSystemInDarkTheme()) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    val leavePicker = { focus.clearFocus(); choosingPayment = false; search = "" }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val personName = members.firstOrNull { it.id == memberId }?.name
    val remainingForPerson = shares.firstOrNull { it.memberId == memberId }?.let { share ->
        GroupSplitAccounting.owed(share, repayments.filter { it.id != existing?.id })
    }
    ModalBottomSheet(onDismissRequest = {
        // Material hides before this callback. Restore it when navigating within the
        // editor, or while a save/discard decision still owns the draft.
        if (choosingPayment) {
            leavePicker()
            scope.launch { sheetState.show() }
        } else {
            close()
            if (busy || dirty) scope.launch { sheetState.show() }
        }
    }, sheetState = sheetState, sheetGesturesEnabled = false, dragHandle = null,
        modifier = Modifier.imePadding()) {
      Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
        if (choosingPayment) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose a recorded payment", style = MaterialTheme.typography.titleLarge)
                Text("Use money already received. It won't be counted twice.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(search, { search = it.take(60) }, label = { Text("Search recorded payments") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            val matches = credits.filter { (row, _) -> search.isBlank() || row.counterpartyLabel.orEmpty().contains(search.trim(), ignoreCase = true) }
                .sortedByDescending { it.first.effectiveTimestamp }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                if (!loaded) item { Text(if (error == null) "Loading recorded payments…" else "Could not load payments. Close and retry.") }
                else if (matches.isEmpty()) item { Text(if (credits.isEmpty()) "No recorded payments with money available. Record the incoming transaction first, or choose Received cash." else "No matching payments.") }
                items(matches, key = { it.first.id }) { (row, available) ->
                    Column(Modifier.fillMaxWidth().clickable {
                        incomingId = row.id; date = row.effectiveTimestamp; dateKnown = true; method = "Linked"; error = null; leavePicker()
                    }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.counterpartyLabel ?: "Incoming payment", style = MaterialTheme.typography.titleMedium)
                        Text("${money(available)} available · ${transactionTime(row.effectiveTimestamp)}", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            TextButton(onClick = leavePicker, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text("Back to repayment") }
        } else {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (existing == null) "Record money received" else "Edit repayment", style = MaterialTheme.typography.titleLarge)
            Text("${expense.counterpartyLabel ?: "Expense"}${expense.groupLabel?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
            Text("Still owed ${money(RepaymentAccounting.owed(expense))}", style = MaterialTheme.typography.titleMedium)
            if (shares.isNotEmpty()) {
                if (personName != null) Text("$personName · ${money(remainingForPerson)} available to settle", style = MaterialTheme.typography.titleMedium)
                if (existing == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shares.filter { GroupSplitAccounting.owed(it, repayments) > 0 }.forEach { share ->
                        val name = members.firstOrNull { it.id == share.memberId }?.name ?: "Local person"
                        FilterChip(memberId == share.memberId, {
                            memberId = share.memberId; payer = name
                            amount = shareDecimal(GroupSplitAccounting.owed(share, repayments)); error = null
                        }, label = { Text(name) }, enabled = !busy)
                    }
                }
                if (memberId == null) Text("Choose whose unpaid share this settles.", style = MaterialTheme.typography.bodySmall)
            }
            if (existing == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = method == "Linked", onClick = { choosingPayment = true; focus.clearFocus() }, label = { Text("Choose a recorded payment") }, enabled = !busy)
                FilterChip(selected = method == "Cash", onClick = { method = "Cash"; incomingId = null; error = null }, label = { Text("Received cash") }, enabled = !busy)
            }
            OutlinedTextField(amount, { amount = it; error = null }, label = { Text("Repayment amount (INR)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            if (shares.isEmpty()) OutlinedTextField(payer, { payer = it.take(40) }, label = { Text("Who paid you? (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            if (existing == null) {
                if (method == "Linked") {
                    val selected = credits.firstOrNull { it.first.id == incomingId }
                    if (selected != null) {
                        Text("Recorded payment: ${selected.first.counterpartyLabel ?: "Incoming payment"}", style = MaterialTheme.typography.bodyMedium)
                        Text("${money(selected.second)} available to use. No new money-in will be added.", style = MaterialTheme.typography.bodySmall)
                    } else Text("Choose the incoming payment that repays this expense.", style = MaterialTheme.typography.bodySmall)
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
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
          Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val remaining = try {
                val minor = ManualInput(amount, `in`.financeministry.app.core.model.Direction.Credit, date, `in`.financeministry.app.core.model.TransactionType.Other).amountMinor()
                val available = remainingForPerson ?: Math.addExact(RepaymentAccounting.owed(expense), existing?.amountMinor ?: 0)
                (available - minor).takeIf { minor <= available }
            } catch (_: IllegalArgumentException) { null } catch (_: ArithmeticException) { null }
            remaining?.let { Text("${personName?.let { name -> "$name · " }.orEmpty()}Still owed after saving: ${money(it)}", style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = loaded && !busy && (shares.isEmpty() || memberId != null) && (existing != null || method == "Cash" || incomingId != null), modifier = Modifier.weight(1f), onClick = {
                if (busy || existing == null && method != "Cash" && incomingId == null) return@Button
                busy = true
                scope.launch {
                    try {
                        if (existing != null) repository.editRepayment(existing.id, amount, date.takeIf { dateKnown }, payer)
                        else repository.recordRepayment(expense.id, amount, date, payer, incomingId.takeIf { method == "Linked" }, memberId)
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
        }
        }
      }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard repayment changes?") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Discard changes") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

package `in`.financeministry.app.feature

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionForm(repository: TransactionRepository, existing: TransactionEntity?, onDone: () -> Unit, onDirtyChange: (Boolean) -> Unit = {}, quickOnly: Boolean = false) {
    var compact by rememberSaveable(existing?.id, quickOnly) { mutableStateOf(quickOnly && existing != null) }
    val formatter = remember { DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT) }
    val draftId = rememberSaveable(existing?.id) { java.util.UUID.randomUUID().toString() }
    var amount by rememberSaveable(existing?.id) { mutableStateOf(existing?.amountMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "") }
    var direction by rememberSaveable(existing?.id) { mutableStateOf(existing?.direction ?: "Debit") }
    var status by rememberSaveable(existing?.id) { mutableStateOf(existing?.status ?: "Successful") }
    var type by rememberSaveable(existing?.id) { mutableStateOf(existing?.transactionType ?: "Other") }
    var channel by rememberSaveable(existing?.id) { mutableStateOf(existing?.channel ?: "CashManual") }
    val initialDate = rememberSaveable(existing?.id) {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(existing?.effectiveTimestamp ?: System.currentTimeMillis()), ZoneId.systemDefault()).format(formatter)
    }
    var date by rememberSaveable(existing?.id) { mutableStateOf(initialDate) }
    var label by rememberSaveable(existing?.id) { mutableStateOf(existing?.counterpartyLabel ?: "") }
    var notes by rememberSaveable(existing?.id) { mutableStateOf(existing?.userNotes ?: "") }
    var hint by rememberSaveable(existing?.id) { mutableStateOf(existing?.maskedAccountHint?.takeLast(4) ?: "") }
    var category by rememberSaveable(existing?.id) { mutableStateOf(existing?.category ?: "Other") }
    var ownership by rememberSaveable(existing?.id) { mutableStateOf(existing?.ownership ?: "Personal") }
    var groupLabel by rememberSaveable(existing?.id) { mutableStateOf(existing?.groupLabel ?: "") }
    var personalShare by rememberSaveable(existing?.id) { mutableStateOf(existing?.personalShareMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "") }
    var repaid by rememberSaveable(existing?.id) { mutableStateOf(existing?.repaidMinor?.takeIf { it > 0 }?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "") }
    var sourceId by rememberSaveable(existing?.id) { mutableStateOf(existing?.paymentSourceId) }
    var sources by remember { mutableStateOf<List<PaymentSourceEntity>>(emptyList()) }
    var optional by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val initialFields = rememberSaveable(existing?.id) { listOf(amount, direction, status, type, channel, date, label, notes, hint, category, ownership, groupLabel, personalShare, repaid, sourceId) }
    val dirty = initialFields != listOf(amount, direction, status, type, channel, date, label, notes, hint, category, ownership, groupLabel, personalShare, repaid, sourceId)
    SideEffect { onDirtyChange(dirty) }
    LaunchedEffect(existing?.id) { sources = repository.paymentSources() }
    val pickerTheme = if (androidx.compose.foundation.isSystemInDarkTheme()) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    var discard by remember { mutableStateOf(false) }
    val leave = { if (!busy) { if (dirty) discard = true else onDone() }; Unit }
    BackHandler { leave() }
    Column(Modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (compact) "Categorize transaction" else if (existing == null) "Add transaction" else "Edit / confirm transaction", style = MaterialTheme.typography.titleLarge)
        if (compact && existing != null) {
            Text("${`in`.financeministry.app.money(existing.amountMinor)} · ${existing.counterpartyLabel ?: friendly(existing.direction)}", style = MaterialTheme.typography.titleMedium)
            if (existing.reviewState == "NeedsReview") Text("Still needs review. Saving labels will not confirm the payment.", style = MaterialTheme.typography.bodySmall)
        }
        if (!compact) {
        Text(if (existing?.reviewState == "NeedsReview") "Check the details below. Unconfirmed transactions are excluded from totals." else "${friendly(existing?.sourceType ?: "Manual")} · Stored only on this device", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(amount, { amount = it }, label = { Text("Amount (INR)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        if (existing != null && amount.isBlank()) Text("Enter the transaction amount; it could not be identified.", color = MaterialTheme.colorScheme.error)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Debit", "Credit", "Transfer").forEach { option ->
                FilterChip(selected = direction == option, onClick = { direction = option }, label = { Text(friendly(option)) })
            }
        }
        if (direction == "Unknown") Text("Choose money out, money in, or transfer.", color = MaterialTheme.colorScheme.error)
        OutlinedTextField(label, { label = it.take(60) }, label = { Text("Label (optional)") }, supportingText = { Text("A short description, not personal or account details.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Choice("Category", category, transactionCategories) { category = it }
        Text("Who was this payment for?", style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Personal", "Family", "ForOther", "Group", "SelfTransfer").forEach { option ->
                FilterChip(selected = ownership == option, onClick = {
                    ownership = option
                    type = if (option == "SelfTransfer") "SelfTransfer" else if (type == "SelfTransfer") "Other" else type
                    if (option !in listOf("ForOther", "Group")) repaid = ""
                    if (option != "Group") { groupLabel = ""; personalShare = "" }
                }, label = { Text(friendly(option)) })
            }
        }
        if (ownership == "Group") {
            OutlinedTextField(groupLabel, { groupLabel = it.take(40) }, label = { Text("Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(personalShare, { personalShare = it }, label = { Text("Your share (INR)") }, supportingText = { Text("The rest is tracked as money others owe you.") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        }
        if (ownership == "ForOther" || ownership == "Group") {
            OutlinedTextField(repaid, { repaid = it }, label = { Text("Already repaid (INR, optional)") }, supportingText = { Text("Update this when people repay you.") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        }
        if (compact) TextButton(onClick = { compact = false }) { Text("Edit all details") }
        if (!compact) {
        val selectedDate = LocalDateTime.parse(date, formatter)
        TextButton(onClick = {
            android.app.DatePickerDialog(context, pickerTheme, { _, year, month, day ->
                date = LocalDateTime.parse(date, formatter).withYear(year).withMonth(1).withDayOfMonth(1).withMonth(month + 1).withDayOfMonth(day).format(formatter)
            }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).show()
        }) { Text("Date: ${selectedDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}") }
        TextButton(onClick = {
            android.app.TimePickerDialog(context, pickerTheme, { _, hour, minute -> date = LocalDateTime.parse(date, formatter).withHour(hour).withMinute(minute).format(formatter) }, selectedDate.hour, selectedDate.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
        }) { Text("Time: ${selectedDate.format(DateTimeFormatter.ofPattern("HH:mm"))}") }
        Choice("Payment method", channel, Channel.entries.filter { it !in listOf(Channel.Unknown, Channel.Other) }.map { it.name }) { chosen ->
            channel = chosen
            if (sources.none { it.id == sourceId && it.channel == chosen }) sourceId = null
        }
        if (sources.any { it.active && it.channel == channel } || sourceId != null) SourceChoice(sourceId, channel, sources) { sourceId = it }
        if (status == "Unknown") Choice("Payment status — please check", status, TransactionStatus.entries.map { it.name }) { status = it }
        if (type == "Unknown") Choice("Transaction type — please choose", type, TransactionType.entries.map { it.name }) {
            type = it
            ownership = if (it == "SelfTransfer") "SelfTransfer" else if (ownership == "SelfTransfer") "Personal" else ownership
        }
        TextButton(onClick = { optional = !optional }) { Text(if (optional) "Fewer details" else "More details") }
        if (optional) {
            if (type != "Unknown") Choice("Transaction type", type, TransactionType.entries.map { it.name }) {
                type = it
                ownership = if (it == "SelfTransfer") "SelfTransfer" else if (ownership == "SelfTransfer") "Personal" else ownership
            }
            if (status != "Unknown") Choice("Payment status", status, TransactionStatus.entries.map { it.name }) { status = it }
            OutlinedTextField(hint, { hint = it }, label = { Text("Account last 4 digits (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it.take(200) }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
            Text("Use short labels and notes. Do not paste SMS, OTPs, personal contact names, UPI IDs or full account numbers. Text stays encrypted on this device.", style = MaterialTheme.typography.bodySmall)
        }
        }
      }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!busy) {
                    if (compact && existing != null) {
                        busy = true
                        scope.launch {
                            try {
                                repository.classify(existing.id, category, SpendingOwnership.valueOf(ownership), groupLabel, personalShare, repaid)
                                onDone()
                            } catch (e: IllegalArgumentException) { error = e.message ?: "Check the fields." }
                            catch (_: Exception) { error = "Could not save. Check that the record still exists and retry." }
                            finally { busy = false }
                        }
                        return@Button
                    }
                    val input = try {
                        val editedTimestamp = LocalDateTime.parse(date, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val timestamp = if (existing != null && date == initialDate) existing.effectiveTimestamp else editedTimestamp
                        ManualInput(amount, Direction.valueOf(direction), timestamp,
                            TransactionType.valueOf(type), TransactionStatus.valueOf(status), Channel.valueOf(channel), label, notes, hint,
                            category, SpendingOwnership.valueOf(ownership), groupLabel, personalShare, repaid, sourceId).also { it.validate() }
                    } catch (e: IllegalArgumentException) { error = e.message ?: "Check the fields."; null }
                    catch (_: java.time.DateTimeException) { error = "Enter a valid date and time as yyyy-MM-dd HH:mm."; null }
                    if (input != null) { busy = true; scope.launch {
                        try { repository.save(input, existing?.id, draftId); onDone() }
                        catch (_: Exception) { error = "Could not save. Check that the record still exists and retry." }
                        finally { busy = false }
                    } }
                }
            }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "Saving…" else "Save transaction") }
            OutlinedButton(onClick = leave, enabled = !busy) { Text("Cancel") }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Leave without saving?") },
        text = { Text("Your changes have not been saved.") },
        confirmButton = { TextButton(onClick = { discard = false; onDone() }) { Text("Discard changes") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

@Composable private fun SourceChoice(value: String?, channel: String, sources: List<PaymentSourceEntity>, onChoose: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedSource = sources.firstOrNull { it.id == value }
    val selected = selectedSource?.let { "${it.nickname}${if (it.active) "" else " (inactive)"}" } ?: "Not set"
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Payment source: $selected") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Not set") }, onClick = { onChoose(null); expanded = false })
            sources.filter { it.active && it.channel == channel }.forEach { source -> DropdownMenuItem(text = { Text(source.nickname) }, onClick = { onChoose(source.id); expanded = false }) }
        }
    }
}

@Composable private fun Choice(label: String, value: String, options: List<String>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: ${friendly(value)}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(friendly(option)) }, onClick = { onChoose(option); expanded = false }) }
        }
    }
}

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
    var repaymentExpected by rememberSaveable(existing?.id) { mutableStateOf(existing?.repaymentExpected ?: true) }
    var rememberCategory by rememberSaveable(existing?.id) { mutableStateOf(false) }
    var rememberForSource by rememberSaveable(existing?.id) { mutableStateOf(false) }
    var sourceId by rememberSaveable(existing?.id) { mutableStateOf(existing?.paymentSourceId) }
    var sources by remember { mutableStateOf<List<PaymentSourceEntity>>(emptyList()) }
    var detailsExpanded by rememberSaveable(existing?.id, quickOnly) { mutableStateOf(existing != null && !quickOnly) }
    var optional by rememberSaveable(existing?.id, quickOnly) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val initialFields = rememberSaveable(existing?.id) { listOf(amount, direction, status, type, channel, date, label, notes, hint, category, ownership, groupLabel, personalShare, repaid, sourceId, repaymentExpected.toString()) }
    val dirty = rememberCategory || initialFields != listOf(amount, direction, status, type, channel, date, label, notes, hint, category, ownership, groupLabel, personalShare, repaid, sourceId, repaymentExpected.toString())
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
        OutlinedTextField(label, { label = it.take(60); if (label.trim().length < 2) rememberCategory = false }, label = { Text("Merchant or short label (optional)") }, supportingText = { Text("A short description, not personal or account details.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Choice("Category", category, transactionCategories) { category = it }
        if (existing?.categoryNeedsReview == true) Text("Conflicting rules matched this payment. Choose its category; the payment's financial status is unchanged.", style = MaterialTheme.typography.bodySmall)
        if (label.trim().length >= 2) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = rememberCategory, onCheckedChange = { rememberCategory = it })
                Text("Remember this category for future payments", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            if (rememberCategory) {
                Text("Exact merchant: ${label.trim()} → $category. Only the category is remembered, not who the payment was for.", style = MaterialTheme.typography.bodySmall)
                if (sources.any { it.id == sourceId && it.active }) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = rememberForSource, onCheckedChange = { rememberForSource = it })
                    Text("Only for ${sources.firstOrNull { it.id == sourceId }?.nickname}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("Who was this payment for?", style = MaterialTheme.typography.labelLarge)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Personal", "Family", "ForOther", "Group", "SelfTransfer").forEach { option ->
                FilterChip(selected = ownership == option, onClick = {
                    ownership = option
                    type = if (option == "SelfTransfer") "SelfTransfer" else if (type == "SelfTransfer") "Other" else type
                    if (option !in listOf("ForOther", "Group")) repaid = ""
                    if (option !in listOf("Group", "ForOther")) groupLabel = ""
                    if (option != "Group") personalShare = ""
                }, label = { Text(friendly(option)) })
            }
        }
        if (direction == "Debit" && ownership in listOf("ForOther", "Group")) {
            Text("Repayment expected?", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = repaymentExpected, onClick = { repaymentExpected = true }, label = { Text("Yes") })
                FilterChip(selected = !repaymentExpected, onClick = { repaymentExpected = false }, label = { Text("No · gift or treat") })
            }
            if (!repaymentExpected) Text("The full payment counts as your spending. No money is owed back.", style = MaterialTheme.typography.bodySmall)
        }
        Text(when {
            !repaymentExpected && ownership in listOf("ForOther", "Group") -> "Gift or treat · full amount counts as your spending."
            else -> when (ownership) {
            "Group" -> "Only your share counts as your spending. The remainder is owed by others."
            "ForOther" -> "This payment is tracked separately from your own spending."
            "SelfTransfer" -> "Transfers are excluded from spending totals."
            else -> "Full amount counts as your spending."
        } }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ownership in listOf("Group", "ForOther")) {
            OutlinedTextField(groupLabel, { groupLabel = it.take(40) }, label = { Text(if (ownership == "Group") "Group name" else "Local name (optional)") },
                supportingText = { Text("A local label only. No contacts are accessed or invited.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (ownership == "Group" && repaymentExpected) {
            OutlinedTextField(personalShare, { personalShare = it }, label = { Text("Your share (INR)") }, supportingText = { Text("The rest is tracked as money others owe you.") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        }
        if (direction == "Debit" && repaymentExpected && ownership in listOf("ForOther", "Group")) {
            Text(if (existing == null) "Save this expense, then record dated repayments from its details."
                else "Record or edit repayments in transaction details. Existing repayment links must be removed before changing financial details or the split.",
                style = MaterialTheme.typography.bodySmall)
            if (existing != null && existing.repaidMinor > 0) Text("Already received: ${`in`.financeministry.app.money(existing.repaidMinor)}", style = MaterialTheme.typography.bodySmall)
        }
        if (compact) TextButton(onClick = { compact = false }) { Text("Edit all details") }
        if (!compact) {
        if (!detailsExpanded) {
            TextButton(onClick = { detailsExpanded = true }) { Text("More details · date, source and notes") }
        } else {
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
                                repository.classify(existing.id, category, SpendingOwnership.valueOf(ownership), groupLabel, personalShare, repaid, repaymentExpected,
                                    if (rememberCategory) RememberCategoryRule(label, sourceId.takeIf { rememberForSource }) else null)
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
                            category, SpendingOwnership.valueOf(ownership), groupLabel, personalShare, repaid, sourceId, repaymentExpected).also { it.validate() }
                    } catch (e: IllegalArgumentException) { error = e.message ?: "Check the fields."; null }
                    catch (_: java.time.DateTimeException) { error = "Enter a valid date and time as yyyy-MM-dd HH:mm."; null }
                    if (input != null) { busy = true; scope.launch {
                        try { repository.save(input, existing?.id, draftId,
                            if (rememberCategory) RememberCategoryRule(label, sourceId.takeIf { rememberForSource }) else null); onDone() }
                        catch (e: IllegalArgumentException) { error = e.message ?: "Check the fields." }
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

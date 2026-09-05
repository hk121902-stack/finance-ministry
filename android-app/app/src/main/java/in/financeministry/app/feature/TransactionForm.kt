package `in`.financeministry.app.feature

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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

@Composable
fun TransactionForm(repository: TransactionRepository, existing: TransactionEntity?, onDone: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT) }
    val draftId = rememberSaveable(existing?.id) { java.util.UUID.randomUUID().toString() }
    var amount by rememberSaveable(existing?.id) { mutableStateOf(existing?.amountMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "") }
    var direction by rememberSaveable(existing?.id) { mutableStateOf(existing?.direction ?: "Debit") }
    var status by rememberSaveable(existing?.id) { mutableStateOf(existing?.status ?: "Successful") }
    var type by rememberSaveable(existing?.id) { mutableStateOf(existing?.transactionType ?: "Other") }
    var channel by rememberSaveable(existing?.id) { mutableStateOf(existing?.channel ?: "CashManual") }
    var date by rememberSaveable(existing?.id) { mutableStateOf(LocalDateTime.ofInstant(Instant.ofEpochMilli(existing?.effectiveTimestamp ?: System.currentTimeMillis()), ZoneId.systemDefault()).format(formatter)) }
    var label by rememberSaveable(existing?.id) { mutableStateOf(existing?.counterpartyLabel ?: "") }
    var notes by rememberSaveable(existing?.id) { mutableStateOf(existing?.userNotes ?: "") }
    var hint by rememberSaveable(existing?.id) { mutableStateOf(existing?.maskedAccountHint?.takeLast(4) ?: "") }
    var optional by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler { if (!busy) onDone() }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (existing == null) "Add transaction" else "Edit / confirm transaction", style = MaterialTheme.typography.titleLarge)
        Text("INR · ${existing?.sourceType ?: "Manual"}")
        OutlinedTextField(amount, { amount = it }, label = { Text("Amount (INR)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Choice("Direction", direction, Direction.entries.map { it.name }) { direction = it }
        OutlinedTextField(date, { date = it }, label = { Text("Date / time (yyyy-MM-dd HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Choice("Category", type, TransactionType.entries.map { it.name }) { type = it }
        Choice("Status", status, TransactionStatus.entries.map { it.name }) { status = it }
        TextButton(onClick = { optional = !optional }) { Text(if (optional) "Hide optional fields" else "Optional fields") }
        if (optional) {
            Choice("Channel", channel, Channel.entries.map { it.name }) { channel = it }
            OutlinedTextField(label, { label = it.take(60) }, label = { Text("Label (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(hint, { hint = it }, label = { Text("Account last 4 digits (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it.take(200) }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
            Text("Use short labels and notes. Do not paste SMS, OTPs, personal contact names, UPI IDs or full account numbers. Text stays encrypted on this device.", style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!busy) {
                    val input = try {
                        ManualInput(amount, Direction.valueOf(direction), LocalDateTime.parse(date, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            TransactionType.valueOf(type), TransactionStatus.valueOf(status), Channel.valueOf(channel), label, notes, hint).also { it.validate() }
                    } catch (e: IllegalArgumentException) { error = e.message ?: "Check the fields."; null }
                    catch (_: java.time.DateTimeException) { error = "Enter a valid date and time as yyyy-MM-dd HH:mm."; null }
                    if (input != null) { busy = true; scope.launch {
                        try { repository.save(input, existing?.id, draftId); onDone() }
                        catch (_: Exception) { error = "Could not save. Check that the record still exists and retry." }
                        finally { busy = false }
                    } }
                }
            }, enabled = !busy) { Text(if (busy) "Saving…" else "Save transaction") }
            OutlinedButton(onClick = onDone, enabled = !busy) { Text("Cancel") }
        }
    }
}

@Composable private fun Choice(label: String, value: String, options: List<String>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: $value") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChoose(option); expanded = false }) }
        }
    }
}

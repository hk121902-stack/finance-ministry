package `in`.financeministry.app.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.core.model.transactionCategories
import `in`.financeministry.app.data.*
import `in`.financeministry.app.money
import `in`.financeministry.app.transactionTime
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptionalToolsPanel(repository: TransactionRepository, month: LocalDate, initialTab: String = "Budgets",
    onAddPayment: (RecurringReminderEntity) -> Unit) {
    val revision by repository.revision.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    var budgets by remember { mutableStateOf<List<BudgetStatus>>(emptyList()) }
    var reminders by remember { mutableStateOf<List<RecurringReminderEntity>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editBudget by remember { mutableStateOf(false) }
    var editReminder by remember { mutableStateOf(false) }
    var linkReminder by remember { mutableStateOf<RecurringReminderEntity?>(null) }
    var candidates by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }
    var selectedCandidate by remember { mutableStateOf<String?>(null) }
    var confirmSummary by remember { mutableStateOf(false) }
    var widgetSummary by remember { mutableStateOf(repository.preferences.getBoolean("widget_summary", false)) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(revision, month) {
        try {
            budgets = repository.budgetStatuses(month)
            reminders = repository.recurringReminders()
        } catch (_: Exception) { error = "Could not load optional tools. Your ledger is unchanged." }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Budgets", "Payment reminders", "Home-screen widget").forEach { option ->
            FilterChip(selected = tab == option, onClick = { tab = option; error = null }, label = { Text(option) })
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    when (tab) {
        "Budgets" -> {
            Text("Monthly category budgets", style = MaterialTheme.typography.titleMedium)
            Text("Uses your spending share, not gross group payments or an account balance.", style = MaterialTheme.typography.bodySmall)
            budgets.forEach { status ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(status.budget.category, style = MaterialTheme.typography.titleMedium)
                            Text(money(status.budget.monthlyLimitMinor))
                        }
                        Text("${money(status.progress.remainingMinor)} left · ${money(status.spentMinor)} used")
                        LinearProgressIndicator({ (status.progress.percentUsed / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        Text("${status.progress.percentUsed}% used${status.budget.alertPercent?.let { " · Alert at $it%" } ?: " · Alerts off"}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { scope.launch { repository.deleteBudget(status.budget.id) } }, enabled = !busy) { Text("Remove budget") }
                    }
                }
            }
            Button(onClick = { editBudget = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Add budget") }
        }
        "Payment reminders" -> {
            Text("Your recurring payment reminders", style = MaterialTheme.typography.titleMedium)
            Text("Nothing is paid or recorded automatically.", style = MaterialTheme.typography.bodySmall)
            reminders.forEach { reminder ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(reminder.title, style = MaterialTheme.typography.titleMedium)
                            reminder.amountMinor?.let { Text(money(it)) }
                        }
                        Text("Day ${reminder.preferredDay} each month · ${reminder.category}", style = MaterialTheme.typography.bodySmall)
                        Text(reminder.lastLinkedAt?.let { "Last linked ${transactionTime(it)}" } ?: "No payment linked yet", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                busy = true; scope.launch {
                                    try { candidates = repository.recurringPaymentCandidates(reminder.id); selectedCandidate = null; linkReminder = reminder }
                                    catch (failure: Exception) { error = failure.message }
                                    finally { busy = false }
                                }
                            }, enabled = !busy && reminder.active) { Text("Mark paid") }
                            TextButton(onClick = { onAddPayment(reminder) }, enabled = !busy && reminder.active) { Text("Add payment") }
                            TextButton(onClick = { scope.launch { repository.setRecurringReminderActive(reminder.id, !reminder.active) } }, enabled = !busy) {
                                Text(if (reminder.active) "Pause" else "Resume")
                            }
                        }
                    }
                }
            }
            Button(onClick = { editReminder = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Add reminder") }
        }
        else -> {
            Text("Private home-screen widget", style = MaterialTheme.typography.titleMedium)
            Text("Quick add shows no amounts by default.")
            Text("Add the Finance Ministry widget from your launcher. Tapping it opens Add transaction.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Show monthly spending summary")
                    Text("Optional · hidden while the device is locked · includes last-updated time", style = MaterialTheme.typography.bodySmall)
                }
                Switch(widgetSummary, onCheckedChange = { checked ->
                    if (checked) confirmSummary = true else {
                        widgetSummary = false; repository.preferences.edit().putBoolean("widget_summary", false).commit()
                        `in`.financeministry.app.widget.FinanceWidget.updateAll(context)
                    }
                }, modifier = Modifier.testTag("widget-summary-toggle"))
            }
        }
    }
    if (editBudget) BudgetEditor(onDismiss = { editBudget = false }, onSave = { category, amount, alert ->
        busy = true; scope.launch {
            try { repository.saveBudget(category, amount, alert); editBudget = false }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    })
    if (editReminder) ReminderEditor(onDismiss = { editReminder = false }, onSave = { title, amount, day, category ->
        busy = true; scope.launch {
            try { repository.saveRecurringReminder(title, amount, day, category); editReminder = false }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    })
    linkReminder?.let { reminder ->
        AlertDialog(onDismissRequest = { if (!busy) linkReminder = null }, title = { Text("Link an existing payment") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Linking marks this reminder paid. It does not add or change a transaction.", style = MaterialTheme.typography.bodySmall)
                if (candidates.isEmpty()) Text("No matching confirmed money-out transaction found. Use Add payment instead.")
                candidates.forEach { row ->
                    FilterChip(selected = selectedCandidate == row.id, onClick = { selectedCandidate = row.id },
                        label = { Text("${row.counterpartyLabel ?: "Payment"} · ${money(row.amountMinor)}") })
                }
            } },
            confirmButton = { TextButton(enabled = !busy && selectedCandidate != null, onClick = {
                val id = requireNotNull(selectedCandidate); busy = true; scope.launch {
                    try { repository.linkRecurringPayment(reminder.id, id); linkReminder = null }
                    catch (failure: Exception) { error = failure.message }
                    finally { busy = false }
                }
            }) { Text("Link this payment") } }, dismissButton = { TextButton(onClick = { linkReminder = null }, enabled = !busy) { Text("Cancel") } })
    }
    if (confirmSummary) AlertDialog(onDismissRequest = { confirmSummary = false },
        title = { Text("Show financial amounts on your home screen?") },
        text = { Text("Anyone who can see your unlocked home screen may see your monthly spending. The widget hides the amount while locked and shows when it was last refreshed.") },
        confirmButton = { TextButton(onClick = {
            widgetSummary = true; repository.preferences.edit().putBoolean("widget_summary", true).commit(); confirmSummary = false
            `in`.financeministry.app.widget.FinanceWidget.updateAll(context)
        }) { Text("Allow summary") } }, dismissButton = { TextButton(onClick = { confirmSummary = false }) { Text("Keep private") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun BudgetEditor(onDismiss: () -> Unit, onSave: (String, String, Int?) -> Unit) {
    var category by rememberSaveable { mutableStateOf("Other") }
    var amount by rememberSaveable { mutableStateOf("") }
    var alert by rememberSaveable { mutableStateOf<Int?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Category budget") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Category")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { transactionCategories.forEach { option ->
                FilterChip(category == option, { category = option }, { Text(option) }, modifier = Modifier.testTag("budget-category-$option"))
            } }
            OutlinedTextField(amount, { amount = it }, label = { Text("Monthly amount (INR)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("budget-amount"))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(alert == 80, { alert = if (it) 80 else null })
                Text("Alert me at 80%")
            }
            Text("An alert is optional. A budget is a spending target, not money in your account.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { onSave(category, amount, alert) }) { Text("Save budget") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ReminderEditor(onDismiss: () -> Unit, onSave: (String, String, Int, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf("1") }
    var category by rememberSaveable { mutableStateOf("Other") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Payment reminder") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it.take(40) }, label = { Text("Reminder name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("reminder-title"))
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("reminder-amount"))
            OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, label = { Text("Day of month") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("reminder-day"))
            Text("Category")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { transactionCategories.forEach { option ->
                FilterChip(category == option, { category = option }, { Text(option) }, modifier = Modifier.testTag("reminder-category-$option"))
            } }
            Text("This reminder never initiates a payment or records a transaction automatically.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { onSave(title, amount, day.toIntOrNull() ?: 0, category) }) { Text("Save reminder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

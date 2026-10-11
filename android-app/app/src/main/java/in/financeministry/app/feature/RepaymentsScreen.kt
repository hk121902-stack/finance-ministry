package `in`.financeministry.app.feature

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.data.*
import `in`.financeministry.app.money
import `in`.financeministry.app.transactionTime
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RepaymentsScreen(repository: TransactionRepository, month: LocalDate, onBack: () -> Unit,
    onExpense: (String) -> Unit) {
    val revision by repository.revision.collectAsState()
    var summary by remember { mutableStateOf<RepaymentSummary?>(null) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var settled by rememberSaveable { mutableStateOf(false) }
    var monthOnly by rememberSaveable { mutableStateOf(false) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingId by rememberSaveable { mutableStateOf<String?>(null) }
    var recordingMemberId by rememberSaveable { mutableStateOf<String?>(null) }
    var personId by rememberSaveable { mutableStateOf<String?>(null) }
    var peopleView by rememberSaveable { mutableStateOf(true) }
    var monthDetails by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(month, revision, retry) {
        error = false
        try { summary = repository.repaymentSummary(month) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    fun groupKey(row: TransactionEntity) = row.ownership + ":" + row.groupLabel.orEmpty().trim().lowercase(Locale.ROOT)
    val leave = { if (personId != null) personId = null else if (group != null) group = null else onBack() }
    BackHandler(onBack = leave)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().fillMaxSize().padding(horizontal = 20.dp)) {
            TextButton(onClick = leave) { Text("Back") }
            Text("Money owed to you", style = MaterialTheme.typography.headlineMedium)
            if (error) {
                Text("Could not load repayments. Your ledger has not changed.")
                TextButton(onClick = { retry++ }) { Text("Try again") }
            } else if (summary == null) CircularProgressIndicator(Modifier.padding(24.dp))
            else {
                val data = summary!!
                val monthName = month.format(DateTimeFormatter.ofPattern("MMMM uuuu"))
                val expenses = data.expenses.filter {
                    (RepaymentAccounting.owed(it) == 0L) == settled && (!monthOnly ||
                        Instant.ofEpochMilli(it.effectiveTimestamp).atZone(ZoneId.systemDefault()).toLocalDate().withDayOfMonth(1) == month.withDayOfMonth(1))
                }
                val groups = expenses.groupBy(::groupKey).toSortedMap()
                val periodExpenses = data.expenses.filter { !monthOnly || Instant.ofEpochMilli(it.effectiveTimestamp).atZone(ZoneId.systemDefault()).toLocalDate().withDayOfMonth(1) == month.withDayOfMonth(1) }
                val periodIds = periodExpenses.map { it.id }.toSet()
                val personShares = data.shares.filter { it.expenseId in periodIds && (GroupSplitAccounting.owed(it, data.repayments) == 0L) == settled }
                val byPerson = personShares.groupBy { it.memberId }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (group == null && personId == null) {
                        item {
                            Text(money(data.outstanding), style = MaterialTheme.typography.headlineLarge)
                            Text("Still owed · all dates", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(monthName, style = MaterialTheme.typography.titleMedium)
                                    RepaymentMetric("Still unpaid from this month's payments", data.monthOutstanding)
                                    TextButton(onClick = { monthDetails = !monthDetails }) { Text(if (monthDetails) "Hide month breakdown" else "Month breakdown") }
                                    if (monthDetails) {
                                        RepaymentMetric("Newly owed from this month's payments", data.newlyOwed)
                                        RepaymentMetric("Received back during this month", data.received)
                                        Text("Receipts this month may settle older expenses. Undated legacy repayments are not counted as monthly receipts.",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = !settled, onClick = { settled = false }, label = { Text("Outstanding") })
                                FilterChip(selected = settled, onClick = { settled = true }, label = { Text("Settled") })
                            }
                            Row(Modifier.fillMaxWidth().clickable { monthOnly = !monthOnly }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(checked = monthOnly, onCheckedChange = { monthOnly = it })
                                Text("Only payments made in $monthName", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            }
                            Text("Names are local labels only; no contacts are accessed or invited.", style = MaterialTheme.typography.bodySmall)
                            if (data.shares.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(peopleView, { peopleView = true }, label = { Text("By person") })
                                FilterChip(!peopleView, { peopleView = false }, label = { Text("By group") })
                            }
                        }
                        if (peopleView) items(byPerson.keys.sortedBy { id -> data.members.firstOrNull { it.id == id }?.name }, key = { "person:$it" }) { id ->
                            val name = data.members.firstOrNull { it.id == id }?.name ?: "Local person"
                            val owed = byPerson.getValue(id).fold(BigInteger.ZERO) { n, share -> n + BigInteger.valueOf(GroupSplitAccounting.owed(share, data.repayments)) }
                            Column(Modifier.fillMaxWidth().clickable { personId = id }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Text(if (owed.signum() == 0) "Settled" else "Owes ${money(owed)}", style = MaterialTheme.typography.titleMedium)
                                }
                                val count = byPerson.getValue(id).size
                                Text("$count shared expense${if (count == 1) "" else "s"} · View balances & repayments", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                            }
                        }
                        if (groups.isEmpty() && (!peopleView || byPerson.isEmpty())) item { Text(if (settled) "No settled expenses in this view." else "Nothing outstanding in this view.") }
                        val visibleGroups = if (!peopleView) groups else groups.mapValues { (_, rows) -> rows.filter { row -> data.shares.none { it.expenseId == row.id } } }.filterValues { it.isNotEmpty() }
                        if (peopleView && visibleGroups.isNotEmpty() && data.shares.isNotEmpty()) item { Text("Unassigned to people", style = MaterialTheme.typography.titleMedium) }
                        items(visibleGroups.keys.toList(), key = { it }) { key ->
                            val records = visibleGroups.getValue(key)
                            Column(Modifier.fillMaxWidth().clickable { group = key }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(records.first().groupLabel?.takeIf { it.isNotBlank() } ?: "Unlabelled", style = MaterialTheme.typography.titleMedium)
                                Text("${records.size} expense${if (records.size == 1) "" else "s"} · ${friendly(records.first().ownership)}",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(money(records.fold(BigInteger.ZERO) { n, row -> n + BigInteger.valueOf(RepaymentAccounting.owed(row)) }),
                                    style = MaterialTheme.typography.titleMedium)
                                HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                            }
                        }
                    } else if (personId != null) {
                        val name = data.members.firstOrNull { it.id == personId }?.name ?: "Local person"
                        val shares = byPerson[personId].orEmpty()
                        item {
                            Text(name, style = MaterialTheme.typography.titleLarge)
                            Text(if (monthOnly) "Shares from $monthName payments" else "Shares across all dates", style = MaterialTheme.typography.bodySmall)
                        }
                        if (shares.isEmpty()) item { Text("Nothing remains in this view. Repayment history is kept in each expense.") }
                        items(shares, key = { it.id }) { share ->
                            val row = data.expenses.first { it.id == share.expenseId }
                            val owed = GroupSplitAccounting.owed(share, data.repayments)
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.counterpartyLabel ?: "Expense", style = MaterialTheme.typography.titleMedium)
                                Text("${row.groupLabel.orEmpty()} · ${transactionTime(row.effectiveTimestamp)}", style = MaterialTheme.typography.bodySmall)
                                Text("Share ${money(share.amountMinor)} · Still owed ${money(owed)}", style = MaterialTheme.typography.bodyMedium)
                                if (owed > 0) Button(onClick = { recordingId = row.id; recordingMemberId = share.memberId }) { Text("Record money received") }
                                TextButton(onClick = { onExpense(row.id) }) { Text("Expense & repayment history") }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                            }
                        }
                    } else {
                        val records = groups[group].orEmpty()
                        item {
                            Text(records.firstOrNull()?.groupLabel ?: "Expenses", style = MaterialTheme.typography.titleLarge)
                            Text(if (monthOnly) "Payments made in $monthName" else "Expenses across all dates", style = MaterialTheme.typography.bodySmall)
                        }
                        if (records.isEmpty()) item { Text("No expenses remain in this view.") }
                        items(records, key = { it.id }) { row ->
                            Column(Modifier.fillMaxWidth().clickable { onExpense(row.id) }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.counterpartyLabel ?: "Expense", style = MaterialTheme.typography.titleMedium)
                                Text("Still owed ${money(RepaymentAccounting.owed(row))}")
                                Text("Paid ${money(row.amountMinor)} · Your share ${money(RepaymentAccounting.personal(row))}", style = MaterialTheme.typography.bodySmall)
                                Text(transactionTime(row.effectiveTimestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (RepaymentAccounting.owed(row) > 0) Button(onClick = { recordingId = row.id; recordingMemberId = null }) { Text("Record money received") }
                                HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                            }
                        }
                    }
                }
                data.expenses.firstOrNull { it.id == recordingId }?.let { expense ->
                    RepaymentEditor(repository, expense, null, onDismiss = { recordingId = null; recordingMemberId = null }, initialMemberId = recordingMemberId)
                }
            }
        }
    }
}

@Composable private fun RepaymentMetric(label: String, amount: BigInteger) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(money(amount), style = MaterialTheme.typography.titleMedium)
    }
}

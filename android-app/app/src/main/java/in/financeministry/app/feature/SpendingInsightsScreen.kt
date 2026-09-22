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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.data.*
import `in`.financeministry.app.money
import `in`.financeministry.app.transactionTime
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun SpendingInsightsScreen(repository: TransactionRepository, month: LocalDate, onBack: () -> Unit,
    onTransaction: (String) -> Unit, onReview: () -> Unit, onBudget: () -> Unit) {
    val revision by repository.revision.collectAsState()
    var insight by remember { mutableStateOf<SpendingInsight?>(null) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(month, revision, retry) {
        error = false
        try { insight = repository.spendingInsights(month) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
    }
    val goBack = { if (category != null) category = null else onBack() }
    BackHandler(onBack = goBack)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().fillMaxSize().padding(horizontal = 20.dp)) {
            TextButton(onClick = goBack) { Text("Back") }
            Text(if (category == null) "Spending breakdown" else "Transactions", style = MaterialTheme.typography.headlineMedium)
            if (error) {
                Text("Could not load spending. Your ledger has not changed.")
                TextButton(onClick = { retry++ }) { Text("Try again") }
            } else if (insight == null) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            } else {
                val data = insight!!
                val selectedCategory = data.categories.firstOrNull { it.category == category }
                val amount = if (category == null) data.total else selectedCategory?.amount ?: BigInteger.ZERO
                val records = if (category == null) emptyList() else data.transactions.filter { it.category == category }
                val date = DateTimeFormatter.ofPattern("d MMM")
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM uuuu")), style = MaterialTheme.typography.titleMedium)
                        Text("${data.firstDay.format(date)}–${data.lastDay.format(date)}${category?.let { " · $it" }.orEmpty()}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Text(money(amount), style = MaterialTheme.typography.headlineLarge)
                        Text("Your share of spending", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (category == null) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (data.canCompare) {
                                        val difference = data.total - data.previousTotal
                                        Text("${money(difference.abs())} ${if (difference.signum() >= 0) "more" else "less"} than ${data.previousFirstDay.format(date)}–${data.previousLastDay.format(date)}",
                                            style = MaterialTheme.typography.titleSmall)
                                        val percent = if (data.previousTotal.signum() > 0) BigDecimal(difference).multiply(BigDecimal(100))
                                            .divide(BigDecimal(data.previousTotal), 1, RoundingMode.HALF_UP).toPlainString() + "% · " else ""
                                        Text("${percent}Prior period ${money(data.previousTotal)}", style = MaterialTheme.typography.bodySmall)
                                    } else Text("Not enough recorded history to compare.")
                                    Text("Based on recorded transactions; history may be incomplete.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (data.categories.isEmpty()) item { Text("No confirmed spending in this period.") }
                        items(data.categories, key = { it.category }) { item ->
                            Column(Modifier.fillMaxWidth().clickable { category = item.category }
                                .semantics { contentDescription = "Open ${item.category} spending" }.padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(item.category, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Text(money(item.amount), style = MaterialTheme.typography.titleMedium)
                                }
                                LinearProgressIndicator(progress = { if (data.total.signum() == 0) 0f else
                                    BigDecimal(item.amount).divide(BigDecimal(data.total), 5, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(3.dp))
                                if (item.category == "Other") Text("Review uncategorized payments →", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        item {
                            Text("Refunds received · ${money(data.refundsReceived)}", style = MaterialTheme.typography.bodySmall)
                            Text("Shown separately; refunds do not reduce this spending figure.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Personal, family and gifts count fully; group payments count only your share.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = onBudget, modifier = Modifier.fillMaxWidth()) { Text("Set a category budget") }
                            TextButton(onClick = onReview) { Text("Review payments excluded from totals") }
                        }
                    } else {
                        item {
                            val gross = records.fold(BigInteger.ZERO) { n, row -> n + BigInteger.valueOf(row.amountMinor ?: 0) }
                            Text("Money out ${money(gross)}", style = MaterialTheme.typography.titleSmall)
                            Text("Paid amount can differ from your share.", style = MaterialTheme.typography.bodySmall)
                            Text("Confirmed spending only · ${records.size} transactions", style = MaterialTheme.typography.bodySmall)
                        }
                        if (records.isEmpty()) item { Text("No spending in this category for the selected period.") }
                        items(records.sortedByDescending { it.effectiveTimestamp }, key = { it.id }) { row ->
                            Column(Modifier.fillMaxWidth().clickable { onTransaction(row.id) }.padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.counterpartyLabel ?: "Payment", style = MaterialTheme.typography.titleMedium)
                                Text("Your share ${money(RepaymentAccounting.personal(row))} · Paid ${money(row.amountMinor)}",
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(transactionTime(row.effectiveTimestamp), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

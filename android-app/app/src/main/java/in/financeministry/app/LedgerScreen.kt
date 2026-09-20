package `in`.financeministry.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.data.*
import `in`.financeministry.app.core.model.transactionCategories
import `in`.financeministry.app.feature.TransactionForm
import `in`.financeministry.app.feature.friendly
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.BigInteger

private fun groupedIndian(digits: String): String {
    if (digits.length <= 3) return digits
    val tail = digits.takeLast(3)
    val head = digits.dropLast(3)
    return head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
}
fun money(minor: Long?): String = minor?.let { money(BigInteger.valueOf(it)) } ?: "Amount unknown"
fun money(minor: BigInteger): String {
    val negative = minor.signum() < 0
    val absolute = minor.abs()
    val units = absolute.divide(BigInteger.valueOf(100)).toString()
    val paise = absolute.mod(BigInteger.valueOf(100)).toString().padStart(2, '0')
    return "${if (negative) "-" else ""}₹${groupedIndian(units)}.$paise"
}
fun transactionTime(millis: Long): String = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LedgerApp(repository: TransactionRepository, request: Pair<String, Boolean>?, refreshGeneration: Int = 0,
    reviewRequestGeneration: Int = 0, quickRequest: Boolean = false, consumeRequest: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsState()
    var snapshot by remember { mutableStateOf<LedgerSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var form by rememberSaveable { mutableStateOf(false) }
    var quickForm by rememberSaveable { mutableStateOf(false) }
    var dirtyForm by rememberSaveable { mutableStateOf(false) }
    var requestWarning by remember { mutableStateOf(false) }
    var requestApproval by remember { mutableIntStateOf(0) }
    var approvedRequest by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var selected by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var disclosure by remember { mutableStateOf(false) }
    var eraseDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(repository.preferences.getBoolean("notifications", true)) }
    var captureEnabled by remember { mutableStateOf(repository.captureAllowed()) }
    var notificationAvailable by remember { mutableStateOf(`in`.financeministry.app.sms.TransactionNotifications.available(context)) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var destination by rememberSaveable { mutableStateOf("Overview") }
    var search by rememberSaveable { mutableStateOf("") }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewAvailable by remember { mutableStateOf(false) }
    var reviewCount by remember { mutableIntStateOf(0) }
    var selectedMonth by rememberSaveable { mutableStateOf(java.time.YearMonth.now().toString()) }
    var showGuide by remember { mutableStateOf(!repository.preferences.getBoolean("onboarding_complete", false)) }
    var paymentSources by remember { mutableStateOf(emptyList<PaymentSourceEntity>()) }
    var showSummaryDetails by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var reviewWarning by remember { mutableStateOf(false) }
    var draftPurposeFilter by remember { mutableStateOf("All") }
    var draftOriginFilter by remember { mutableStateOf("All") }
    var draftDirectionFilter by remember { mutableStateOf("All") }
    var draftCategories by remember { mutableStateOf(emptyList<String>()) }
    var draftSourceIds by remember { mutableStateOf(emptyList<String>()) }
    var preferredName by remember { mutableStateOf(repository.preferences.getString("preferred_name", "").orEmpty()) }
    var preferredNameDraft by rememberSaveable { mutableStateOf(preferredName) }
    val ledgerListState = rememberLazyListState()
    BackHandler(enabled = settings && !form && selectedId == null) {
        if (settingsSection != null) settingsSection = null else settings = false
    }
    BackHandler(enabled = !settings && !form && selectedId == null && destination != "Overview") {
        destination = "Overview"; offset = 0; snapshot = null
    }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { captureEnabled = repository.captureAllowed() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAvailable = `in`.financeministry.app.sms.TransactionNotifications.available(context)
    }
    LaunchedEffect(refreshGeneration) {
        captureEnabled = repository.captureAllowed()
        notifications = repository.preferences.getBoolean("notifications", true)
        notificationAvailable = `in`.financeministry.app.sms.TransactionNotifications.available(context)
    }
    BackHandler(enabled = selected != null && !form) { if (!busy) { selected = null; selectedId = null } }
    LaunchedEffect(selectedId) {
        if (selectedId != null && selected?.id != selectedId) {
            try { selected = repository.get(selectedId!!); if (selected == null) { selectedId = null; form = false; error = "This transaction no longer exists." } }
            catch (_: Exception) { error = "Cannot open this transaction."; selectedId = null; form = false }
        }
    }
    LaunchedEffect(revision, offset, filter, destination, search, refreshGeneration, selectedMonth) {
        loading = true
        try {
            val monthSummary = repository.snapshot(0, "All", java.time.YearMonth.parse(selectedMonth).atDay(1), LocalDate.now())
            val result = if (destination == "Review") repository.reviewQueue(offset).copy(
                debit = monthSummary.debit, credit = monthSummary.credit,
                dailyDebit = monthSummary.dailyDebit, dailyCredit = monthSummary.dailyCredit,
                personalSpend = monthSummary.personalSpend, paidForOthers = monthSummary.paidForOthers,
                outstandingRepayments = monthSummary.outstandingRepayments,
                selectedMonth = monthSummary.selectedMonth)
                else repository.snapshot(if (destination == "Overview") 0 else offset,
                    if (destination == "Overview") "All" else filter,
                    java.time.YearMonth.parse(selectedMonth).atDay(1), LocalDate.now(),
                    if (destination == "Transactions") search else "")
            snapshot = result
            paymentSources = repository.paymentSources()
            reviewCount = repository.reviewCount()
            reviewAvailable = reviewCount > 0
            if (result.rows.isEmpty() && offset > 0) offset = maxOf(0, offset - 100)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Cannot open encrypted storage. Existing data was preserved. Erase all only if you intend to delete it." }
        finally { loading = false }
    }
    LaunchedEffect(reviewRequestGeneration) {
        if (reviewRequestGeneration > 0) {
            if (form && dirtyForm) reviewWarning = true
            else {
                settings = false; settingsSection = null; selected = null; selectedId = null; form = false
                destination = "Review"; offset = 0; snapshot = null
            }
        }
    }
    LaunchedEffect(selectedMonth) { ledgerListState.scrollToItem(0) }
    LaunchedEffect(request, requestApproval) {
        if (request != null) {
            if (form && dirtyForm && approvedRequest != request) { requestWarning = true; return@LaunchedEffect }
            approvedRequest = null
            dirtyForm = false
            quickForm = quickRequest
            selected = null; selectedId = null; form = false; settings = false; destination = "Overview"
            try { selected = repository.get(request.first); if (selected == null) error = "This transaction no longer exists." else { selectedId = selected!!.id; form = request.second; error = null } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Cannot open this transaction. Data was preserved." }
            consumeRequest()
        }
    }
    Surface(Modifier.fillMaxSize()) {
        val ledgerScroll = rememberScrollState()
        val pageModifier = Modifier.safeDrawingPadding().fillMaxSize().padding(16.dp)
        Column(if (settings || (selected != null && !form)) pageModifier.verticalScroll(ledgerScroll) else pageModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!form && selectedId == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val homeTitle = preferredName.trim().takeIf { it.isNotEmpty() }?.let { "Hi, $it" } ?: "Your overview"
                Text(if (settings) settingsSection ?: "Settings" else when (destination) {
                    "Overview" -> homeTitle
                    "Transactions" -> "Transactions"
                    else -> "Review"
                }, style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = {
                    if (settings && settingsSection != null) settingsSection = null
                    else if (settings) { settings = false; settingsSection = null; destination = "Overview" }
                    else { settings = true; settingsSection = null }
                }) { Text(if (settings && settingsSection != null) "Back" else if (settings) "Home" else "Settings") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (selectedId != null && selected == null) { Text("Loading transaction…") }
            else if (form) {
                TransactionForm(repository, selected, onDone = { dirtyForm = false; quickForm = false; form = false; selected = null; selectedId = null; offset = 0; error = null }, onDirtyChange = { dirtyForm = it }, quickOnly = quickForm)
            } else if (selected != null) {
                val row = selected!!
                Text(money(row.amountMinor), style = MaterialTheme.typography.headlineSmall)
                Text("${friendly(row.direction)} · ${friendly(row.status)}")
                Text("${friendly(row.transactionType)} · ${friendly(row.channel)} · ${friendly(row.sourceType)}")
                paymentSources.firstOrNull { it.id == row.paymentSourceId }?.let { Text("Payment source: ${it.nickname}${if (it.active) "" else " (inactive)"}", style = MaterialTheme.typography.bodySmall) }
                if (row.paymentSourceId == null && paymentSources.any { it.active && it.channel == row.channel })
                    TextButton(onClick = { showSourcePicker = true }) { Text("Choose payment source") }
                transactionLabels(row)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                row.groupLabel?.let { Text("Group: $it", style = MaterialTheme.typography.bodySmall) }
                repaymentSummary(row, snapshot?.reversedOriginalIds.orEmpty())?.let { (label, owed) ->
                    Text("$label: ${money(owed)}", style = MaterialTheme.typography.bodySmall)
                }
                if (row.reviewState == "NeedsReview") Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Check this transaction", style = MaterialTheme.typography.titleMedium)
                        Text(when {
                            row.amountMinor == null -> "We couldn’t identify a reliable amount. Enter it to finish recording."
                            row.direction == "Unknown" -> "Choose whether money went out, came in, or moved between accounts."
                            row.status == "Unknown" -> "Check whether this payment completed before confirming."
                            else -> "Check the details before including this transaction in your totals."
                        })
                    }
                } else Text(friendly(row.reviewState), style = MaterialTheme.typography.bodySmall)
                Text(transactionTime(row.effectiveTimestamp))
                row.linkedOriginalId?.let { originalId ->
                    TextButton(onClick = { selected = null; selectedId = originalId }) { Text("View linked original transaction") }
                    Text("Linked by matching reference, account, channel and full amount.", style = MaterialTheme.typography.bodySmall)
                }
                row.counterpartyLabel?.let { Text(it) }; row.maskedAccountHint?.let { Text(it) }; row.userNotes?.let { Text(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { form = true }) { Text("Edit / confirm") }
                    OutlinedButton(onClick = { selected = null; selectedId = null }) { Text("Back") }
                }
                TextButton(onClick = { deleteDialog = true }) { Text("Delete transaction") }
            } else if (settings) {
                when (settingsSection) {
                    null -> {
                        Text("Private and encrypted on this device", style = MaterialTheme.typography.bodyMedium)
                        SettingsRow("Personalization", if (preferredName.isBlank()) "Set an optional home greeting" else "Home says Hi, $preferredName") {
                            preferredNameDraft = preferredName; settingsSection = "Personalization"
                        }
                        SettingsRow("SMS and past messages", if (captureEnabled) "New SMS capture is on" else "Capture is off · Manual entry still works") { settingsSection = "SMS & messages" }
                        SettingsRow("Payment sources", if (paymentSources.count { it.active } == 0) "Add your UPI, accounts and cards" else "${paymentSources.count { it.active }} active") { settingsSection = "Payment sources" }
                        SettingsRow("Review reminders", if (repository.preferences.getBoolean("review_reminder", false)) "Daily reminder is on" else "Off") { settingsSection = "Review reminders" }
                        SettingsRow("Data and privacy", "Local storage, erasure and recovery") { settingsSection = "Data & privacy" }
                        SettingsRow("Getting started", "Review the setup checklist") { showGuide = true; settings = false; settingsSection = null }
                    }
                    "Personalization" -> {
                        Text("A name is optional and stays on this device.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = preferredNameDraft, onValueChange = { preferredNameDraft = it.take(40) },
                            label = { Text("Preferred name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            preferredName = preferredNameDraft.trim()
                            repository.preferences.edit().putString("preferred_name", preferredName).apply()
                            settingsSection = null
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save name") }
                    }
                    "SMS & messages" -> {
                        Text("Record supported bank alerts as transactions. You can always add or correct a transaction yourself.")
                        Button(onClick = {
                            if (captureEnabled) { repository.preferences.edit().putBoolean("sms_disclosure", false).apply(); captureEnabled = false } else disclosure = true
                        }, enabled = !busy) { Text(if (captureEnabled) "Pause SMS capture" else "Enable SMS capture") }
                        Text(if (captureEnabled) "SMS capture active" else "SMS capture paused or permission unavailable", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Recording notifications", Modifier.padding(top = 12.dp))
                            Switch(checked = notifications, onCheckedChange = {
                                notifications = it; repository.preferences.edit().putBoolean("notifications", it).apply()
                                if (it && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            })
                        }
                        if (notifications && !notificationAvailable) {
                            Text("Android notifications are blocked. SMS capture can still record transactions.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Open notification settings") }
                        }
                        `in`.financeministry.app.feature.HistoricalImportPanel(repository)
                    }
                    "Payment sources" -> `in`.financeministry.app.feature.PaymentSourcesPanel(repository)
                    "Review reminders" -> `in`.financeministry.app.feature.ReviewReminderPanel(repository, refreshGeneration)
                    "Data & privacy" -> {
                        Text("Your ledger stays encrypted on this device. The app has no bank connection or payment access.")
                        Text("There is no backup or recovery after erasing data. Uninstalling the app loses your ledger.")
                        TextButton(onClick = { eraseDialog = true }, enabled = !busy) { Text("Erase all local data", color = MaterialTheme.colorScheme.error) }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = ledgerListState,
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (destination == "Overview" && showGuide) item {
                    `in`.financeministry.app.feature.FirstUseGuide(captureEnabled, paymentSources.any { it.active },
                        repository.preferences.getBoolean("review_reminder", false),
                        onDone = { repository.preferences.edit().putBoolean("onboarding_complete", true).apply(); showGuide = false },
                        onAdd = { repository.preferences.edit().putBoolean("onboarding_complete", true).apply(); showGuide = false; selected = null; selectedId = null; form = true },
                        onOpenSms = { showGuide = false; settings = true; settingsSection = "SMS & messages" },
                        onOpenSources = { showGuide = false; settings = true; settingsSection = "Payment sources" },
                        onOpenReminder = { showGuide = false; settings = true; settingsSection = "Review reminders" })
                }
                if (destination == "Overview") item {
                    snapshot?.let {
                        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                TextButton(onClick = { selectedMonth = java.time.YearMonth.parse(selectedMonth).minusMonths(1).toString(); offset = 0; snapshot = null },
                                    modifier = Modifier.semantics { contentDescription = "Previous month" }) { Text("‹") }
                                Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                    Text(java.time.YearMonth.parse(selectedMonth).format(DateTimeFormatter.ofPattern("MMMM uuuu")), style = MaterialTheme.typography.titleMedium)
                                    Text("Whole month", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
                                }
                                TextButton(onClick = { selectedMonth = java.time.YearMonth.parse(selectedMonth).plusMonths(1).toString(); offset = 0; snapshot = null },
                                    modifier = Modifier.semantics { contentDescription = "Next month" }) { Text("›") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Your share of spending", style = MaterialTheme.typography.labelMedium)
                                IconButton(onClick = { showSummaryDetails = true }, modifier = Modifier.semantics { contentDescription = "How totals work" }) { Text("ⓘ") }
                            }
                            Text(money(it.personalSpend), style = MaterialTheme.typography.headlineMedium)
                            Text("Personal, family and your share of group spending", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) { Text("Money out · month", style = MaterialTheme.typography.labelSmall); Text(money(it.debit), style = MaterialTheme.typography.titleSmall) }
                                Column(Modifier.weight(1f)) { Text("Money in · month", style = MaterialTheme.typography.labelSmall); Text(money(it.credit), style = MaterialTheme.typography.titleSmall) }
                            }
                            if (showSummaryDetails) {
                                AlertDialog(onDismissRequest = { showSummaryDetails = false }, title = { Text("How totals work") },
                                    text = { Text("Money in and out cover the selected month. They exclude self transfers, card repayments, failed or unconfirmed payments, and reversed originals.\n\nYour share of spending is money out minus amounts paid for other people or groups that you expect back. Still owed includes unpaid amounts across all dates.\n\nToday shows eligible payments for today.") },
                                    confirmButton = { TextButton(onClick = { showSummaryDetails = false }) { Text("Got it") } })
                            }
                        } }
                    }
                }
                if (destination == "Overview") item {
                    snapshot?.let {
                        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column {
                                Text("Still owed to you", style = MaterialTheme.typography.titleSmall)
                                Text("All dates · repayments tracked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(money(it.outstandingRepayments), style = MaterialTheme.typography.titleMedium)
                        } }
                    }
                    if (repository.preferences.getBoolean("capture_error", false)) Text("A message could not be recorded. Add it manually if needed.", color = MaterialTheme.colorScheme.error)
                    if (reviewAvailable) Card(Modifier.fillMaxWidth().clickable { destination = "Review"; offset = 0; snapshot = null }
                        .semantics { contentDescription = "Open review queue" }, shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("$reviewCount payment${if (reviewCount == 1) "" else "s"} needs review →", style = MaterialTheme.typography.titleSmall)
                            Text("Excluded from totals until confirmed", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (destination == "Overview") item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Recent transactions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { destination = "Transactions"; offset = 0; snapshot = null }) { Text("View all") }
                }
                }
                if (destination == "Transactions") item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(java.time.YearMonth.parse(selectedMonth).format(DateTimeFormatter.ofPattern("MMMM uuuu")), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = {
                            draftPurposeFilter = purposePart(filter); draftOriginFilter = originPart(filter); draftDirectionFilter = directionPart(filter); draftCategories = categoryParts(filter); draftSourceIds = sourceParts(filter); showFilters = true
                        }, modifier = Modifier.semantics { contentDescription = "Filter transactions" }) { Text("Filters") }
                    }
                    OutlinedTextField(search, { search = it.take(80); offset = 0; snapshot = null }, label = { Text("Search transactions") },
                        placeholder = { Text("Merchant or label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (filter != "All") {
                            FilterChip(selected = true, onClick = {
                                draftPurposeFilter = purposePart(filter); draftOriginFilter = originPart(filter); draftDirectionFilter = directionPart(filter); draftCategories = categoryParts(filter); draftSourceIds = sourceParts(filter); showFilters = true
                            }, label = { Text(filterLabel(filter, paymentSources)) })
                        }
                        if (search.isNotBlank()) FilterChip(selected = true, onClick = { search = ""; offset = 0; snapshot = null }, label = { Text("Search: $search") })
                        if (filter != "All" || search.isNotBlank()) TextButton(onClick = { filter = "All"; search = ""; offset = 0; snapshot = null },
                            modifier = Modifier.semantics { contentDescription = "Clear transaction filters" }) { Text("Clear") }
                    }
                    snapshot?.let {
                        Text("${it.resultCount} matching transaction${if (it.resultCount == 1L) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                        Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) { Text("Money out · results", style = MaterialTheme.typography.labelSmall); Text(money(it.resultDebit), style = MaterialTheme.typography.titleSmall) }
                            Column(Modifier.weight(1f)) { Text("Money in · results", style = MaterialTheme.typography.labelSmall); Text(money(it.resultCredit), style = MaterialTheme.typography.titleSmall) }
                        } }
                        Text(if (filter == "All" && search.isBlank()) "Same confirmed-payment rules as Overview."
                            else "Filtered totals use the same confirmed-payment rules as Overview.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (destination == "Review") item {
                    Text("All dates · $reviewCount remaining", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text("Review queue", style = MaterialTheme.typography.titleLarge)
                    Text("Check uncertain details before a payment enters your totals.", style = MaterialTheme.typography.bodySmall)
                }
                val rows = if (destination == "Overview") snapshot?.rows.orEmpty().take(4) else snapshot?.rows.orEmpty()
                if (loading) item { Text("Loading transactions…") }
                else if (rows.isEmpty()) item { Text(if (destination == "Review") "You’re all caught up. No saved transactions need review." else if (destination == "Transactions" && (filter != "All" || search.isNotBlank())) "No transactions match these filters in this month. Change or clear the filters." else "No transactions here yet. Add one manually or enable new SMS capture in Settings.") }
                rows.groupBy { Instant.ofEpochMilli(it.effectiveTimestamp).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, rows) ->
                    item("day-$day") { Text(day.format(DateTimeFormatter.ofPattern("d MMM yyyy")), style = MaterialTheme.typography.labelLarge) }
                    items(rows, key = { it.id }) { row ->
                        Column(Modifier.fillMaxWidth().clickable { selected = row; selectedId = row.id }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(row.counterpartyLabel ?: if (row.sourceType == "Manual") "Manual transaction" else "${friendly(row.channel)} transaction",
                                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Text(money(row.amountMinor), style = MaterialTheme.typography.titleSmall)
                                }
                                val namedSource = paymentSources.firstOrNull { it.id == row.paymentSourceId }
                                Text(buildList {
                                    add(friendly(row.direction))
                                    transactionLabels(row)?.let(::add)
                                    namedSource?.nickname?.let(::add)
                                }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(row.effectiveTimestamp)), style = MaterialTheme.typography.labelSmall)
                                    TextButton(onClick = { selected = row; selectedId = row.id; quickForm = true; form = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Categorize") }
                                }
                                if (row.ownership == "Group") {
                                    val personal = row.personalShareMinor ?: row.amountMinor ?: 0
                                    val debt = repaymentSummary(row, snapshot?.reversedOriginalIds.orEmpty())
                                    Text("Your share ${money(personal)}${debt?.let { " · ${it.first} ${money(it.second)}" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                                } else if (row.ownership == "ForOther") {
                                    repaymentSummary(row, snapshot?.reversedOriginalIds.orEmpty())?.let { (label, owed) ->
                                        Text("$label ${money(owed)}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (row.paymentSourceId == null && paymentSources.any { it.active && it.channel == row.channel })
                                    Text("Payment source needs selection", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                if (row.reviewState == "NeedsReview" || row.status != "Successful")
                                    Text("${friendly(row.status)} · ${friendly(row.reviewState)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                else if (row.isUserCorrected) Text("Edited by you", style = MaterialTheme.typography.labelSmall)
                                HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                if (destination != "Overview" && (offset > 0 || snapshot?.hasOlder == true)) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { offset = maxOf(0, offset - 100); snapshot = null }, enabled = !loading && offset > 0) { Text("Newer") }
                    Text("Page ${offset / 100 + 1}", Modifier.padding(top = 12.dp))
                    TextButton(onClick = { offset += 100; snapshot = null }, enabled = !loading && snapshot?.hasOlder == true && offset <= Int.MAX_VALUE - 201) { Text("Older") }
                }
                }
                }
                if (destination != "Review" && !(destination == "Overview" && showGuide)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = { selected = null; selectedId = null; form = true }, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "Add transaction" },
                            shape = MaterialTheme.shapes.large,
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                            Text("+ Add transaction")
                        }
                    }
                }
                LedgerNavigation(destination) { next ->
                    if (next != destination) { destination = next; offset = 0; snapshot = null }
                }
            }
        }
    }
    if (requestWarning) AlertDialog(onDismissRequest = { requestWarning = false; consumeRequest() },
        title = { Text("Open another transaction?") }, text = { Text("Your current changes have not been saved.") },
        confirmButton = { TextButton(onClick = { approvedRequest = request; requestWarning = false; requestApproval++ }) { Text("Discard and open") } },
        dismissButton = { TextButton(onClick = { requestWarning = false; consumeRequest() }) { Text("Keep editing") } })
    if (reviewWarning) AlertDialog(onDismissRequest = { reviewWarning = false },
        title = { Text("Open review queue?") }, text = { Text("Your current transaction changes have not been saved.") },
        confirmButton = { TextButton(onClick = {
            reviewWarning = false; dirtyForm = false; form = false; selected = null; selectedId = null
            settings = false; settingsSection = null; destination = "Review"; offset = 0; snapshot = null
        }) { Text("Discard and review") } },
        dismissButton = { TextButton(onClick = { reviewWarning = false }) { Text("Keep editing") } })
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("Read new SMS on this device?") },
        text = { Text("Android gives this app access to incoming SMS, including non-financial messages. Processing stays on this device. We reject OTPs and non-transactions and store normalized financial fields in encrypted storage. Raw messages and senders are not stored or uploaded. SMS permission is optional; manual entry always works. No payment or bank connection is involved.") },
        confirmButton = { TextButton(onClick = { repository.preferences.edit().putBoolean("sms_disclosure", true).apply(); disclosure = false; smsPermission.launch(Manifest.permission.RECEIVE_SMS) }) { Text("I understand — continue") } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Not now") } })
    if (eraseDialog) AlertDialog(onDismissRequest = { if (!busy) eraseDialog = false }, title = { Text("Erase all local data?") },
        text = { Text("Permanently deletes transactions, corrections, encryption keys and settings. There is no backup or undo. SMS capture will be off.") },
        confirmButton = { TextButton(onClick = { busy = true; scope.launch {
            try { repository.eraseAll(); selected = null; selectedId = null; form = false; snapshot = null; captureEnabled = false; notifications = true; error = null }
            catch (_: Exception) { error = "Erasure did not fully finish. Capture is off; retry before re-enabling it." }
            finally { busy = false; eraseDialog = false }
        } }, enabled = !busy) { Text("Erase permanently") } },
        dismissButton = { TextButton(onClick = { eraseDialog = false }, enabled = !busy) { Text("Cancel") } })
    if (deleteDialog) AlertDialog(onDismissRequest = { if (!busy) deleteDialog = false }, title = { Text("Delete this transaction?") },
        text = { Text("The record and correction history will be permanently removed.") },
        confirmButton = { TextButton(onClick = { if (!busy) { busy = true; scope.launch {
            try { repository.delete(selected!!.id); selected = null; selectedId = null } catch (_: Exception) { error = "Delete failed. Please retry." }
            finally { busy = false; deleteDialog = false }
        } } }, enabled = !busy) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }, enabled = !busy) { Text("Cancel") } })
    if (showFilters) AlertDialog(onDismissRequest = { showFilters = false }, title = { Text("Filter transactions") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Money flow", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Debit", "Credit").forEach { option ->
                    FilterChip(selected = draftDirectionFilter == option, onClick = {
                        draftDirectionFilter = if (draftDirectionFilter == option) "All" else option
                    }, label = { Text(if (option == "Debit") "Money out" else "Money in") })
                }
            }
            HorizontalDivider()
            Text("Purpose", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Personal", "Family", "ForOthers", "Group", "SelfTransfer").forEach { option ->
                    FilterChip(selected = draftPurposeFilter == option, onClick = {
                        draftPurposeFilter = if (draftPurposeFilter == option) "All" else option
                    }, label = { Text(friendlyFilter(option)) })
                }
            }
            HorizontalDivider()
            Text("Origin and changes", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Manual", "Edited").forEach { option ->
                    FilterChip(selected = draftOriginFilter == option, onClick = {
                        draftOriginFilter = if (draftOriginFilter == option) "All" else option
                    }, label = { Text(friendlyFilter(option)) })
                }
            }
            HorizontalDivider()
            Text("Payment source", style = MaterialTheme.typography.labelLarge)
            if (paymentSources.isEmpty()) Text("Add a payment source in Settings to filter by it.", style = MaterialTheme.typography.bodySmall)
            else {
                Text("Choose one or more registered sources.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    paymentSources.sortedWith(compareByDescending<PaymentSourceEntity> { it.active }.thenBy { it.nickname.lowercase() }).forEach { source ->
                        FilterChip(selected = source.id in draftSourceIds, onClick = {
                            draftSourceIds = if (source.id in draftSourceIds) draftSourceIds - source.id else draftSourceIds + source.id
                        }, label = { Text(source.nickname + if (source.active) "" else " · inactive") })
                    }
                }
            }
            HorizontalDivider()
            Text("Category", style = MaterialTheme.typography.labelLarge)
            Text("Choose one or more. None selected includes all categories.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                transactionCategories.forEach { category ->
                    FilterChip(selected = category in draftCategories, onClick = {
                        draftCategories = if (category in draftCategories) draftCategories - category else draftCategories + category
                    }, label = { Text(category) })
                }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        val knownSourceIds = paymentSources.map { it.id }.toSet()
        val next = combinedFilter(draftPurposeFilter, draftOriginFilter, draftDirectionFilter, draftCategories,
            draftSourceIds.filter { it in knownSourceIds })
        if (next != filter || offset != 0) { filter = next; offset = 0; snapshot = null }
        showFilters = false
    }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = {
            draftPurposeFilter = "All"; draftOriginFilter = "All"; draftDirectionFilter = "All"; draftCategories = emptyList(); draftSourceIds = emptyList()
            if (filter != "All" || offset != 0) { filter = "All"; offset = 0; snapshot = null }
            showFilters = false
        }, modifier = Modifier.semantics { contentDescription = "Clear transaction filters" }) { Text("Clear all") } })
    if (showSourcePicker && selected != null) AlertDialog(
        onDismissRequest = { if (!busy) showSourcePicker = false },
        title = { Text("Choose payment source") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("This only assigns a source. It won’t confirm or change the transaction.", style = MaterialTheme.typography.bodySmall)
                paymentSources.filter { it.active && it.channel == selected!!.channel }.forEach { source ->
                    TextButton(onClick = { if (!busy) { busy = true; scope.launch {
                        try {
                            selected = repository.updateTransactionPaymentSource(selected!!.id, source.id)
                            showSourcePicker = false; error = null
                        } catch (_: Exception) { error = "Could not assign that payment source. Please retry." }
                        finally { busy = false }
                    } } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(source.nickname) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { showSourcePicker = false }, enabled = !busy) { Text("Cancel") } })
}

private fun purposePart(value: String): String = value.split("+").firstOrNull {
    it in listOf("Personal", "Family", "ForOthers", "Group", "SelfTransfer")
} ?: "All"

private fun originPart(value: String): String = value.split("+").firstOrNull {
    it in listOf("Manual", "Edited")
} ?: "All"

private fun directionPart(value: String): String = value.split("+").firstOrNull {
    it in listOf("Debit", "Credit")
} ?: "All"

private fun categoryParts(value: String): List<String> = value.split("+").filter { it.startsWith("Category:") }.map { it.removePrefix("Category:") }

private fun sourceParts(value: String): List<String> = value.split("+").filter { it.startsWith("Source:") }.map { it.removePrefix("Source:") }

private fun combinedFilter(purpose: String, origin: String, direction: String = "All", categories: List<String> = emptyList(),
    sourceIds: List<String> = emptyList()): String = (listOf(purpose, origin, direction) +
    transactionCategories.filter { it in categories }.map { "Category:$it" } + sourceIds.distinct().map { "Source:$it" })
    .filter { it != "All" }.joinToString("+").ifBlank { "All" }

private fun filterLabel(value: String, paymentSources: List<PaymentSourceEntity>): String {
    val sourceNames = paymentSources.associate { it.id to it.nickname }
    return value.split("+").joinToString(" · ") {
        when {
            it.startsWith("Category:") -> it.removePrefix("Category:")
            it.startsWith("Source:") -> sourceNames[it.removePrefix("Source:")] ?: "Payment source"
            else -> friendlyFilter(it)
        }
    }
}

private fun friendlyFilter(value: String): String = when (value) {
    "Review" -> "Needs review"
    "Debit" -> "Money out"
    "Credit" -> "Money in"
    "ForOthers" -> "For someone else"
    "SelfTransfer" -> "Self transfer"
    else -> friendly(value)
}

internal fun repaymentSummary(row: TransactionEntity, reversedOriginalIds: Set<String> = emptySet()): Pair<String, Long>? {
    if (row.ownership !in listOf("ForOther", "Group") || row.direction != "Debit" || row.amountMinor == null) return null
    if (row.status in listOf("Failed", "Reversed") || row.transactionType in listOf("SelfTransfer", "CardRepayment", "Refund", "Reversal") || row.id in reversedOriginalIds) return null
    val personal = row.personalShareMinor ?: row.amountMinor
    val owed = (row.amountMinor - personal - row.repaidMinor).coerceAtLeast(0)
    val settled = row.status == "Successful" && row.reviewState != "NeedsReview"
    return (if (settled) "Still owed" else "Potentially owed after review") to owed
}

private fun transactionLabels(row: TransactionEntity): String? = buildList {
    if (row.category != "Other") add(friendly(row.category))
    if (row.ownership != "Personal") add(friendly(row.ownership))
    row.groupLabel?.takeIf { it.isNotBlank() }?.let(::add)
}.joinToString(" · ").ifBlank { null }

@Composable
private fun LedgerNavigation(destination: String, onNavigate: (String) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LedgerNavigationButton("Overview", destination, "Open overview", onNavigate, Modifier.weight(1f))
        LedgerNavigationButton("Transactions", destination, "Open transactions", onNavigate, Modifier.weight(1f))
        LedgerNavigationButton("Review", destination, "Open review tab", onNavigate, Modifier.weight(1f))
    }
}

@Composable
private fun LedgerNavigationButton(label: String, destination: String, description: String, onNavigate: (String) -> Unit, modifier: Modifier) {
    TextButton(onClick = { onNavigate(label) }, modifier = modifier.semantics { contentDescription = description },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destination == label) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            containerColor = if (destination == label) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
        Text(label)
    }
}

@Composable
private fun SettingsRow(title: String, summary: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}

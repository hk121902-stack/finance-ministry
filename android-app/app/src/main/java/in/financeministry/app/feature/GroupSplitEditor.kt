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
import `in`.financeministry.app.data.*
import `in`.financeministry.app.money
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

internal fun encodeShares(shares: List<MemberShareInput>): String = JSONArray().apply {
    shares.forEach { put(JSONObject().put("person", it.memberId).put("amount", it.amountMinor)) }
}.toString()
internal fun decodeShares(value: String): List<MemberShareInput> = JSONArray(value).let { array ->
    List(array.length()) { i -> array.getJSONObject(i).let { MemberShareInput(it.getString("person"), it.getLong("amount")) } }
}
internal fun shareDecimal(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()
private fun parseShare(value: String): Long {
    require(value.matches(Regex("[0-9]{1,16}(\\.[0-9]{1,2})?"))) { "Enter shares with up to two decimal places." }
    return BigDecimal(value).movePointRight(2).longValueExact()
}

/** Draft-only until Apply. Member nicknames are reusable local records, never contacts. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GroupSplitEditor(repository: TransactionRepository, total: Long, personal: Long,
    initial: List<MemberShareInput>, onApply: suspend (Long, List<MemberShareInput>) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<LocalMemberEntity>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var ids by rememberSaveable { mutableStateOf(initial.map { it.memberId }) }
    var equal by rememberSaveable { mutableStateOf(initial.isEmpty()) }
    val initialAmounts = remember { JSONObject().put("you", shareDecimal(personal)).apply {
        initial.forEach { put(it.memberId, shareDecimal(it.amountMinor)) }
    }.toString() }
    var amounts by rememberSaveable { mutableStateOf(initialAmounts) }
    var newName by rememberSaveable { mutableStateOf("") }
    var choose by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var rename by rememberSaveable { mutableStateOf("") }
    val dirty = ids != initial.map { it.memberId } || amounts != initialAmounts || equal != initial.isEmpty()
    val close = { if (!busy) { if (dirty) discard = true else onDismiss() }; Unit }
    BackHandler { close() }
    LaunchedEffect(Unit) {
        try { members = repository.localMembers(); ready = true }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load people. Close and retry." }
    }
    fun setAmount(id: String, value: String) { amounts = JSONObject(amounts).put(id, value).toString(); error = null }
    // A modal owns a separate composition. Capture one immutable render snapshot:
    // its child must not observe new IDs while using an older calculated list.
    val selectedIds = ids
    val equalMode = equal
    val amountTexts = JSONObject(amounts)
    val equalAmounts = if (selectedIds.isEmpty()) listOf(total) else GroupSplitAccounting.equal(total, selectedIds.size + 1)
    val custom = try { listOf(parseShare(amountTexts.optString("you"))) + selectedIds.map { parseShare(amountTexts.optString(it)) } }
        catch (_: Exception) { null }
    val values = if (equalMode) equalAmounts else custom
    val sum = values?.fold(BigInteger.ZERO) { n, v -> n + BigInteger.valueOf(v) }
    val valid = ready && selectedIds.isNotEmpty() && selectedIds.size <= 20 && sum == BigInteger.valueOf(total)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !dirty && !busy })
    ModalBottomSheet(onDismissRequest = close, sheetState = sheet, sheetGesturesEnabled = false, dragHandle = null,
        modifier = Modifier.imePadding()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Split with people", style = MaterialTheme.typography.headlineSmall)
                Text("You paid ${money(total)}", style = MaterialTheme.typography.titleMedium)
                Text("Names stay on this device. No contacts or invitations.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(equalMode, { equal = true; error = null }, label = { Text("Equal") }, enabled = !busy)
                    FilterChip(!equalMode, {
                        if (equalMode) {
                            amounts = JSONObject().put("you", shareDecimal(equalAmounts[0])).apply {
                                selectedIds.forEachIndexed { i, id -> put(id, shareDecimal(equalAmounts[i + 1])) }
                            }.toString()
                        }
                        equal = false; error = null
                    }, label = { Text("Custom") }, enabled = !busy)
                }
                if (equalMode && total % (selectedIds.size + 1) != 0L) Text("Extra paise go to You, then people in the order below.", style = MaterialTheme.typography.bodySmall)
                SplitShareRow("You", if (equalMode) equalAmounts[0] else null, amountTexts.optString("you"), !busy,
                    { setAmount("you", it) })
                selectedIds.forEachIndexed { i, id ->
                    val name = members.firstOrNull { it.id == id }?.name ?: "Loading person…"
                    Column {
                        SplitShareRow(name, if (equalMode) equalAmounts[i + 1] else null, amountTexts.optString(id), !busy,
                            { setAmount(id, it) })
                        TextButton(onClick = { ids = ids - id; error = null }, enabled = !busy) { Text("Remove $name from split") }
                    }
                }
                if (selectedIds.size < 20) {
                    TextButton(onClick = { choose = true }, enabled = ready && !busy) { Text("Choose an existing person") }
                    OutlinedTextField(newName, { newName = it.take(40); error = null }, label = { Text("New local name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = ready && !busy)
                    TextButton(onClick = {
                        if (busy || ids.size >= 20) return@TextButton
                        busy = true
                        scope.launch {
                            try {
                                val person = repository.addLocalMember(newName)
                                members = repository.localMembers(); ids = ids + person.id
                                setAmount(person.id, "0"); newName = ""
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (e: IllegalArgumentException) { error = e.message }
                            catch (_: Exception) { error = "Could not add this name. Check the people list before retrying." }
                            finally { busy = false }
                        }
                    }, enabled = ready && !busy && newName.isNotBlank()) { Text("Add person") }
                }
                if (initial.isNotEmpty()) TextButton(onClick = { remove = true }, enabled = !busy) { Text("Use an unnamed split instead") }
                Spacer(Modifier.height(8.dp))
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        selectedIds.isEmpty() -> Text("Add at least one person besides you.", style = MaterialTheme.typography.bodySmall)
                        sum == null -> Text("Enter a valid amount for every share.", style = MaterialTheme.typography.bodySmall)
                        sum < BigInteger.valueOf(total) -> Text("Remaining to assign: ${money(BigInteger.valueOf(total) - sum)}", style = MaterialTheme.typography.bodySmall)
                        sum > BigInteger.valueOf(total) -> Text("Shares exceed the expense by ${money(sum - BigInteger.valueOf(total))}", color = MaterialTheme.colorScheme.error)
                        else -> Text("${selectedIds.size + 1} people · Total ${money(total)}", style = MaterialTheme.typography.bodySmall)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (busy) return@Button
                            busy = true
                            scope.launch {
                                try { onApply(values!![0], selectedIds.mapIndexed { i, id -> MemberShareInput(id, values[i + 1]) }); onDismiss() }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (e: IllegalArgumentException) { error = e.message }
                                catch (_: Exception) { error = "Could not apply the split. Close and check the expense before retrying." }
                                finally { busy = false }
                            }
                        }, enabled = valid && !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "Saving…" else "Apply split") }
                        TextButton(onClick = close, enabled = !busy) { Text("Cancel") }
                    }
                }
            }
        }
    }
    if (choose) AlertDialog(onDismissRequest = { choose = false }, title = { Text("Choose people") },
        text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(search, { search = it.take(40) }, label = { Text("Search local names") }, singleLine = true)
            members.filter { it.name.contains(search, true) }.forEach { person ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(person.id in selectedIds, { checked ->
                        if (!checked) ids = ids - person.id
                        else if (person.id !in ids && ids.size < 20) { ids = ids + person.id; setAmount(person.id, "0") }
                    })
                    Text(person.name, Modifier.weight(1f))
                    TextButton(onClick = { renameId = person.id; rename = person.name }) { Text("Rename") }
                }
            }
            if (members.isEmpty()) Text("No saved people yet. Add a new local name in the split.")
        } }, confirmButton = { TextButton(onClick = { choose = false }) { Text("Done") } })
    if (renameId != null) AlertDialog(onDismissRequest = { if (!busy) renameId = null }, title = { Text("Rename local person") },
        text = { Column {
            OutlinedTextField(rename, { rename = it.take(40) }, label = { Text("Local name") }, singleLine = true)
            Text("Their shares and repayments stay linked.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true; scope.launch {
                try { repository.renameLocalMember(renameId!!, rename); members = repository.localMembers(); renameId = null; error = null }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (e: IllegalArgumentException) { error = e.message }
                catch (_: Exception) { error = "Could not rename. Close and retry." }
                finally { busy = false }
            }
        }) { Text("Save name") } }, dismissButton = { TextButton(onClick = { renameId = null }, enabled = !busy) { Text("Cancel") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard split changes?") },
        text = { Text("The expense has not changed. New local names remain available for future splits.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Discard changes") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
    if (remove) AlertDialog(onDismissRequest = { if (!busy) remove = false }, title = { Text("Remove individual shares?") },
        text = { Text("Your share and the total owed stay the same. Unlink any existing repayments first; incoming payments are kept.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true; scope.launch {
                try { onApply(personal, emptyList()); onDismiss() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (e: IllegalArgumentException) { error = e.message; remove = false }
                catch (_: Exception) { error = "Could not remove the split. Close and check the expense."; remove = false }
                finally { busy = false }
            }
        }) { Text("Remove shares") } }, dismissButton = { TextButton(onClick = { remove = false }, enabled = !busy) { Text("Cancel") } })
}

@Composable private fun SplitShareRow(name: String, fixed: Long?, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    if (fixed != null) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(money(fixed), style = MaterialTheme.typography.titleMedium)
    } else OutlinedTextField(value, { onChange(it.take(19)) }, label = { Text("Share for $name (INR)") },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(), enabled = enabled)
}

package `in`.financeministry.app.feature

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.data.*
import `in`.financeministry.app.money

@Composable
internal fun GroupSharesPanel(repository: TransactionRepository, expense: TransactionEntity, eligible: Boolean) {
    val revision by repository.revision.collectAsState()
    var row by remember(expense.id) { mutableStateOf(expense) }
    var shares by remember { mutableStateOf<List<GroupShareEntity>>(emptyList()) }
    var members by remember { mutableStateOf<List<LocalMemberEntity>>(emptyList()) }
    var receipts by remember { mutableStateOf<List<RepaymentEntity>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var edit by rememberSaveable(expense.id) { mutableStateOf(false) }
    var receiving by rememberSaveable(expense.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(expense.id, revision) {
        try {
            row = repository.get(expense.id) ?: expense
            shares = repository.groupSharesFor(expense.id); members = repository.localMembers()
            receipts = repository.repaymentsFor(expense.id); ready = true; error = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { ready = false; error = "Could not load people and balances. Close and retry." }
    }
    Text("People & shares", style = MaterialTheme.typography.titleMedium)
    if (shares.isEmpty()) Text("Unassigned to people · ${money(RepaymentAccounting.owed(row))} still owed", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Your share", style = MaterialTheme.typography.bodyMedium)
            Text(money(RepaymentAccounting.personal(row)), style = MaterialTheme.typography.titleMedium)
        }
        shares.sortedBy { members.firstOrNull { m -> m.id == it.memberId }?.name }.forEach { share ->
            val name = members.firstOrNull { it.id == share.memberId }?.name ?: "Local person"
            val owed = GroupSplitAccounting.owed(share, receipts)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(if (owed == 0L) "Settled" else "Owes ${money(owed)}", style = MaterialTheme.typography.titleMedium)
                }
                Text("Share ${money(share.amountMinor)} · Received ${money(share.amountMinor - owed)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (owed > 0L) TextButton(onClick = { receiving = share.memberId }, enabled = ready && eligible) { Text("Record received from $name") }
            }
        }
    }
    TextButton(onClick = { edit = true }, enabled = ready && eligible) { Text(if (shares.isEmpty()) "Split with people" else "Edit individual split") }
    if (receipts.isNotEmpty()) Text("Unlink repayments before changing the split. Incoming payments are kept.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (edit) GroupSplitEditor(repository, row.amountMinor!!, RepaymentAccounting.personal(row),
        shares.map { MemberShareInput(it.memberId, it.amountMinor) },
        onApply = { own, people -> repository.saveGroupSplit(row.id, own, people, row.updatedAt) }, onDismiss = { edit = false })
    receiving?.let { person -> RepaymentEditor(repository, row, null, onDismiss = { receiving = null }, initialMemberId = person) }
}

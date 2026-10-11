package `in`.financeministry.app.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import java.math.BigDecimal

/** Keep the member draft in its own small composition, not the financial form's data-flow graph. */
internal class GroupSplitFormDraft(ready: Boolean) {
    var encoded by mutableStateOf<String?>(null)
    var baseline by mutableStateOf<String?>(null)
    var ready by mutableStateOf(ready)
    var total by mutableStateOf<Long?>(null)
    var error by mutableStateOf<String?>(null)
    val dirty get() = encoded != baseline
    val shares get() = encoded?.let(::decodeShares)
    val hasPeople get() = !shares.isNullOrEmpty()
    companion object {
        val Saver = listSaver<GroupSplitFormDraft, Any>(
            save = { listOf(it.encoded.orEmpty(), it.baseline.orEmpty(), it.ready, it.total ?: -1L, it.error.orEmpty()) },
            restore = { GroupSplitFormDraft(it[2] as Boolean).apply {
                encoded = (it[0] as String).ifEmpty { null }; baseline = (it[1] as String).ifEmpty { null }
                total = (it[3] as Long).takeIf { amount -> amount > 0 }; error = (it[4] as String).ifEmpty { null }
            } })
    }
}

@Composable internal fun rememberGroupSplitFormDraft(repository: TransactionRepository, existing: TransactionEntity?): GroupSplitFormDraft {
    val key = existing?.id
    val draft = rememberSaveable(key, saver = GroupSplitFormDraft.Saver) { GroupSplitFormDraft(existing == null) }
    LaunchedEffect(key) {
        if (!draft.ready && key != null) {
            try {
                val shares = repository.groupSharesFor(key).map { MemberShareInput(it.memberId, it.amountMinor) }
                draft.encoded = shares.takeIf { it.isNotEmpty() }?.let(::encodeShares)
                draft.baseline = draft.encoded; draft.ready = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { draft.error = "Could not load the spending split. Close and retry before saving." }
        }
    }
    return draft
}

@Composable internal fun GroupSplitFormFields(repository: TransactionRepository, draft: GroupSplitFormDraft, amount: String,
    personalShare: String, enabled: Boolean, onPersonalShare: (String) -> Unit, onError: (String) -> Unit) {
    if (draft.hasPeople) {
        Text("Your share: ₹$personalShare", style = MaterialTheme.typography.titleMedium)
        Text("${draft.shares!!.size} individual shares · Edit the split to change amounts.", style = MaterialTheme.typography.bodySmall)
        Text("The split is recorded when you save this transaction.", style = MaterialTheme.typography.bodySmall)
    } else OutlinedTextField(personalShare, onPersonalShare, label = { Text("Your share (INR)") },
        supportingText = { Text("The rest is tracked as money others owe you.") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    TextButton(onClick = {
        try { draft.total = ManualInput(amount, Direction.Debit, 1, TransactionType.Other).amountMinor() }
        catch (_: IllegalArgumentException) { onError("Enter the expense amount before splitting it.") }
    }, enabled = draft.ready && enabled) { Text(if (draft.hasPeople) "Edit individual split" else "Split with people") }
    draft.total?.let { total ->
        val own = try { BigDecimal(personalShare).movePointRight(2).longValueExact() } catch (_: Exception) { 0L }
        GroupSplitEditor(repository, total, own, draft.shares.orEmpty(),
            onApply = { personal, shares -> onPersonalShare(shareDecimal(personal)); draft.encoded = encodeShares(shares) },
            onDismiss = { draft.total = null })
    }
}

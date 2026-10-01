package `in`.financeministry.app.feature

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.financeministry.app.data.TransactionRepository
import `in`.financeministry.app.data.addCategory
import kotlinx.coroutines.launch

@Composable
fun AddCategoryDialog(repository: TransactionRepository, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Add category") },
        text = { Column {
            OutlinedTextField(name, { name = it.take(40); error = null }, label = { Text("New category name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("A local label only. It does not change how totals are calculated.", modifier = Modifier.padding(top = 8.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { onCreated(repository.addCategory(name)) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: IllegalArgumentException) { error = failure.message ?: "Check the category name." }
                catch (_: Exception) { error = "Could not save category. Try again." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Saving…" else "Save category") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}

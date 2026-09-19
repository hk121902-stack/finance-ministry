package `in`.financeministry.app.feature

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FirstUseGuide(captureEnabled: Boolean, hasSources: Boolean, reminderEnabled: Boolean,
    onDone: () -> Unit, onAdd: () -> Unit, onOpenSms: () -> Unit, onOpenSources: () -> Unit, onOpenReminder: () -> Unit) {
    val optionalSetupExpanded = rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Getting started", style = MaterialTheme.typography.titleMedium)
            Text("Add a payment now, or set up optional recording below. Everything stays on this device.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("+ Add transaction") }
            TextButton(onClick = { optionalSetupExpanded.value = !optionalSetupExpanded.value }, contentPadding = PaddingValues(0.dp)) {
                Text(if (optionalSetupExpanded.value) "Hide optional setup" else "Optional setup · SMS, sources and reminder")
            }
            if (optionalSetupExpanded.value) {
                GuideItem(captureEnabled, "Record supported bank SMS", onOpenSms)
                GuideItem(hasSources, "Name your UPI, accounts and cards", onOpenSources)
                GuideItem(reminderEnabled, "Choose a review reminder", onOpenReminder)
            }
            TextButton(onClick = onDone, contentPadding = PaddingValues(0.dp)) { Text("Skip for now") }
        }
    }
}

@Composable private fun GuideItem(done: Boolean, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Text("${if (done) "✓" else "○"} $label", Modifier.align(Alignment.CenterStart))
        }
    }
}

package `in`.financeministry.app

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    private val request = mutableStateOf<Pair<String, Boolean>?>(null)
    private val resumeGeneration = androidx.compose.runtime.mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readRequest(intent)
        setContent {
            FinanceMinistryTheme {
                LedgerApp((application as FinanceMinistryApp).container.repository, request.value, resumeGeneration.intValue) {
                    request.value = null
                    intent.removeExtra("transaction_id"); intent.removeExtra("edit")
                }
            }
        }
    }
    override fun onResume() { super.onResume(); resumeGeneration.intValue++ }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readRequest(intent) }
    private fun readRequest(intent: Intent) { request.value = intent.getStringExtra("transaction_id")?.let { it to intent.getBooleanExtra("edit", false) } }
}

@Composable
fun FinanceMinistryTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (dark) androidx.compose.material3.darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF8AD5CA),
        background = androidx.compose.ui.graphics.Color(0xFF101817),
        surface = androidx.compose.ui.graphics.Color(0xFF101817),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF263C38),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF263C38),
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFF224D45)
    ) else androidx.compose.material3.lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF176B60),
        onPrimary = androidx.compose.ui.graphics.Color.White,
        primaryContainer = androidx.compose.ui.graphics.Color(0xFFD4EEE6),
        background = androidx.compose.ui.graphics.Color(0xFFF7F9F6),
        surface = androidx.compose.ui.graphics.Color(0xFFF7F9F6),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE4EDE7),
        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE4EDE7),
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD4EEE6)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun FinanceMinistryNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "shell") {
        composable("shell") {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(text = "Finance Ministry", style = MaterialTheme.typography.headlineMedium)
                    Text(text = "Private alpha shell")
                }
            }
        }
    }
}

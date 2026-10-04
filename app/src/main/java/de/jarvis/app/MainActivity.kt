package de.jarvis.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(transparent), SystemBarStyle.dark(transparent))
        val prefs = Prefs(this)
        setContent {
            JarvisTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    var setupDone by remember { mutableStateOf(prefs.setupDone) }
                    if (!setupDone) SetupFlow(resumeTick, prefs) { setupDone = true }
                    else HomeScreen(resumeTick) { prefs.setupDone = false; prefs.skipped = emptySet(); setupDone = false }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); resumeTick++ }
}

@Composable
fun SetupFlow(resumeTick: Int, prefs: Prefs, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    var started by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableStateOf(prefs.skipped) }
    var attempted by remember { mutableStateOf(setOf<Step>()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    val current = remember(resumeTick, tick, skipped) {
        Step.entries.firstOrNull { it.name !in skipped && !Perms.isGranted(ctx, it) }
    }

    fun request(step: Step) {
        val retry = step in attempted
        attempted = attempted + step
        Perms.request(ctx, step, retry) { launcher.launch(it) }
    }

    // Automatisch weiter: Systemdialog bzw. Einstellungsseite oeffnet sich nach kurzem Hinweis von selbst.
    LaunchedEffect(started, current) {
        if (started && current != null && current !in attempted) {
            delay(1800)
            if (current !in attempted) request(current)
        }
    }

    when {
        !started -> Welcome { started = true }
        current != null -> StepScreen(
            step = current,
            index = Step.entries.indexOf(current) + 1,
            total = Step.entries.size,
            retry = current in attempted,
            onGo = { request(current) },
            onSkip = { skipped = skipped + current.name; prefs.skipped = skipped })
        else -> ReadyScreen { prefs.setupDone = true; onFinished() }
    }
}

package de.jarvis.app

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val Cyan = Color(0xFF3DDCFF)
val Amber = Color(0xFFFFC857)
val Green = Color(0xFF4ADE80)
val Red = Color(0xFFE5484D)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Cyan, onPrimary = Color(0xFF00212B),
        background = Color(0xFF0A0F1A), surface = Color(0xFF121A2B),
        onBackground = Color.White, onSurface = Color.White,
        surfaceVariant = Color(0xFF121A2B), onSurfaceVariant = Color.White
    ), content = content)

@Composable
fun Centered(content: @Composable ColumnScope.() -> Unit) = BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center, content = content)
}

@Composable
private fun Logo() = Text("JARVIS", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Cyan, letterSpacing = 6.sp)

fun fmt(ms: Long): String = SimpleDateFormat("EEE d. MMM, HH:mm", Locale.GERMAN).format(Date(ms))

@Composable
fun Welcome(onStart: () -> Unit) = Centered {
    Logo()
    Text("Persönlicher Assistent", color = Color.Gray)
    Spacer(Modifier.height(28.dp))
    Text("Willkommen. Ich richte mich jetzt für dich ein.", textAlign = TextAlign.Center)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onStart) { Text("STARTEN") }
}

@Composable
fun StepScreen(step: Step, index: Int, total: Int, retry: Boolean, onGo: () -> Unit, onSkip: () -> Unit) = Centered {
    Text("Schritt $index von $total", color = Color.Gray)
    Spacer(Modifier.height(16.dp))
    Text(step.title, style = MaterialTheme.typography.headlineMedium, color = Cyan)
    Spacer(Modifier.height(12.dp))
    Text(step.text, textAlign = TextAlign.Center)
    if (retry) {
        Spacer(Modifier.height(12.dp))
        Text("Dafür brauche ich deine Erlaubnis.", color = Amber, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(28.dp))
    Button(onClick = onGo) { Text(if (retry) "Einstellungen öffnen" else "Weiter") }
    TextButton(onClick = onSkip) { Text("Später") }
}

@Composable
fun ReadyScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    var checks by remember { mutableStateOf<List<Check>?>(null) }
    LaunchedEffect(Unit) { checks = Provisioner.run(ctx.applicationContext) }
    Centered {
        Logo()
        Spacer(Modifier.height(20.dp))
        val list = checks
        if (list == null) {
            CircularProgressIndicator(color = Cyan)
            Spacer(Modifier.height(12.dp))
            Text("Ich richte mich ein …")
        } else {
            val warn = list.any { it.status == Status.WARN }
            Text(if (warn) "🟡 TEILWEISE BEREIT" else "🟢 BEREIT", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            list.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(c.label, Modifier.weight(1f))
                    val (sym, col) = when (c.status) { Status.OK -> "✓" to Green; Status.PENDING -> "…" to Color.Gray; Status.WARN -> "!" to Amber }
                    Text("$sym ${c.detail}", color = col)
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(onClick = onFinish) { Text("JARVIS STARTEN") }
        }
    }
}

@Composable
fun HomeScreen(resumeTick: Int, onRecheck: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val reminders by AppDb.get(ctx).reminders().observeActive().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    LaunchedEffect(resumeTick) { if (!JarvisService.running) JarvisService.start(ctx.applicationContext) }
    var running by remember { mutableStateOf(JarvisService.running) }
    LaunchedEffect(resumeTick) { delay(500); running = JarvisService.running }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(); Spacer(Modifier.weight(1f))
            Text(if (running) "🟢 aktiv" else "🟡 startet …", color = Color.Gray)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showAdd = true }) { Text("+ Erinnerung") }
            OutlinedButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    Reminders.add(ctx.applicationContext, "Das ist ein Test. Alles funktioniert.", System.currentTimeMillis() + 10_000, "NONE", true)
                }
            }) { Text("Test-Anruf (10 s)") }
        }
        Text("Für den Test-Anruf danach den Bildschirm sperren.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        Text("Erinnerungen", style = MaterialTheme.typography.titleMedium, color = Cyan)
        if (reminders.isEmpty()) Text("Noch keine Erinnerungen.", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(reminders, key = { it.id }) { r ->
                val rep = when (r.repeat) { "DAILY" -> " · täglich"; "WEEKLY" -> " · wöchentlich"; else -> "" }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (r.asCall) "📞 " else "") + r.text)
                            Text(fmt(r.triggerAt) + rep, fontSize = 12.sp, color = Color.Gray)
                        }
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { Reminders.remove(ctx.applicationContext, r.id) } }) { Text("✕") }
                    }
                }
            }
        }
        TextButton(onClick = onRecheck) { Text("Berechtigungen erneut prüfen") }
    }

    if (showAdd) AddReminderDialog(
        onDismiss = { showAdd = false },
        onSave = { text, at, repeat, call ->
            showAdd = false
            scope.launch(Dispatchers.IO) { Reminders.add(ctx.applicationContext, text, at, repeat, call) }
        })
}

@Composable
fun AddReminderDialog(onDismiss: () -> Unit, onSave: (String, Long, String, Boolean) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var at by remember { mutableStateOf<Long?>(null) }
    var repeat by remember { mutableStateOf("NONE") }
    var asCall by remember { mutableStateOf(false) }
    val chosen = at

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue Erinnerung") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Woran soll ich dich erinnern?") })
                Text("Wann?", color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { at = System.currentTimeMillis() + 60_000 }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("1 Min") }
                    OutlinedButton(onClick = { at = System.currentTimeMillis() + 3_600_000 }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("1 Std") }
                    OutlinedButton(onClick = {
                        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
                        at = c.timeInMillis
                    }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Morgen 9") }
                }
                OutlinedButton(onClick = {
                    val now = Calendar.getInstance()
                    TimePickerDialog(ctx, { _, h, m ->
                        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0) }
                        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
                        at = c.timeInMillis
                    }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                }) { Text("Uhrzeit wählen …") }
                if (chosen != null) Text("→ ${fmt(chosen)}", color = Cyan)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("NONE" to "Einmal", "DAILY" to "Täglich", "WEEKLY" to "Wöchentl.").forEach { (k, label) ->
                        FilterChip(selected = repeat == k, onClick = { repeat = k }, label = { Text(label, fontSize = 12.sp) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Als JARVIS-Anruf", Modifier.weight(1f))
                    Switch(checked = asCall, onCheckedChange = { asCall = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && chosen != null, onClick = { onSave(text.trim(), chosen!!, repeat, asCall) }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

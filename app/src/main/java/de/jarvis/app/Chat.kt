package de.jarvis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class Speaker(ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    init {
        tts = TextToSpeech(ctx.applicationContext) { s ->
            if (s == TextToSpeech.SUCCESS) { tts?.language = Locale.GERMAN; ready = true }
        }
    }
    fun speak(t: String) { if (ready) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "jarvis") }
    fun stop() { tts?.stop() }
    fun shutdown() { tts?.shutdown() }
}

@Composable
fun KeyField(onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var k by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = k, onValueChange = { k = it }, singleLine = true,
            label = { Text("Anthropic-API-Schlüssel (sk-ant-…)") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = k.trim().length > 20, onClick = { Secrets.setApiKey(ctx, k.trim()); k = ""; onSaved() }) { Text("Speichern") }
    }
}

@Composable
fun ChatScreen(listenTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(Secrets.apiKey(ctx).isNotBlank()) }
    var listening by remember { mutableStateOf(false) }
    val speaker = remember { Speaker(ctx) }
    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val listState = rememberLazyListState()
    DisposableEffect(Unit) { onDispose { speaker.shutdown(); recognizer?.destroy() } }

    fun send(t: String) {
        val q = t.trim()
        if (q.isEmpty() || busy) return
        Ai.chat.add(ChatMsg(true, q)); input = ""; busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { Ai.ask(ctx.applicationContext, q) }
            Ai.chat.add(ChatMsg(false, r.text, r.sources))
            busy = false
            if (ttsEnabled(ctx)) speaker.speak(r.text)
        }
    }

    fun startListening() {
        if (recognizer == null) { Ai.chat.add(ChatMsg(false, "Spracherkennung ist auf diesem Gerät nicht verfügbar.")); return }
        if (!Perms.isGranted(ctx, Step.MIC)) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        speaker.stop()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { listening = false }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                listening = false
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) send(t)
            }
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE"))
        listening = true
    }

    LaunchedEffect(listenTick) { if (listenTick > 0 && hasKey) startListening() }
    LaunchedEffect(Ai.chat.size) { if (Ai.chat.isNotEmpty()) listState.animateScrollToItem(Ai.chat.size - 1) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (!hasKey) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("KI einrichten", color = Cyan)
                    Text("Einmalig brauche ich deinen Anthropic-API-Schlüssel (console.anthropic.com → API Keys). Er wird verschlüsselt auf dem Handy gespeichert.", fontSize = 13.sp)
                    KeyField { hasKey = true }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (Ai.chat.isEmpty()) item {
                Text("Tippe auf 🎤 und frag mich etwas, z. B. „Was habe ich morgen?“ oder „Erinnere mich in einer Stunde.“", color = Color.Gray, modifier = Modifier.padding(8.dp))
            }
            items(Ai.chat) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Card(Modifier.widthIn(max = 300.dp),
                        colors = CardDefaults.cardColors(containerColor = if (m.fromUser) Color(0xFF0E3A4A) else Color(0xFF121A2B))) {
                        Column(Modifier.padding(12.dp)) {
                            Text(m.text)
                            if (m.sources.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text("Quellen:", fontSize = 11.sp, color = Cyan)
                                m.sources.forEach { Text("• $it", fontSize = 11.sp, color = Color.Gray) }
                            }
                        }
                    }
                }
            }
        }
        if (busy) Text("JARVIS denkt nach …", color = Color.Gray, fontSize = 12.sp)
        if (listening) Text("Ich höre zu …", color = Cyan, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Schreib mit JARVIS …") }, singleLine = true)
            Button(onClick = { if (listening) recognizer?.stopListening() else startListening() },
                contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (listening) "⏹" else "🎤") }
            Button(onClick = { send(input) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("➤") }
        }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val memories by AppDb.get(ctx).memories().observeAll().collectAsState(initial = emptyList())
    var tts by remember { mutableStateOf(ttsEnabled(ctx)) }
    var hasKey by remember { mutableStateOf(Secrets.apiKey(ctx).isNotBlank()) }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Einstellungen", style = MaterialTheme.typography.titleLarge, color = Cyan) }
        item {
            Text("KI-Schlüssel: " + if (hasKey) "gespeichert ✓" else "fehlt")
            KeyField { hasKey = true }
            if (hasKey) TextButton(onClick = { Secrets.setApiKey(ctx, ""); hasKey = false }) { Text("Schlüssel entfernen") }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Antworten vorlesen", Modifier.weight(1f))
                Switch(checked = tts, onCheckedChange = { tts = it; setTtsEnabled(ctx, it) })
            }
        }
        item { OutlinedButton(onClick = { Ai.reset() }) { Text("Gespräch zurücksetzen") } }
        item { Text("Gedächtnis", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        if (memories.isEmpty()) item { Text("Nichts gespeichert. Sag z. B. „Merke dir, dass …“.", color = Color.Gray) }
        items(memories, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.text, Modifier.weight(1f))
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { AppDb.get(ctx).memories().delete(m.id) } }) { Text("✕") }
                }
            }
        }
    }
}

@Composable
fun ConfirmHost() {
    val req by Confirm.pending.collectAsState()
    req?.let { r ->
        AlertDialog(
            onDismissRequest = { r.result.complete(false) },
            title = { Text("Bestätigen") },
            text = { Text(r.text) },
            confirmButton = { TextButton(onClick = { r.result.complete(true) }) { Text("Ja") } },
            dismissButton = { TextButton(onClick = { r.result.complete(false) }) { Text("Abbrechen") } })
    }
}

@Composable
fun MainShell(resumeTick: Int, listenTick: Int, onRecheck: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(listenTick) { if (listenTick > 0) tab = 0 }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ChatScreen(listenTick)
                1 -> HomeScreen(resumeTick, onRecheck)
                else -> SettingsScreen()
            }
        }
        NavigationBar {
            listOf("💬" to "Chat", "⏰" to "Erinnerungen", "⚙️" to "Einstellungen").forEachIndexed { i, (icon, label) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icon) }, label = { Text(label, fontSize = 11.sp) })
            }
        }
    }
    ConfirmHost()
}

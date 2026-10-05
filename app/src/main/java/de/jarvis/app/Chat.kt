package de.jarvis.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KeyField(onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var k by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = k, onValueChange = { k = it }, singleLine = true,
            label = { Text("Gemini-Schlüssel (AIza…)") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = k.trim().length > 20, onClick = { Secrets.setApiKey(ctx, k.trim()); k = ""; onSaved() }) { Text("Speichern") }
    }
}

@Composable
fun GetKeyButton() {
    val ctx = LocalContext.current
    OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }) {
        Text("Kostenlosen Schlüssel holen")
    }
}

@Composable
fun AiKeyScreen(onSaved: () -> Unit, onSkip: () -> Unit) = Centered {
    Text("KI verbinden", style = MaterialTheme.typography.headlineMedium, color = Cyan)
    Spacer(Modifier.height(12.dp))
    Text("Meine KI läuft über Google Gemini. Den Schlüssel dafür gibt es kostenlos: Tippe auf den Button, melde dich an, tippe auf „Create API key“ und kopiere ihn. Dann hier einfügen.", textAlign = TextAlign.Center)
    Spacer(Modifier.height(16.dp))
    GetKeyButton()
    Spacer(Modifier.height(8.dp))
    KeyField(onSaved)
    TextButton(onClick = onSkip) { Text("Später") }
}

@Composable
fun GoogleScreen(error: String, onConnect: () -> Unit, onSkip: () -> Unit) = Centered {
    Text("Google-Konto", style = MaterialTheme.typography.headlineMedium, color = Cyan)
    Spacer(Modifier.height(12.dp))
    Text("Für Gmail melde dich einmal mit Google an. Ich sehe dein Passwort nie.", textAlign = TextAlign.Center)
    if (error.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text("Das hat nicht geklappt. Wahrscheinlich ist die Google-Einrichtung (OAuth) noch nicht fertig.", color = Amber, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onConnect) { Text("Google-Konto verbinden") }
    TextButton(onClick = onSkip) { Text("Später") }
}

@Composable
fun ChatScreen(listenTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(Secrets.apiKey(ctx).isNotBlank()) }
    var listening by remember { mutableStateOf(false) }
    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val listState = rememberLazyListState()
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    fun send(t: String) {
        val q = t.trim()
        if (q.isEmpty() || busy) return
        Ai.chat.add(ChatMsg(true, q)); input = ""; busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { Ai.ask(ctx.applicationContext, q) }
            Ai.chat.add(ChatMsg(false, r.text, r.sources))
            busy = false
            if (ttsEnabled(ctx)) Voice.speak(ctx.applicationContext, r.text)
        }
    }

    fun startListening() {
        if (recognizer == null) { Ai.chat.add(ChatMsg(false, "Spracherkennung ist auf diesem Gerät nicht verfügbar.")); return }
        if (!Perms.isGranted(ctx, Step.MIC)) { micLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        Voice.stop()
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("JARVIS", color = Cyan, fontSize = 22.sp, letterSpacing = 4.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, CallActivity::class.java).putExtra("outgoing", true)) }) { Text("📞 Anruf") }
        }
        Spacer(Modifier.height(8.dp))
        if (!hasKey) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("KI einrichten", color = Cyan)
                    Text("Einmalig brauche ich einen kostenlosen Google-Gemini-Schlüssel.", fontSize = 13.sp)
                    GetKeyButton()
                    KeyField { hasKey = true }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (Ai.chat.isEmpty()) item {
                Text("Tippe auf 🎤 und frag mich etwas, z. B. „Was habe ich morgen?“ oder „Ruf mich in 10 Minuten an.“", color = Color.Gray, modifier = Modifier.padding(8.dp))
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
private fun SwitchRow(label: String, key: String, default: Boolean, onChange: ((Boolean) -> Unit)? = null) {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf(appPrefs(ctx).getBoolean(key, default)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = v, onCheckedChange = { v = it; appPrefs(ctx).edit().putBoolean(key, it).apply(); onChange?.invoke(it) })
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val memories by AppDb.get(ctx).memories().observeAll().collectAsState(initial = emptyList())
    var watches by remember { mutableStateOf(Watches.all(ctx)) }
    var hasKey by remember { mutableStateOf(Secrets.apiKey(ctx).isNotBlank()) }
    var googleOk by remember { mutableStateOf(GoogleAuth.connected(ctx)) }
    var googleErr by remember { mutableStateOf("") }
    val connect = rememberGoogleConnect { ok, msg -> googleOk = ok; googleErr = if (ok) "" else msg }
    var voice by remember { mutableStateOf(appPrefs(ctx).getString("voice_name", "Charon") ?: "Charon") }
    var robot by remember { mutableFloatStateOf(appPrefs(ctx).getFloat("robot", 0.6f)) }

    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Einstellungen", style = MaterialTheme.typography.titleLarge, color = Cyan) }

        item { Text("KI", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Gemini-Schlüssel: " + if (hasKey) "gespeichert ✓" else "fehlt")
                GetKeyButton()
                KeyField { hasKey = true }
                if (hasKey) TextButton(onClick = { Secrets.setApiKey(ctx, ""); hasKey = false }) { Text("Schlüssel entfernen") }
            }
        }

        item { Text("Google", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Google-Konto: " + if (googleOk) "verbunden ✓" else "nicht verbunden")
                if (googleErr.isNotBlank()) Text("Verbindung fehlgeschlagen: $googleErr", color = Amber, fontSize = 12.sp)
                if (!googleOk) Button(onClick = connect) { Text("Google-Konto verbinden") }
                else TextButton(onClick = { GoogleAuth.setConnected(ctx, false); googleOk = false }) { Text("Trennen") }
            }
        }

        item { Text("Stimme", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        item { SwitchRow("Antworten vorlesen", "tts", true) }
        item { SwitchRow("Premium-Stimme (Gemini, limitiert)", "voice_gemini", true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Charon", "Orus", "Algenib").forEach {
                    FilterChip(selected = voice == it, onClick = { voice = it; appPrefs(ctx).edit().putString("voice_name", it).apply() }, label = { Text(it) })
                }
            }
        }
        item {
            Text("Roboter-Effekt: ${(robot * 100).toInt()} %")
            Slider(value = robot, onValueChange = { robot = it }, onValueChangeFinished = { appPrefs(ctx).edit().putFloat("robot", robot).apply() })
            OutlinedButton(onClick = { scope.launch { Voice.speak(ctx.applicationContext, "Guten Tag. Ich bin JARVIS. Alle Systeme laufen einwandfrei.") } }) { Text("Stimme testen") }
        }

        item { Text("Hey JARVIS & Proaktiv", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        item {
            SwitchRow("„Hey JARVIS“ immer hören (Beta)", "wake", false) {
                if (it) { if (Perms.isGranted(ctx, Step.MIC)) ListenService.start(ctx) } else ListenService.stop(ctx)
            }
        }
        item { SwitchRow("Termine ankündigen", "pro_cal", true) }
        item { SwitchRow("Wichtige neue E-Mails melden", "pro_mail", true) }
        item { SwitchRow("Wichtiges als JARVIS-Anruf melden", "pro_as_call", false) }

        item { Text("Beobachtungen", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        if (watches.isEmpty()) item { Text("Keine. Sag z. B. „Beobachte die Bahnstreik-Lage.“", color = Color.Gray) }
        items(watches, key = { it.id }) { w ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${w.query} (alle ${w.everyHours} Std.)", Modifier.weight(1f))
                    TextButton(onClick = { Watches.remove(ctx, w.id); watches = Watches.all(ctx) }) { Text("✕") }
                }
            }
        }

        item { Text("Gedächtnis", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        item { OutlinedButton(onClick = { Ai.reset() }) { Text("Gespräch zurücksetzen") } }
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
            title = { Text(r.title) },
            text = { Text(r.text) },
            confirmButton = { TextButton(onClick = { r.result.complete(true) }) { Text(r.yes) } },
            dismissButton = { TextButton(onClick = { r.result.complete(false) }) { Text(r.no) } })
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

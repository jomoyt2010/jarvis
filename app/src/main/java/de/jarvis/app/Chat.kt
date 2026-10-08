package de.jarvis.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------- Hilfen ----------
private fun AnnotatedString.Builder.inl(t: String) {
    var i = 0
    for (m in Regex("\\*\\*(.+?)\\*\\*").findAll(t)) {
        append(t.substring(i, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        i = m.range.last + 1
    }
    append(t.substring(i))
}

/** Mini-Markdown: **fett**, Ueberschriften, Listen. */
fun md(s: String): AnnotatedString = buildAnnotatedString {
    val lines = s.lines()
    lines.forEachIndexed { idx, line ->
        val head = Regex("^#{1,3}\\s+(.*)").matchEntire(line)
        val bullet = Regex("^\\s*[-*]\\s+(.*)").matchEntire(line)
        when {
            head != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Cyan)) { inl(head.groupValues[1]) }
            bullet != null -> { append("• "); inl(bullet.groupValues[1]) }
            else -> inl(line)
        }
        if (idx < lines.lastIndex) append("\n")
    }
}

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
fun GoogleScreen(error: String, onConnect: () -> Unit, onSkip: () -> Unit) = Centered {
    Text("Google-Konto", style = MaterialTheme.typography.headlineMedium, color = Cyan)
    Spacer(Modifier.height(12.dp))
    Text("Für Gmail melde dich einmal mit Google an. Ich sehe dein Passwort nie.", textAlign = TextAlign.Center)
    if (error.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(error, color = Amber, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onConnect) { Text("Google-Konto verbinden") }
    TextButton(onClick = onSkip) { Text("Später") }
}

// ---------- Chat ----------
@Composable
fun ChatScreen(listenTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(Secrets.hasAi(ctx)) }
    var listening by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val pending = remember { mutableStateListOf<Attachment>() }
    val recognizer = remember { if (Recog.available(ctx)) Recog.create(ctx) else null }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val listState = rememberLazyListState()
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    fun addUris(uris: List<Uri>) {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { uris.map { Attachments.load(ctx, it) to Attachments.name(ctx, it) } }
            loaded.forEach { (a, n) ->
                if (a == null) Ai.chat.add(ChatMsg(false, "„$n“ kann ich nicht lesen. Unterstützt: Fotos, PDF, Text- und Audiodateien (bis 12 MB)."))
                else pending.add(a)
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addUris(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { addUris(listOf(it)) } }

    fun takePhoto() {
        val dir = File(ctx.cacheDir, "images").apply { mkdirs() }
        val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(dir, "foto_${System.currentTimeMillis()}.jpg"))
        cameraUri = u
        camera.launch(u)
    }

    fun send(t: String, viaMic: Boolean = false) {
        val q = t.trim()
        if ((q.isEmpty() && pending.isEmpty()) || busy) return
        val files = pending.toList(); pending.clear()
        Ai.chat.add(ChatMsg(true, q, files = files.map { it.name }, thumbs = files.mapNotNull { it.thumb }))
        input = ""; busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { Ai.ask(ctx.applicationContext, q, files, viaMic) }
            Ai.chat.add(ChatMsg(false, r.text, r.sources, model = r.model))
            busy = false
            if (ttsEnabled(ctx) && (viaMic || r.text.length <= 400)) Voice.speak(ctx.applicationContext, r.text)
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
                if (t != null) send(t, true)
            }
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.stt(ctx)))
        listening = true
    }

    LaunchedEffect(listenTick) { if (listenTick > 0 && hasKey) startListening() }
    LaunchedEffect(Ai.chat.size, imeUp) { if (Ai.chat.isNotEmpty()) listState.animateScrollToItem(Ai.chat.size - 1) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("JARVIS", color = Cyan, fontSize = 22.sp, letterSpacing = 4.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, CallActivity::class.java).putExtra("outgoing", true)) }) { Text("📞 Anruf") }
        }
        Spacer(Modifier.height(6.dp))
        if (!hasKey) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("KI einrichten", color = Cyan)
                    GroqKeyBlock { hasKey = true }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (Ai.chat.isEmpty()) item {
                Text("Schreib mir, häng ein Foto oder PDF an (📎) oder tippe auf 🎤. Zum Beispiel: „Was habe ich morgen?“ oder „Ruf mich in 10 Minuten an.“", color = Color.Gray, modifier = Modifier.padding(8.dp))
            }
            items(Ai.chat) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Card(if (m.fromUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth(0.95f),
                        colors = CardDefaults.cardColors(containerColor = if (m.fromUser) Color(0xFF0E3A4A) else Color(0xFF121A2B))) {
                        SelectionContainer {
                            Column(Modifier.padding(12.dp)) {
                                if (m.thumbs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                                    m.thumbs.forEach { Image(it.asImageBitmap(), null, Modifier.size(96.dp).clip(RoundedCornerShape(8.dp))) }
                                }
                                val others = m.files.size - m.thumbs.size
                                if (others > 0) Text("📎 ${m.files.takeLast(others).joinToString(", ")}", fontSize = 12.sp, color = Color.Gray)
                                if (m.text.isNotBlank()) Text(if (m.fromUser) AnnotatedString(m.text) else md(m.text))
                                if (m.sources.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("Quellen: " + m.sources.joinToString(" · "), fontSize = 11.sp, color = Color.Gray)
                                }
                                if (!m.fromUser && m.model.isNotBlank()) Text(m.model, fontSize = 10.sp, color = Color(0xFF55657A), modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
        if (busy) Text("JARVIS denkt nach …", color = Color.Gray, fontSize = 12.sp)
        if (listening) Text("Ich höre zu …", color = Cyan, fontSize = 12.sp)
        if (pending.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pending.toList().forEach { a ->
                Card { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                    a.thumb?.let { Image(it.asImageBitmap(), null, Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))) }
                    Text(a.name.take(18), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                    TextButton(onClick = { pending.remove(a) }) { Text("✕") }
                } }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box {
                TextButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("📎", fontSize = 20.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Foto aufnehmen") }, onClick = { menu = false; takePhoto() })
                    DropdownMenuItem(text = { Text("Foto oder Datei wählen") }, onClick = { menu = false; pick.launch(arrayOf("image/*", "application/pdf", "text/*", "audio/*")) })
                }
            }
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Nachricht an JARVIS …") }, maxLines = 6)
            TextButton(onClick = { if (listening) recognizer?.stopListening() else startListening() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (listening) "⏹" else "🎤", fontSize = 20.sp)
            }
            Button(onClick = { send(input) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("➤") }
        }
    }
}

// ---------- Einstellungen ----------
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
private fun SecretRow(label: String, name: String, hint: String) {
    val ctx = LocalContext.current
    var has by remember { mutableStateOf(Secrets.get(ctx, name).isNotBlank()) }
    var v by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label: " + if (has) "gespeichert ✓" else "nicht gesetzt (optional)", fontSize = 13.sp)
        Text(hint, fontSize = 11.sp, color = Color.Gray)
        OutlinedTextField(value = v, onValueChange = { v = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Schlüssel einfügen") })
        Row {
            Button(enabled = v.trim().length > 10, onClick = { Secrets.put(ctx, name, v.trim()); v = ""; has = true }) { Text("Speichern") }
            if (has) TextButton(onClick = { Secrets.put(ctx, name, ""); has = false }) { Text("Entfernen") }
        }
    }
}

@Composable
fun SettingsScreen(resumeTick: Int = 0) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val memories by AppDb.get(ctx).memories().observeAll().collectAsState(initial = emptyList())
    var watches by remember { mutableStateOf(Watches.all(ctx)) }
    var hasKey by remember { mutableStateOf(Secrets.apiKey(ctx).isNotBlank()) }
    var aiTest by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(appPrefs(ctx).getString("ai_provider", "auto") ?: "auto") }
    var googleOk by remember { mutableStateOf(GoogleAuth.connected(ctx)) }
    var googleErr by remember { mutableStateOf("") }
    val connect = rememberGoogleConnect { ok, msg -> googleOk = ok; googleErr = if (ok) "" else msg }
    var engine by remember { mutableStateOf(appPrefs(ctx).getString("voice_engine", "auto") ?: "auto") }
    var edgeVoice by remember { mutableStateOf(appPrefs(ctx).getString("voice_edge", "de-DE-ConradNeural") ?: "de-DE-ConradNeural") }
    var robot by remember { mutableFloatStateOf(appPrefs(ctx).getFloat("robot", 0.5f)) }
    var testInfo by remember { mutableStateOf("") }
    val sha = remember { GoogleAuth.sha1(ctx) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(8.dp)); Text("Einstellungen", style = MaterialTheme.typography.titleLarge, color = Cyan) }

        item { Text("KI (Router + Experten, alles kostenlos)", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        item { Text("JARVIS fragt zuerst ein sehr schnelles Modell. Schwere Aufgaben gibt es automatisch an stärkere Modelle weiter. Mit mehreren Schlüsseln stößt du viel seltener an ein Gratis-Limit. Empfohlen: Groq und Mistral.", fontSize = 11.sp, color = Color.Gray) }
        Oai.all.forEach { p -> item { SecretBlock(p.label + "-Schlüssel", p.keyName, p.keyUrl, p.getLabel) } }
        item { SecretBlock("Gemini-Schlüssel", "gemini_key", "https://aistudio.google.com/apikey", "Kostenlosen Gemini-Schlüssel holen") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { scope.launch { aiTest = "Teste …"; aiTest = Ai.diagnose(ctx.applicationContext) } }) { Text("KI testen") }
                if (aiTest.isNotBlank()) SelectionContainer { Text(aiTest, fontSize = 11.sp, color = Color.Gray) }
            }
        }

        item { Text("Google & Gmail", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Google-Konto: " + if (googleOk) "verbunden ✓" else "nicht verbunden")
                if (googleErr.isNotBlank()) Text(googleErr, color = Amber, fontSize = 12.sp)
                if (!googleOk) Button(onClick = connect) { Text("Google-Konto verbinden") }
                else TextButton(onClick = { GoogleAuth.setConnected(ctx, false); googleOk = false }) { Text("Trennen") }
                Text("Für die Google-Einrichtung brauchst du diese Werte:", fontSize = 11.sp, color = Color.Gray)
                SelectionContainer { Text("Paketname: ${ctx.packageName}\nSHA-1: $sha", fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SHA-1", sha))
                }) { Text("SHA-1 kopieren") }
            }
        }

        item { Text("Stimme", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        item { SwitchRow("Antworten vorlesen", "tts", true) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Stimmen-Dienst (alle kostenlos)", fontSize = 13.sp)
                val opts = listOf("auto" to "Automatisch", "edge" to "Edge-KI", "groqtts" to "Groq-KI (EN)", "gemini" to "Gemini", "google" to "Google Chirp", "polly" to "Polly Brian/Hans", "phone" to "Handy")
                opts.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (k, label) ->
                            FilterChip(selected = engine == k, onClick = { engine = k; appPrefs(ctx).edit().putString("voice_engine", k).apply() },
                                label = { Text(label, fontSize = 12.sp) })
                        }
                    }
                }
                VoiceLangAndEdge()
            }
        }
        item {
            Text("Roboter-Effekt: ${(robot * 100).toInt()} %")
            Slider(value = robot, onValueChange = { robot = it }, onValueChangeFinished = { appPrefs(ctx).edit().putFloat("robot", robot).apply() })
            OutlinedButton(onClick = {
                scope.launch {
                    testInfo = "Erzeuge Stimme …"
                    Voice.speak(ctx.applicationContext, "Guten Tag. Ich bin JARVIS. Alle Systeme laufen einwandfrei.")
                    testInfo = "Gesprochen mit: ${Voice.lastEngine.ifBlank { "nichts" }}" + if (Voice.lastError.isNotBlank()) " · Probleme: ${Voice.lastError}" else ""
                }
            }) { Text("Stimme testen") }
            if (testInfo.isNotBlank()) Text(testInfo, fontSize = 11.sp, color = Color.Gray)
            OutlinedButton(onClick = { scope.launch { testInfo = "Teste alle Stimmen …"; testInfo = Voice.diagnose(ctx.applicationContext) } }) { Text("Alle Stimmen testen") }
        }
        item { SecretRow("Google-Cloud-TTS-Schlüssel", "gtts_key", "Optional: Chirp-3-HD-Stimme, 1 Mio. Zeichen/Monat gratis (Google-Cloud-Konto mit Rechnungskonto nötig).") }
        item { VoiceIdField("Google-Stimme (z. B. de-DE-Chirp3-HD-Charon)", "voice_google") }

        item { Text("Handy-Steuerung & Assistent", style = MaterialTheme.typography.titleMedium, color = Cyan) }
        item { PermRow(Step.ACCESSIBILITY, "Apps bedienen (Bedienungshilfe)", resumeTick) }
        item { PermRow(Step.OVERLAY, "Apps im Hintergrund öffnen", resumeTick) }
        item { PermRow(Step.ASSISTANT, "Standard-Assistent (wie Google Assistant)", resumeTick) }
        item { Text("Beispiel: „Öffne YouTube und suche nach Lo-Fi Beats.“", fontSize = 11.sp, color = Color.Gray) }

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
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ConfirmHost() {
    val req by Confirm.pending.collectAsState()
    req?.let { r ->
        AlertDialog(
            onDismissRequest = { r.result.complete(false) },
            title = { Text(r.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(r.text) } },
            confirmButton = { TextButton(onClick = { r.result.complete(true) }) { Text(r.yes) } },
            dismissButton = { TextButton(onClick = { r.result.complete(false) }) { Text(r.no) } })
    }
}

@Composable
fun MainShell(resumeTick: Int, listenTick: Int, onRecheck: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(listenTick) { if (listenTick > 0) tab = 0 }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ChatScreen(listenTick)
                1 -> HomeScreen(resumeTick, onRecheck)
                else -> SettingsScreen(resumeTick)
            }
        }
        if (!imeUp) NavigationBar {
            listOf("💬" to "Chat", "⏰" to "Erinnerungen", "⚙️" to "Einstellungen").forEachIndexed { i, (icon, label) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icon) }, label = { Text(label, fontSize = 11.sp) })
            }
        }
    }
    ConfirmHost()
}

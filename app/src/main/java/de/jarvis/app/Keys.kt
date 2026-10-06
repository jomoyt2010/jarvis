package de.jarvis.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.launch

@Composable
fun GetUrlButton(url: String, label: String) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text(label) }
}

/** Schluessel-Block: Status, Link zum kostenlosen Schluessel, Eingabe, Speichern. */
@Composable
fun SecretBlock(label: String, name: String, url: String, getLabel: String, onSaved: () -> Unit = {}) {
    val ctx = LocalContext.current
    var has by remember { mutableStateOf(Secrets.get(ctx, name).isNotBlank()) }
    var v by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label: " + if (has) "gespeichert ✓" else "nicht gesetzt")
        GetUrlButton(url, getLabel)
        OutlinedTextField(value = v, onValueChange = { v = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Schlüssel einfügen") })
        Row {
            Button(enabled = v.trim().length > 15, onClick = { Secrets.put(ctx, name, v.trim()); v = ""; has = true; onSaved() }) { Text("Speichern") }
            if (has) TextButton(onClick = { Secrets.put(ctx, name, ""); has = false }) { Text("Entfernen") }
        }
    }
}

@Composable
fun GroqKeyBlock(onSaved: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Meine KI läuft über Groq: kostenlos, ohne Kreditkarte, sehr schnell und in jedem Land verfügbar. Tippe auf den Button, melde dich an (z. B. mit Google), tippe auf „Create API Key“ und kopiere den Schlüssel.", fontSize = 13.sp)
        SecretBlock("Groq-Schlüssel", "groq_key", "https://console.groq.com/keys", "Kostenlosen Groq-Schlüssel holen", onSaved)
    }
}

@Composable
fun AiKeyScreen(onSaved: () -> Unit, onSkip: () -> Unit) = Centered {
    Text("KI verbinden", style = MaterialTheme.typography.headlineMedium, color = Cyan)
    Spacer(Modifier.height(12.dp))
    GroqKeyBlock(onSaved)
    Text("Alternativ geht auch ein Google-Gemini-Schlüssel (in den Einstellungen).", fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    TextButton(onClick = onSkip) { Text("Später") }
}

/** Sprache + Edge-Stimme. */
@Composable
fun VoiceLangAndEdge() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lang by remember { mutableStateOf(appPrefs(ctx).getString("lang", "de") ?: "de") }
    val en = lang == "en"
    val key = if (en) "voice_edge_en" else "voice_edge"
    val list = if (en) listOf("en-GB-ThomasNeural" to "Thomas (UK)", "en-GB-RyanNeural" to "Ryan (UK)", "en-US-AndrewMultilingualNeural" to "Andrew")
    else listOf("de-DE-ConradNeural" to "Conrad", "de-DE-KillianNeural" to "Killian", "de-DE-FlorianMultilingualNeural" to "Florian",
        "en-US-AndrewMultilingualNeural" to "Andrew*", "en-US-BrianMultilingualNeural" to "Brian*")
    var sel by remember(en) { mutableStateOf(appPrefs(ctx).getString(key, list[0].first) ?: list[0].first) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Sprache von JARVIS (Antworten, Sprachausgabe, Spracherkennung)", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("de" to "Deutsch", "en" to "English (britisch)").forEach { (k, l) ->
                FilterChip(selected = lang == k, onClick = { lang = k; appPrefs(ctx).edit().putString("lang", k).apply() }, label = { Text(l, fontSize = 12.sp) })
            }
        }
        Text("Edge-Stimme" + if (!en) " (* mehrsprachig, experimentell)" else "", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        list.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (k, l) ->
                    FilterChip(selected = sel == k, onClick = { sel = k; appPrefs(ctx).edit().putString(key, k).apply(); scope.launch { Voice.speak(ctx.applicationContext, if (en) "Good day. I am JARVIS, at your service." else "Guten Tag. Ich bin JARVIS, zu Ihren Diensten.") } }, label = { Text(l, fontSize = 12.sp) })
                }
            }
        }
    }
}

/** Zeile fuer Sonderberechtigungen (Handy-Steuerung, Overlay, Assistent). */
@Composable
fun PermRow(step: Step, label: String, resumeTick: Int) {
    val ctx = LocalContext.current
    val ok = remember(resumeTick) { Perms.isGranted(ctx, step) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(if (ok) "aktiv ✓" else "nicht aktiv", fontSize = 11.sp, color = if (ok) Green else Color.Gray)
        }
        if (!ok) OutlinedButton(onClick = { Perms.request(ctx, step, false) {} }) { Text("Einrichten") }
    }
}

@Composable
fun VoiceIdField(label: String, key: String) {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf(appPrefs(ctx).getString(key, "") ?: "") }
    OutlinedTextField(value = v, onValueChange = { v = it; appPrefs(ctx).edit().putString(key, it.trim()).apply() },
        singleLine = true, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
}

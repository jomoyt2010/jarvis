package de.jarvis.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProfileAndLearnSettings(resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(appPrefs(ctx).getString("p_name", "") ?: "") }
    var about by remember { mutableStateOf(appPrefs(ctx).getString("p_about", "") ?: "") }
    var formal by remember { mutableStateOf(appPrefs(ctx).getBoolean("p_formal", false)) }
    var unlock by remember { mutableStateOf(appPrefs(ctx).getBoolean("admin_unlock", false)) }
    var pin by remember { mutableStateOf("") }
    var hasPin by remember { mutableStateOf(Secrets.get(ctx, "lock_pin").isNotBlank()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); if (LearnMode.running) tick++ } }
    val notes = remember(tick, resumeTick, LearnMode.status) { PhoneKnowledge.all(ctx) }
    var noteVersion by remember { mutableIntStateOf(0) }
    val shown = remember(tick, resumeTick, noteVersion, LearnMode.status) { PhoneKnowledge.all(ctx) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Persönlich", style = MaterialTheme.typography.titleMedium, color = Cyan)
        OutlinedTextField(value = name, onValueChange = { name = it; appPrefs(ctx).edit().putString("p_name", it.trim()).apply() },
            label = { Text("Wie soll JARVIS dich nennen?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("JARVIS soll mich siezen", Modifier.weight(1f))
            Switch(checked = formal, onCheckedChange = { formal = it; appPrefs(ctx).edit().putBoolean("p_formal", it).apply() })
        }
        OutlinedTextField(value = about, onValueChange = { about = it; appPrefs(ctx).edit().putString("p_about", it).apply() },
            label = { Text("Über dich (Beruf, Interessen, Alltag, Vorlieben)") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
        Button(onClick = { ctx.startActivity(Intent(ctx, CallActivity::class.java).putExtra("outgoing", true).putExtra("interview", true)) }) {
            Text("Kennenlern-Gespräch starten")
        }
        Text("JARVIS stellt dir Fragen und merkt sich die Antworten. Alles ist unter „Gedächtnis“ sichtbar und löschbar.", fontSize = 11.sp, color = Color.Gray)

        Text("Handy-Lernmodus", style = MaterialTheme.typography.titleMedium, color = Cyan, modifier = Modifier.padding(top = 8.dp))
        Text("JARVIS darf 5 Minuten lang dein Handy erkunden (Apps öffnen, lesen, scrollen) und speichert, wie alles bedient wird. Er kauft, sendet, löscht und ändert dabei nichts.", fontSize = 11.sp, color = Color.Gray)
        if (!LearnMode.running) Button(onClick = {
            scope.launch {
                if (Confirm.ask("JARVIS erkundet jetzt 5 Minuten lang dein Handy. Er öffnet Apps und liest Bildschirme, kauft, sendet, löscht und ändert aber nichts. Starten?", "STARTEN", "ABBRECHEN", "Lernmodus")) LearnMode.start(ctx)
            }
        }) { Text("Lernmodus starten (5 Minuten)") }
        else OutlinedButton(onClick = { LearnMode.stop() }) { Text("Lernmodus stoppen") }
        if (LearnMode.status.isNotBlank()) Text(LearnMode.status, fontSize = 12.sp, color = Cyan)
        Text("Gelernt: ${shown.size} Notizen", fontSize = 12.sp)
        shown.takeLast(25).reversed().forEachIndexed { _, (t, n) ->
            Text("• $t: $n", fontSize = 11.sp, color = Color.Gray)
        }
        if (shown.isNotEmpty()) TextButton(onClick = { PhoneKnowledge.clear(ctx); noteVersion++ }) { Text("Gelerntes löschen") }

        Text("Handy-Verwaltung", style = MaterialTheme.typography.titleMedium, color = Cyan, modifier = Modifier.padding(top = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("JARVIS darf das Handy entsperren", Modifier.weight(1f))
            Switch(checked = unlock, onCheckedChange = { unlock = it; appPrefs(ctx).edit().putBoolean("admin_unlock", it).apply() })
        }
        Text("Optional: PIN fürs Entsperren. Sie wird verschlüsselt gespeichert und nur dafür verwendet. Neustart fragt immer vorher nach. Ohne PIN wischt JARVIS nur den Sperrbildschirm hoch.", fontSize = 11.sp, color = Color.Gray)
        Text("PIN: " + if (hasPin) "gespeichert ✓" else "nicht gesetzt", fontSize = 13.sp)
        OutlinedTextField(value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(12) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            label = { Text("PIN eingeben") }, modifier = Modifier.fillMaxWidth())
        Row {
            Button(enabled = pin.length >= 4, onClick = { Secrets.put(ctx, "lock_pin", pin); pin = ""; hasPin = true }) { Text("PIN speichern") }
            if (hasPin) TextButton(onClick = { Secrets.put(ctx, "lock_pin", ""); hasPin = false }) { Text("PIN löschen") }
        }
    }
}

@Composable
fun PrefSlider(label: String, key: String, min: Int, max: Int, def: Int, unit: String) {
    val ctx = LocalContext.current
    var v by remember { mutableIntStateOf(appPrefs(ctx).getInt(key, def)) }
    fun set(n: Int) { v = n.coerceIn(min, max); appPrefs(ctx).edit().putInt(key, v).apply() }
    Column {
        Text("$label: $v$unit", fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { set(v - 1) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("−") }
            Slider(value = v.toFloat(), onValueChange = { set(Math.round(it)) }, valueRange = min.toFloat()..max.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
            OutlinedButton(onClick = { set(v + 1) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("+") }
        }
    }
}

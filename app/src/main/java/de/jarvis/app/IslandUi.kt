package de.jarvis.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
private fun ToggleRow(label: String, key: String, default: Boolean, onChange: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf(appPrefs(ctx).getBoolean(key, default)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = v, onCheckedChange = { v = it; appPrefs(ctx).edit().putBoolean(key, it).apply(); onChange(it); Island.apply(ctx) })
    }
}

@Composable
private fun IslandSlider(label: String, key: String, min: Int, max: Int, def: Int) {
    val ctx = LocalContext.current
    var v by remember { mutableIntStateOf(appPrefs(ctx).getInt(key, def)) }
    fun set(n: Int) { v = n.coerceIn(min, max); appPrefs(ctx).edit().putInt(key, v).apply(); Island.apply(ctx) }
    Column {
        Text("$label: $v dp", fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { set(v - 1) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("−") }
            Slider(value = v.toFloat(), onValueChange = { set(it.roundToInt()) }, valueRange = min.toFloat()..max.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
            OutlinedButton(onClick = { set(v + 1) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("+") }
        }
    }
}

private fun detectCutout(ctx: android.content.Context, view: View): String {
    val cut = view.rootWindowInsets?.displayCutout ?: return "Keine Kamera-Aussparung erkannt. Bitte von Hand einstellen."
    val rect = (if (Build.VERSION.SDK_INT >= 29 && !cut.boundingRectTop.isEmpty) cut.boundingRectTop else cut.boundingRects.firstOrNull { it.top <= 2 })
        ?: return "Keine Aussparung am oberen Rand erkannt. Bitte von Hand einstellen."
    val d = ctx.resources.displayMetrics.density
    val screenW = ctx.resources.displayMetrics.widthPixels
    val w = (rect.width() / d + 24).roundToInt().coerceIn(60, 260)
    val h = (rect.height() / d + 12).roundToInt().coerceIn(20, 80)
    val x = ((rect.centerX() - screenW / 2f) / d).roundToInt().coerceIn(-200, 200)
    val y = (rect.top / d - 4).roundToInt().coerceIn(-20, 60)
    appPrefs(ctx).edit().putInt("island_w", w).putInt("island_h", h).putInt("island_x", x).putInt("island_y", y).apply()
    Island.apply(ctx)
    return "Erkannt: Aussparung ${rect.width()}×${rect.height()} px → Breite $w dp, Höhe $h dp. Mit der Kalibrierung feinjustieren."
}

private fun openChannel(ctx: android.content.Context, pkg: String, channel: String) {
    val i = if (channel.isNotBlank()) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
    try { ctx.startActivity(i) } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }
}

@Composable
fun IslandSettings(resumeTick: Int) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var info by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dynamic Island", style = MaterialTheme.typography.titleMedium, color = Cyan)
        Text("Zeigt Musik, Timer, Anrufe und JARVIS oben an der Kamera. Tippen klappt sie auf, langes Drücken öffnet JARVIS.", fontSize = 11.sp, color = Color.Gray)
        PermRow(Step.OVERLAY, "Über anderen Apps einblenden", resumeTick)
        PermRow(Step.NOTIFICATION_ACCESS, "Benachrichtigungszugriff (Musik, Timer, Anrufe)", resumeTick)
        ToggleRow("Dynamic Island aktivieren", "island_on", false) {
            if (it && !Settings.canDrawOverlays(ctx)) Perms.request(ctx, Step.OVERLAY, false) {}
        }
        OutlinedButton(onClick = { info = detectCutout(ctx, view); version++ }) { Text("An meine Kamera anpassen (automatisch)") }
        if (info.isNotBlank()) Text(info, fontSize = 11.sp, color = Color.Gray)
        ToggleRow("Kalibrierung (rote Umrandung zeigen)", "island_calib", false)
        ToggleRow("Auch ohne Aktivität anzeigen", "island_idle", false)
        key(version) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                IslandSlider("Breite", "island_w", 60, 260, 126)
                IslandSlider("Höhe (Dicke)", "island_h", 20, 80, 36)
                IslandSlider("Abstand von oben", "island_y", -20, 60, 6)
                IslandSlider("Horizontal verschieben", "island_x", -200, 200, 0)
                IslandSlider("Breite aufgeklappt", "island_ew", 240, 400, 340)
            }
        }
        OutlinedButton(onClick = { IslandHub.note("Hallo, ich bin JARVIS.", 4) }) { Text("Testanzeige") }
        Text("Aus der Benachrichtigungsleiste ausblenden", fontSize = 13.sp)
        ToggleRow("Musik-Benachrichtigung entfernen", "island_hide_media", false)
        ToggleRow("Timer-Benachrichtigung entfernen", "island_hide_timer", false)
        Text("Android erlaubt das nur bei Benachrichtigungen, die nicht fest an die App gebunden sind. Bei den übrigen schaltest du sie in der jeweiligen App ab:", fontSize = 11.sp, color = Color.Gray)
        if (IslandHub.mediaPkg.isNotBlank())
            OutlinedButton(onClick = { openChannel(ctx, IslandHub.mediaPkg, IslandHub.mediaChannel) }) { Text("Musik-Anzeige von ${IslandHub.mediaPkg.substringAfterLast('.')} abschalten") }
        if (IslandHub.timerPkg.isNotBlank())
            OutlinedButton(onClick = { openChannel(ctx, IslandHub.timerPkg, IslandHub.timerChannel) }) { Text("Timer-Anzeige von ${IslandHub.timerPkg.substringAfterLast('.')} abschalten") }
        if (IslandHub.mediaPkg.isBlank() && IslandHub.timerPkg.isBlank())
            Text("Sobald Musik läuft oder ein Timer startet, erscheinen hier die Abschalt-Knöpfe.", fontSize = 11.sp, color = Color.Gray)
    }
}

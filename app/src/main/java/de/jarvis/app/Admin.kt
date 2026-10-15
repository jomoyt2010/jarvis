package de.jarvis.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PowerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Wird gesetzt, sobald fremde Inhalte (Web, Mail, Bildschirm) gelesen wurden: heikle Aktionen brauchen dann Bestaetigung. */
object Taint { @Volatile var on = false }

object Apps {
    fun installed(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
}

private const val NEED = "Dafür brauche ich deine Erlaubnis: Handy-Steuerung ist nicht aktiviert (Einstellungen → Bedienungshilfen → JARVIS)."

private fun svc(ctx: Context): JarvisAccessibilityService? {
    val s = JarvisAccessibilityService.instance
    if (s == null) try { ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    return s
}

private fun wake(ctx: Context) {
    try {
        @Suppress("DEPRECATION")
        val wl = ctx.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "jarvis:wake")
        wl.acquire(4000)
    } catch (_: Exception) {}
}

object UiTapTool : JarvisTool {
    override val name = "ui_tap"
    override val description = "Tippt auf eine Stelle des Bildschirms (x und y in Prozent 0-100). long=true: langes Drücken."
    override val schema = """{"type":"object","properties":{"x":{"type":"number"},"y":{"type":"number"},"long":{"type":"boolean"}},"required":["x","y"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        val s = svc(ctx) ?: return NEED
        if (LearnMode.running) return "Im Lernmodus wird nicht getippt."
        val ok = s.tap(args.getDouble("x").toFloat(), args.getDouble("y").toFloat(), args.optBoolean("long", false))
        delay(900)
        return (if (ok) "Getippt.\n" else "Tippen fehlgeschlagen.\n") + s.readScreen(25)
    }
}

object UiSwipeTool : JarvisTool {
    override val name = "ui_swipe"
    override val description = "Wischt über den Bildschirm: direction up, down, left oder right (z. B. Sperrbildschirm hochwischen, Seiten wechseln)."
    override val schema = """{"type":"object","properties":{"direction":{"type":"string","enum":["up","down","left","right"]}},"required":["direction"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        val s = svc(ctx) ?: return NEED
        val ok = when (args.getString("direction")) {
            "up" -> s.swipe(50f, 78f, 50f, 28f)
            "down" -> s.swipe(50f, 28f, 50f, 78f)
            "left" -> s.swipe(82f, 50f, 18f, 50f)
            else -> s.swipe(18f, 50f, 82f, 50f)
        }
        delay(800)
        return (if (ok) "Gewischt.\n" else "Wischen fehlgeschlagen.\n") + s.readScreen(25)
    }
}

object UiLookTool : JarvisTool {
    override val name = "ui_look"
    override val description = "Macht einen Screenshot und lässt ein Bildmodell den Bildschirm beschreiben (wenn ui_read_screen nicht reicht, z. B. bei Bildern, Spielen, Karten)."
    override val schema = """{"type":"object","properties":{"question":{"type":"string"}}}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        val s = svc(ctx) ?: return NEED
        val bmp = withTimeoutOrNull(6000) { suspendCancellableCoroutine<Bitmap?> { c -> s.screenshot { c.resume(it) } } }
            ?: return "Screenshot nicht möglich (braucht Android 11+, oder der Bereich ist geschützt)."
        val scale = 1080f / maxOf(bmp.width, bmp.height).toFloat()
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val q = args.optString("question", "Beschreibe den Bildschirm.")
        return "Bildschirm-Auswertung (fremde Daten):\n" +
            Ai.look(ctx, out.toByteArray(), q + "\nNenne wichtige Bedienelemente mit ihrer Mitte als x%,y% (0-100).")
    }
}

object WakeScreenTool : JarvisTool {
    override val name = "wake_screen"
    override val description = "Weckt den Bildschirm auf."
    override val schema = """{"type":"object","properties":{}}"""
    override suspend fun run(ctx: Context, args: JSONObject): String { wake(ctx); return "Bildschirm geweckt." }
}

object UnlockPhoneTool : JarvisTool {
    override val name = "unlock_phone"
    override val description = "Weckt den Bildschirm, wischt den Sperrbildschirm hoch und gibt, falls gespeichert, die PIN ein."
    override val schema = """{"type":"object","properties":{}}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        if (LearnMode.running) return "Im Lernmodus nicht erlaubt."
        if (!appPrefs(ctx).getBoolean("admin_unlock", false))
            return "Dafür brauche ich deine Erlaubnis: In den Einstellungen unter „Handy-Verwaltung“ „Entsperren erlauben“ einschalten."
        if (Taint.on && !Confirm.ask("JARVIS möchte das Handy entsperren, nachdem er fremde Inhalte gelesen hat. Erlauben?", "ENTSPERREN", "ABBRECHEN", "Entsperren"))
            return "Der Nutzer hat das Entsperren abgelehnt."
        val s = svc(ctx) ?: return NEED
        wake(ctx); delay(900)
        s.swipe(50f, 85f, 50f, 25f, 300); delay(900)
        val pin = Secrets.get(ctx, "lock_pin")
        if (pin.isNotBlank()) {
            for (d in pin) { s.clickExact(d.toString()); delay(200) }
            listOf("OK", "Eingabe", "Enter", "Bestätigen").any { s.clickExact(it) }
            delay(900)
        }
        return "Entsperrversuch ausgeführt.\n" + s.readScreen(15)
    }
}

object RestartPhoneTool : JarvisTool {
    override val name = "restart_phone"
    override val description = "Startet das Handy neu (öffnet das Power-Menü und tippt auf Neustart). Fragt immer vorher nach."
    override val schema = """{"type":"object","properties":{}}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        if (LearnMode.running) return "Im Lernmodus nicht erlaubt."
        if (!Confirm.ask("JARVIS möchte dein Handy jetzt neu starten. Fortfahren?", "NEU STARTEN", "ABBRECHEN", "Neustart"))
            return "Der Nutzer hat den Neustart abgelehnt."
        val s = svc(ctx) ?: return NEED
        s.press("power_menu"); delay(1300)
        val words = listOf("Neustart", "Neu starten", "Restart", "Reboot")
        if (!words.any { s.click(it) }) return "Den Neustart-Knopf im Power-Menü habe ich nicht gefunden.\n" + s.readScreen(20)
        delay(1300)
        (words + "OK").any { s.click(it) }
        return "Neustart ausgelöst."
    }
}

object ListAppsTool : JarvisTool {
    override val name = "list_apps"
    override val description = "Listet die installierten Apps des Handys (Namen)."
    override val schema = """{"type":"object","properties":{}}"""
    override suspend fun run(ctx: Context, args: JSONObject): String = Apps.installed(ctx).joinToString(", ") { it.second }.take(1800)
}

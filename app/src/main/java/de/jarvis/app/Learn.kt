package de.jarvis.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Gelerntes Wissen ueber die Bedienung dieses Handys (sichtbar und loeschbar in den Einstellungen). */
object PhoneKnowledge {
    private fun arr(ctx: Context): JSONArray = try { JSONArray(appPrefs(ctx).getString("phone_notes", "[]")) } catch (e: Exception) { JSONArray() }

    fun all(ctx: Context): List<Pair<String, String>> {
        val a = arr(ctx)
        return (0 until a.length()).map { val o = a.getJSONObject(it); o.optString("t") to o.optString("n") }
    }

    private fun save(ctx: Context, l: List<Pair<String, String>>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.first).put("n", it.second)) }
        appPrefs(ctx).edit().putString("phone_notes", a.toString()).apply()
    }

    fun add(ctx: Context, topic: String, note: String) {
        val t = topic.trim().take(60); val n = note.trim().take(300)
        val l = all(ctx).toMutableList()
        if (n.isBlank() || l.any { it.first == t && it.second == n }) return
        l.add(t to n)
        save(ctx, l.takeLast(150))
    }

    fun remove(ctx: Context, i: Int) { val l = all(ctx).toMutableList(); if (i in l.indices) { l.removeAt(i); save(ctx, l) } }
    fun clear(ctx: Context) = save(ctx, emptyList())

    fun relevant(ctx: Context, text: String): String {
        val l = all(ctx)
        if (l.isEmpty()) return ""
        val words = text.lowercase().split(Regex("[^a-zäöüß0-9]+")).filter { it.length > 3 }
        val hits = l.filter { (t, n) -> words.any { w -> t.lowercase().contains(w) || n.lowercase().contains(w) } }
        return (hits.ifEmpty { l }).takeLast(8).joinToString("\n") { "- ${it.first}: ${it.second}" }
    }
}

object Profile {
    fun summary(ctx: Context): String {
        val p = appPrefs(ctx)
        val name = p.getString("p_name", "") ?: ""
        val about = p.getString("p_about", "") ?: ""
        val sb = StringBuilder()
        if (name.isNotBlank()) sb.append("Name: $name. ")
        sb.append(if (p.getBoolean("p_formal", false)) "Anrede: Siezen. " else "Anrede: Duzen. ")
        if (about.isNotBlank()) sb.append("Über den Nutzer: ${about.take(500)}")
        return sb.toString().trim()
    }
}

object LearnNoteTool : JarvisTool {
    override val name = "learn_note"
    override val description = "Speichert eine nützliche Erkenntnis über die Bedienung des Handys (Thema + kurzer Bedienweg)."
    override val schema = """{"type":"object","properties":{"topic":{"type":"string"},"note":{"type":"string"}},"required":["topic","note"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        PhoneKnowledge.add(ctx, args.getString("topic"), args.getString("note")); return "Gemerkt."
    }
}

/** Lernmodus: JARVIS erkundet das Handy 5 Minuten lang (nur ansehen) und speichert Bedienwege. */
object LearnMode {
    @Volatile var running = false
    @Volatile var endAt = 0L
    @Volatile var status = ""
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val RULES = "[LERNMODUS] Du erkundest mein Handy, um es später besser zu bedienen. ERLAUBT: Apps öffnen, Bildschirm lesen, scrollen, Menüs und Unterseiten öffnen, zurück. VERBOTEN: kaufen, bestellen, senden, anrufen, löschen, deinstallieren, etwas umschalten oder ändern, Konten, Passwörter, Zahlungen, Neustart, Ausschalten und Tippen in Textfelder. Im Zweifel nicht tippen. Speichere jede nützliche Erkenntnis kurz mit learn_note (Thema und Bedienweg). Aufgabe: "

    fun start(ctx: Context, minutes: Int = 5) {
        if (running) return
        if (JarvisAccessibilityService.instance == null) { status = "Bitte zuerst die Handy-Steuerung (Bedienungshilfe) aktivieren."; return }
        val app = ctx.applicationContext
        running = true; endAt = System.currentTimeMillis() + minutes * 60_000L; status = "Starte …"
        job = scope.launch {
            try { run(app) } catch (_: Exception) {}
            finally {
                running = false
                status = "Fertig. ${PhoneKnowledge.all(app).size} Notizen gespeichert."
                Ai.clearTurns()
                IslandHub.setJarvis("", "")
                JarvisAccessibilityService.instance?.press("home")
            }
        }
    }

    fun stop() { running = false; job?.cancel(); status = "Abgebrochen." }

    private suspend fun run(ctx: Context) {
        val steps = ArrayList<String>()
        steps += "Öffne die Einstellungen-App. Lies den Bildschirm. Finde heraus, wo WLAN, Bluetooth, Display und Helligkeit, Töne und Lautstärke und Akku zu finden sind und wie man dorthin navigiert. Nur ansehen."
        steps += "Öffne die Schnelleinstellungen mit ui_press quick_settings, lies die Kacheln und schließe sie wieder mit ui_press back. Nichts umschalten."
        val apps = Apps.installed(ctx).map { it.second }
        val wanted = listOf("Uhr", "YouTube", "Chrome", "Kamera", "Galerie", "Kalender", "Nachrichten", "WhatsApp", "Spotify", "Karten", "Telefon")
        val done = HashSet<String>()
        for (w in wanted) { val a = apps.firstOrNull { it.contains(w, true) }; if (a != null && done.add(a)) steps += "Öffne die App „$a“, lies den Bildschirm und finde heraus, wie man ihre wichtigsten Funktionen erreicht (z. B. Suche, Menü). Nur ansehen." }
        steps += "Rufe list_apps auf und speichere die Namen der installierten Apps knapp mit learn_note (Thema „Apps“)."
        for (a in apps) { if (done.size > 14) break; if (done.add(a)) steps += "Öffne die App „$a“, lies den Bildschirm und notiere kurz, wofür sie da ist und wo ihre Hauptfunktionen sind. Nur ansehen." }
        var i = 0
        for (s in steps) {
            if (!running || System.currentTimeMillis() > endAt) break
            i++
            status = "Schritt $i von ${steps.size}: ${s.take(50)}…"
            IslandHub.setJarvis("thinking", "Lernmodus $i/${steps.size}")
            try { Ai.ask(ctx, RULES + s, emptyList(), false, true) } catch (_: Exception) {}
            JarvisAccessibilityService.instance?.press("home")
            delay(700)
        }
    }
}

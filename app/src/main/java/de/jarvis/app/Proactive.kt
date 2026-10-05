package de.jarvis.app

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract.Instances
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Websuche-Beobachtungen: JARVIS meldet sich nur bei wesentlich Neuem. */
object Watches {
    data class Watch(val id: Long, val query: String, val everyHours: Int, val last: String, val lastRun: Long)

    fun all(ctx: Context): List<Watch> {
        val a = try { JSONArray(appPrefs(ctx).getString("watches", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { val o = a.getJSONObject(it); Watch(o.getLong("id"), o.getString("q"), o.getInt("h"), o.optString("last"), o.optLong("run")) }
    }
    private fun save(ctx: Context, l: List<Watch>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("q", it.query).put("h", it.everyHours).put("last", it.last).put("run", it.lastRun)) }
        appPrefs(ctx).edit().putString("watches", a.toString()).apply()
    }
    fun add(ctx: Context, q: String, hours: Int) = save(ctx, all(ctx) + Watch(System.currentTimeMillis(), q, hours.coerceIn(1, 48), "", 0))
    fun remove(ctx: Context, id: Long) = save(ctx, all(ctx).filter { it.id != id })
    fun update(ctx: Context, w: Watch) = save(ctx, all(ctx).map { if (it.id == w.id) w else it })
}

object CreateWatchTool : JarvisTool {
    override val name = "create_watch"
    override val description = "Beobachtet ein Thema per Websuche und meldet sich bei wesentlichen Neuigkeiten (z. B. 'Bahnstreik', 'Preis der Grafikkarte X')."
    override val schema = """{"type":"object","properties":{"query":{"type":"string"},"every_hours":{"type":"integer","description":"Prüfintervall in Stunden, Standard 6"}},"required":["query"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        Watches.add(ctx, args.getString("query"), args.optInt("every_hours", 6)); return "Beobachtung angelegt. Ich melde mich bei Neuigkeiten."
    }
}
object ListWatchesTool : JarvisTool {
    override val name = "list_watches"
    override val description = "Listet alle Beobachtungen mit ID."
    override val schema = """{"type":"object","properties":{}}"""
    override suspend fun run(ctx: Context, args: JSONObject): String =
        Watches.all(ctx).joinToString("\n") { "id=${it.id} | ${it.query} | alle ${it.everyHours} Std." }.ifBlank { "Keine Beobachtungen." }
}
object DeleteWatchTool : JarvisTool {
    override val name = "delete_watch"
    override val description = "Beendet eine Beobachtung anhand ihrer ID."
    override val schema = """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String { Watches.remove(ctx, args.getLong("id")); return "Beobachtung beendet." }
}

object Proactive {
    fun schedule(ctx: Context) {
        try {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("jarvis_proactive", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ProactiveWorker>(15, TimeUnit.MINUTES).build())
        } catch (_: Exception) {}
    }

    /** Meldet entweder als Hinweis oder (wenn aktiviert) als JARVIS-Anruf. */
    fun announce(c: Context, id: Int, title: String, text: String) {
        if (appPrefs(c).getBoolean("pro_as_call", false)) Notifications.showCall(c, "$title. $text")
        else Notifications.showInfo(c, id, title, text)
    }
}

class ProactiveWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        if (Prefs(c).setupDone) {
            val sp = appPrefs(c)
            if (sp.getBoolean("pro_cal", true)) try { checkCalendar(c) } catch (_: Exception) {}
            if (sp.getBoolean("pro_mail", true) && GoogleAuth.connected(c)) try { checkMail(c) } catch (_: Exception) {}
            try { checkWatches(c) } catch (_: Exception) {}
        }
        Result.success()
    }

    private fun checkCalendar(c: Context) {
        if (!Perms.isGranted(c, Step.CALENDAR) || !NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val now = System.currentTimeMillis()
        val sp = appPrefs(c)
        val done = (sp.getStringSet("notified_events", emptySet()) ?: emptySet())
            .filter { (it.substringAfter(":").toLongOrNull() ?: 0L) > now - 86_400_000 }.toMutableSet()
        val b = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now); ContentUris.appendId(b, now + 35 * 60_000)
        c.contentResolver.query(b.build(), arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.ALL_DAY),
            null, null, "${Instances.BEGIN} ASC")?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(3) == 1) continue
                val begin = cur.getLong(2)
                if (begin < now - 60_000) continue
                val key = "${cur.getLong(0)}:$begin"
                if (!done.add(key)) continue
                val min = ((begin - now) / 60_000).toInt()
                Proactive.announce(c, 20_000 + (key.hashCode() and 0xFFFF), "Termin in $min Minuten", cur.getString(1) ?: "Termin")
            }
        }
        sp.edit().putStringSet("notified_events", done).apply()
    }

    private suspend fun checkMail(c: Context) {
        val sp = appPrefs(c)
        val mails = GmailApi.list(c, "is:unread is:important newer_than:1d", 5)
        val seen = (sp.getStringSet("seen_mail", emptySet()) ?: emptySet()).toMutableSet()
        val seeded = sp.getBoolean("mail_seeded", false)
        val fresh = mails.filter { it.id !in seen }
        seen.addAll(mails.map { it.id })
        sp.edit().putStringSet("seen_mail", seen.toList().takeLast(100).toSet()).putBoolean("mail_seeded", true).apply()
        if (seeded && fresh.isNotEmpty()) {
            val m = fresh.first()
            Proactive.announce(c, 30_000, if (fresh.size > 1) "${fresh.size} neue wichtige E-Mails" else "Neue wichtige E-Mail", "Von ${m.from}: ${m.subject}")
        }
    }

    private fun checkWatches(c: Context) {
        val key = Secrets.apiKey(c)
        if (key.isBlank()) return
        val now = System.currentTimeMillis()
        for (w in Watches.all(c)) {
            if (now - w.lastRun < w.everyHours * 3_600_000L) continue
            try {
                val prompt = if (w.last.isBlank()) "Fasse den aktuellen Stand zu folgendem Thema in höchstens 3 Sätzen zusammen: ${w.query}"
                else "Thema: ${w.query}\nLetzter bekannter Stand: ${w.last}\nPrüfe per Websuche, ob es wesentlich Neues gibt. Antworte exakt mit KEINE_AENDERUNG, falls nicht. Sonst nenne die Neuigkeiten in höchstens 2 Sätzen."
                val r = Ai.searchWeb(key, prompt)
                when {
                    w.last.isBlank() -> Watches.update(c, w.copy(last = r.text, lastRun = now))
                    r.text.contains("KEINE_AENDERUNG") -> Watches.update(c, w.copy(lastRun = now))
                    else -> { Watches.update(c, w.copy(last = r.text, lastRun = now)); Proactive.announce(c, 40_000 + (w.id % 1000).toInt(), "Neues zu: ${w.query}", r.text) }
                }
            } catch (_: Exception) {}
        }
    }
}

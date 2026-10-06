package de.jarvis.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Tool-Schnittstelle fuer die KI. Neues Tool: Interface implementieren und in ToolRegistry eintragen. */
interface JarvisTool {
    val name: String
    val description: String
    val schema: String
    suspend fun run(ctx: Context, args: JSONObject): String
}

object ToolRegistry {
    val all: List<JarvisTool> = listOf(
        CreateReminderTool, ListRemindersTool, DeleteReminderTool,
        GetCalendarTool, CreateEventTool, DeleteEventTool,
        RememberTool, ForgetTool, DeviceStatusTool,
        SearchWebTool, ListMailTool, ReadMailTool, SendMailTool,
        CreateWatchTool, ListWatchesTool, DeleteWatchTool,
        OpenAppTool, YouTubeSearchTool, OpenUrlTool, NavigateTool, DialTool, SmsDraftTool, MediaControlTool, VolumeTool,
        UiReadTool, UiClickTool, UiTypeTool, UiScrollTool, UiPressTool)
    fun find(name: String) = all.firstOrNull { it.name == name }
}

/** Bestaetigungsdialog fuer heikle Aktionen (z. B. Termin loeschen). */
object Confirm {
    class Req(val title: String, val text: String, val yes: String, val no: String, val result: CompletableDeferred<Boolean>)
    val pending = MutableStateFlow<Req?>(null)
    suspend fun ask(text: String, yes: String = "Ja", no: String = "Abbrechen", title: String = "Bestätigen"): Boolean {
        val d = CompletableDeferred<Boolean>()
        pending.value = Req(title, text, yes, no, d)
        val r = withTimeoutOrNull(90_000) { d.await() } ?: false
        pending.value = null
        return r
    }
}

private const val NO_CAL = "Dafür brauche ich deine Erlaubnis: Kalender-Zugriff fehlt."

private fun isoToMs(s: String): Long {
    val t = if (s.length == 10) s + "T00:00" else s.take(16)
    return LocalDateTime.parse(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun fmtDay(ms: Long): String =
    SimpleDateFormat("EEE d. MMM", Locale.GERMAN).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

private fun writableCalendarId(ctx: Context): Long? {
    val c = ctx.contentResolver.query(
        Calendars.CONTENT_URI, arrayOf(Calendars._ID),
        "${Calendars.VISIBLE}=1 AND ${Calendars.CALENDAR_ACCESS_LEVEL}>=500", null,
        "${Calendars.IS_PRIMARY} DESC") ?: return null
    c.use { return if (it.moveToFirst()) it.getLong(0) else null }
}

object CreateReminderTool : JarvisTool {
    override val name = "create_reminder"
    override val description = "Erstellt eine Erinnerung (Benachrichtigung) zu einem Zeitpunkt, optional wiederholt oder als JARVIS-Anruf."
    override val schema = """{"type":"object","properties":{"text":{"type":"string"},"time":{"type":"string","description":"Lokale Zeit, Format yyyy-MM-ddTHH:mm"},"repeat":{"type":"string","enum":["NONE","DAILY","WEEKLY"]},"as_call":{"type":"boolean"}},"required":["text","time"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        val at = isoToMs(args.getString("time"))
        if (at <= System.currentTimeMillis()) return "Dieser Zeitpunkt liegt in der Vergangenheit."
        Reminders.add(ctx, args.getString("text"), at, args.optString("repeat", "NONE"), args.optBoolean("as_call", false))
        return "Erinnerung erstellt für ${fmt(at)}"
    }
}

object ListRemindersTool : JarvisTool {
    override val name = "list_reminders"
    override val description = "Listet alle aktiven Erinnerungen mit ID."
    override val schema = """{"type":"object","properties":{}}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        val l = AppDb.get(ctx).reminders().active()
        return if (l.isEmpty()) "Keine Erinnerungen." else l.joinToString("\n") { "id=${it.id} | ${it.text} | ${fmt(it.triggerAt)} | ${it.repeat}" }
    }
}

object DeleteReminderTool : JarvisTool {
    override val name = "delete_reminder"
    override val description = "Löscht eine Erinnerung anhand ihrer ID (IDs über list_reminders)."
    override val schema = """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        Reminders.remove(ctx, args.getLong("id")); return "Erinnerung gelöscht."
    }
}

object GetCalendarTool : JarvisTool {
    override val name = "get_calendar"
    override val description = "Liest Kalendertermine in einem Zeitraum (inkl. Termin-ID)."
    override val schema = """{"type":"object","properties":{"from":{"type":"string","description":"yyyy-MM-ddTHH:mm"},"to":{"type":"string","description":"yyyy-MM-ddTHH:mm"}},"required":["from","to"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        if (!Perms.isGranted(ctx, Step.CALENDAR)) return NO_CAL
        val b = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, isoToMs(args.getString("from")))
        ContentUris.appendId(b, isoToMs(args.getString("to")))
        val sb = StringBuilder()
        ctx.contentResolver.query(b.build(),
            arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY),
            null, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(4) == 1
                sb.append("id=${c.getLong(0)} | ${c.getString(1)} | ")
                sb.append(if (allDay) "ganztägig ${fmtDay(c.getLong(2))}" else "${fmt(c.getLong(2))} bis ${fmt(c.getLong(3))}").append("\n")
            }
        }
        return sb.toString().ifBlank { "Keine Termine in diesem Zeitraum." }
    }
}

object CreateEventTool : JarvisTool {
    override val name = "create_calendar_event"
    override val description = "Erstellt einen Kalendertermin."
    override val schema = """{"type":"object","properties":{"title":{"type":"string"},"start":{"type":"string","description":"yyyy-MM-ddTHH:mm"},"end":{"type":"string","description":"optional, Standard 1 Stunde"}},"required":["title","start"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        if (!Perms.isGranted(ctx, Step.CALENDAR)) return NO_CAL
        val start = isoToMs(args.getString("start"))
        val end = if (args.has("end") && !args.isNull("end")) isoToMs(args.getString("end")) else start + 3_600_000
        val calId = writableCalendarId(ctx) ?: return "Kein beschreibbarer Kalender auf dem Gerät gefunden."
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, calId); put(Events.TITLE, args.getString("title"))
            put(Events.DTSTART, start); put(Events.DTEND, end); put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        return if (ctx.contentResolver.insert(Events.CONTENT_URI, v) != null) "Termin erstellt: ${args.getString("title")}, ${fmt(start)}" else "Termin konnte nicht erstellt werden."
    }
}

object DeleteEventTool : JarvisTool {
    override val name = "delete_calendar_event"
    override val description = "Löscht einen Kalendertermin per ID. Der Nutzer wird von der App vorher um Bestätigung gebeten."
    override val schema = """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        if (!Perms.isGranted(ctx, Step.CALENDAR)) return NO_CAL
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, args.getLong("id"))
        val title = ctx.contentResolver.query(uri, arrayOf(Events.TITLE), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: return "Termin nicht gefunden."
        if (!Confirm.ask("Termin „$title“ wirklich löschen?")) return "Der Nutzer hat das Löschen abgelehnt."
        return if (ctx.contentResolver.delete(uri, null, null) > 0) "Termin gelöscht." else "Löschen fehlgeschlagen."
    }
}

object RememberTool : JarvisTool {
    override val name = "remember"
    override val description = "Speichert eine Information im Gedächtnis. NUR aufrufen, wenn der Nutzer ausdrücklich darum bittet (z. B. 'merke dir')."
    override val schema = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        AppDb.get(ctx).memories().insert(Memory(text = args.getString("text"))); return "Gemerkt."
    }
}

object ForgetTool : JarvisTool {
    override val name = "forget"
    override val description = "Löscht einen Gedächtnis-Eintrag anhand seiner ID."
    override val schema = """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        AppDb.get(ctx).memories().delete(args.getLong("id")); return "Vergessen."
    }
}

object DeviceStatusTool : JarvisTool {
    override val name = "get_device_status"
    override val description = "Akkustand, Ladezustand und Uhrzeit des Handys."
    override val schema = """{"type":"object","properties":{}}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        return "Akku $pct %, ${if (charging) "lädt" else "lädt nicht"}. Zeit: ${fmt(System.currentTimeMillis())}"
    }
}

package de.jarvis.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

enum class Step(val title: String, val text: String) {
    NOTIFICATIONS("Benachrichtigungen", "Damit ich dich erinnern und informieren kann, brauche ich die Erlaubnis für Benachrichtigungen."),
    MIC("Mikrofon", "Damit du mit mir sprechen kannst, brauche ich Zugriff auf das Mikrofon."),
    CALENDAR("Kalender", "Damit ich deine Termine kenne und eintragen kann, brauche ich Zugriff auf deinen Kalender."),
    EXACT_ALARM("Pünktliche Erinnerungen", "Schalte auf der nächsten Seite „Alarme & Erinnerungen“ für JARVIS ein."),
    FULL_SCREEN("JARVIS-Anruf", "Erlaube auf der nächsten Seite Vollbild-Hinweise, damit ich wichtige Dinge als Anruf auf dem Sperrbildschirm zeigen kann."),
    BATTERY("Hintergrundbetrieb", "Damit ich dich auch informieren kann, wenn die App geschlossen ist, benötige ich Hintergrundbetrieb. Tippe auf der nächsten Seite auf „Zulassen“.")
}

object Perms {
    private fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun isGranted(ctx: Context, s: Step): Boolean = when (s) {
        Step.NOTIFICATIONS -> NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        Step.MIC -> has(ctx, Manifest.permission.RECORD_AUDIO)
        Step.CALENDAR -> has(ctx, Manifest.permission.READ_CALENDAR) && has(ctx, Manifest.permission.WRITE_CALENDAR)
        Step.EXACT_ALARM -> Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        Step.FULL_SCREEN -> Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        Step.BATTERY -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    private fun isRuntime(s: Step) = s == Step.NOTIFICATIONS || s == Step.MIC || s == Step.CALENDAR

    fun request(ctx: Context, step: Step, retry: Boolean, launch: (Array<String>) -> Unit) {
        val uri = Uri.parse("package:${ctx.packageName}")
        if (retry && isRuntime(step)) { open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)); return }
        when (step) {
            Step.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                else open(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            Step.MIC -> launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            Step.CALENDAR -> launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            Step.EXACT_ALARM -> open(ctx, Intent("android.settings.REQUEST_SCHEDULE_EXACT_ALARM", uri))
            Step.FULL_SCREEN -> open(ctx, Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", uri))
            Step.BATTERY -> open(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri))
        }
    }

    private fun open(ctx: Context, i: Intent) {
        try { ctx.startActivity(i) } catch (e: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
    }
}

enum class Status { OK, PENDING, WARN }
data class Check(val label: String, val status: Status, val detail: String)

/** Automatische Konfiguration nach den Berechtigungen. */
object Provisioner {
    suspend fun run(ctx: Context): List<Check> = withContext(Dispatchers.IO) {
        Notifications.createChannels(ctx)
        AppDb.get(ctx).openHelper.writableDatabase // Datenbank + Memory-System anlegen
        Reminders.restoreAll(ctx)
        JarvisService.start(ctx)
        Proactive.schedule(ctx)
        delay(600)

        val calendar = if (Perms.isGranted(ctx, Step.CALENDAR)) {
            val n = ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID), null, null, null)?.use { it.count } ?: 0
            if (n > 0) Check("Kalender", Status.OK, "verfügbar") else Check("Kalender", Status.WARN, "kein Kalender auf dem Gerät")
        } else Check("Kalender", Status.WARN, "Erlaubnis fehlt")

        val web = runCatching {
            val c = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 4000
            val ok = c.responseCode in 200..399; c.disconnect(); ok
        }.getOrDefault(false)

        val exact = Perms.isGranted(ctx, Step.EXACT_ALARM) && Perms.isGranted(ctx, Step.NOTIFICATIONS)

        listOf(
            if (GoogleAuth.connected(ctx)) Check("Google", Status.OK, "verbunden") else Check("Google", Status.PENDING, "nicht verbunden"),
            calendar,
            if (GoogleAuth.connected(ctx)) Check("Gmail", Status.OK, "verfügbar") else Check("Gmail", Status.PENDING, "braucht Google"),
            if (web) Check("Web", Status.OK, "verfügbar") else Check("Web", Status.WARN, "keine Verbindung"),
            if (exact) Check("Erinnerungen", Status.OK, "verfügbar") else Check("Erinnerungen", Status.WARN, "nur ungefähre Zeiten"),
            if (JarvisService.running) Check("Hintergrund", Status.OK, "aktiv") else Check("Hintergrund", Status.WARN, "nicht aktiv"),
            if (Secrets.apiKey(ctx).isNotBlank()) Check("KI", Status.OK, "Schlüssel gespeichert") else Check("KI", Status.PENDING, "Schlüssel im Chat einfügen")
        )
    }
}

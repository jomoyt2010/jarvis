package de.jarvis.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object ReminderScheduler {
    const val ACTION_FIRE = "de.jarvis.app.REMINDER_FIRE"
    const val EXTRA_ID = "id"

    private fun pending(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, id.toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, r.id)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
        else
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi) // ungefaehr, falls Erlaubnis fehlt
    }

    fun cancel(ctx: Context, id: Long) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))

    fun nextTrigger(r: Reminder, now: Long = System.currentTimeMillis()): Long? {
        val field = when (r.repeat) {
            "DAILY" -> Calendar.DAY_OF_YEAR
            "WEEKLY" -> Calendar.WEEK_OF_YEAR
            else -> return null
        }
        val c = Calendar.getInstance().apply { timeInMillis = r.triggerAt }
        while (c.timeInMillis <= now) c.add(field, 1)
        return c.timeInMillis
    }
}

object Reminders {
    suspend fun add(ctx: Context, text: String, at: Long, repeat: String = "NONE", asCall: Boolean = false): Long {
        val r = Reminder(text = text, triggerAt = at, repeat = repeat, asCall = asCall)
        val id = AppDb.get(ctx).reminders().insert(r)
        ReminderScheduler.schedule(ctx, r.copy(id = id))
        return id
    }

    suspend fun remove(ctx: Context, id: Long) {
        ReminderScheduler.cancel(ctx, id)
        AppDb.get(ctx).reminders().delete(id)
    }

    suspend fun fire(ctx: Context, id: Long) {
        val dao = AppDb.get(ctx).reminders()
        val r = dao.get(id) ?: return
        if (r.done) return
        if (r.asCall) JarvisCalls.ring(ctx, r.text) else Notifications.showReminder(ctx, r.id, r.text)
        val next = ReminderScheduler.nextTrigger(r)
        if (next != null) {
            val u = r.copy(triggerAt = next)
            dao.update(u)
            ReminderScheduler.schedule(ctx, u)
        } else dao.update(r.copy(done = true))
    }

    /** Nach Neustart: Alarme neu setzen, verpasste sofort melden. */
    suspend fun restoreAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for (r in AppDb.get(ctx).reminders().active()) {
            if (r.triggerAt <= now) fire(ctx, r.id) else ReminderScheduler.schedule(ctx, r)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1)
        if (id < 0) return
        val app = ctx.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Reminders.fire(app, id) } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.restoreAll(app)
                if (Prefs(app).setupDone) JarvisService.start(app)
                if (Prefs(app).setupDone && appPrefs(app).getBoolean("wake", false))
                    Notifications.showInfo(app, 77, "Hey JARVIS ist aus", "Tippe hier, um die Sprachaktivierung neu zu starten.", true)
            } finally { pending.finish() }
        }
    }
}

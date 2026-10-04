package de.jarvis.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

object Notifications {
    const val CH_SERVICE = "jarvis_service"
    const val CH_REMINDER = "jarvis_reminder"
    const val CH_CALL = "jarvis_call"
    const val SERVICE_ID = 1
    const val CALL_ID = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "JARVIS im Hintergrund", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(
            NotificationChannel(CH_CALL, "JARVIS-Anruf", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 400, 600, 400)
            })
    }

    private fun openApp(ctx: Context) =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun serviceNotification(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("JARVIS")
            .setContentText("Ich bin bereit.")
            .setOngoing(true).setSilent(true)
            .setContentIntent(openApp(ctx))
            .build()

    @SuppressLint("MissingPermission")
    fun showReminder(ctx: Context, id: Long, text: String) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(ctx, CH_REMINDER)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("JARVIS erinnert dich")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        NotificationManagerCompat.from(ctx).notify(10_000 + id.toInt(), n)
    }

    /** Simulierter Anruf (kein echter Telefonanruf): High-Priority + Full-Screen-Intent. */
    @SuppressLint("MissingPermission")
    fun showCall(ctx: Context, text: String) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val intent = Intent(ctx, CallActivity::class.java)
            .putExtra("text", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val full = PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_CALL)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("JARVIS")
            .setContentText("Eingehender Anruf")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setTimeoutAfter(45_000)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // Klingeln bis angenommen/abgelehnt
        NotificationManagerCompat.from(ctx).notify(CALL_ID, n)
    }
}

package de.jarvis.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class JarvisService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.createChannels(this)
        val n = Notifications.serviceNotification(this)
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(Notifications.SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifications.SERVICE_ID, n)
        running = true
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    companion object {
        @Volatile var running = false
        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, JarvisService::class.java)) }
            catch (e: Exception) { Log.w("JARVIS", "Service-Start nicht moeglich: ${e.message}") }
        }
    }
}

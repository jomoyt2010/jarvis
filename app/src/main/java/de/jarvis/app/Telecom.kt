package de.jarvis.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.app.NotificationManagerCompat

/**
 * JARVIS-Anrufe laufen ueber das Android-Telecom-System (selbstverwaltete Anrufe):
 * Klingeln, Bluetooth/Headset-Tasten, Anrufstatus - wie bei WhatsApp & Co. Kein echter Mobilfunkanruf.
 */
object JarvisCalls {
    @Volatile var pendingText = ""
    @Volatile var connection: JarvisConnection? = null
    @Volatile var shown = false

    private fun handle(ctx: Context) = PhoneAccountHandle(ComponentName(ctx, JarvisConnectionService::class.java), "jarvis_assistant")

    fun register(ctx: Context) {
        try {
            ctx.getSystemService(TelecomManager::class.java).registerPhoneAccount(
                PhoneAccount.builder(handle(ctx), "JARVIS")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED).addSupportedUriScheme("jarvis").build())
        } catch (_: Exception) {}
    }

    /** Eingehenden JARVIS-Anruf ausloesen (mit Notbremse: Benachrichtigung, falls Telecom nichts zeigt). */
    fun ring(ctx: Context, text: String) {
        val app = ctx.applicationContext
        pendingText = text; shown = false
        Voice.prefetch(app, text)
        try {
            register(app)
            val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("jarvis", "assistant", null)) }
            app.getSystemService(TelecomManager::class.java).addNewIncomingCall(handle(app), extras)
        } catch (e: Exception) { Notifications.showCall(app, text); return }
        Handler(Looper.getMainLooper()).postDelayed({ if (!shown) Notifications.showCall(app, text) }, 2500)
    }

    fun answered() { connection?.setActive() }

    fun ended(cause: Int = DisconnectCause.LOCAL) {
        connection?.let { try { it.setDisconnected(DisconnectCause(cause)); it.destroy() } catch (_: Exception) {} }
        connection = null
    }

    /** true, wenn die Telecom-Verbindung die Audio-Route gesetzt hat. */
    @Suppress("DEPRECATION")
    fun route(speaker: Boolean): Boolean {
        val c = connection ?: return false
        c.setAudioRoute(if (speaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
        return true
    }
}

class JarvisConnection(private val ctx: Context, private val text: String) : Connection() {
    override fun onShowIncomingCallUi() { Notifications.showCall(ctx, text) }

    override fun onAnswer() { // z. B. ueber Headset-Taste
        setActive()
        try {
            ctx.startActivity(Intent(ctx, CallActivity::class.java).putExtra("text", text).putExtra("accept", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: Exception) {}
    }

    override fun onReject() {
        NotificationManagerCompat.from(ctx).cancel(Notifications.CALL_ID)
        JarvisCalls.ended(DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        NotificationManagerCompat.from(ctx).cancel(Notifications.CALL_ID)
        CallActivity.active?.hangup()
        JarvisCalls.ended(DisconnectCause.LOCAL)
    }

    override fun onAbort() { onDisconnect() }
}

class JarvisConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val c = JarvisConnection(applicationContext, JarvisCalls.pendingText)
        c.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
        c.setAddress(Uri.fromParts("jarvis", "assistant", null), TelecomManager.PRESENTATION_ALLOWED)
        c.setCallerDisplayName("JARVIS", TelecomManager.PRESENTATION_ALLOWED)
        c.setAudioModeIsVoip(true)
        c.setRinging()
        JarvisCalls.connection = c
        return c
    }

    override fun onCreateIncomingConnectionFailed(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?) {
        Notifications.showCall(applicationContext, JarvisCalls.pendingText)
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> {
                NotificationManagerCompat.from(ctx).cancel(Notifications.CALL_ID)
                JarvisCalls.ended(DisconnectCause.REJECTED)
            }
            ACTION_HANGUP -> {
                CallActivity.active?.hangup()
                NotificationManagerCompat.from(ctx).cancel(Notifications.ONGOING_ID)
                JarvisCalls.ended(DisconnectCause.LOCAL)
            }
        }
    }
    companion object {
        const val ACTION_DECLINE = "de.jarvis.app.CALL_DECLINE"
        const val ACTION_HANGUP = "de.jarvis.app.CALL_HANGUP"
    }
}

package de.jarvis.app

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Hey JARVIS" (Beta): hoert im Vordergrunddienst auf das Wort "Jarvis". */
class ListenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rec: SpeechRecognizer? = null
    private val wake = Regex("(jarvis|jarwis|dscharvis|tscharvis)")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { appPrefs(this).edit().putBoolean("wake", false).apply(); stopSelf(); return START_NOT_STICKY }
        if (!Perms.isGranted(this, Step.MIC)) { stopSelf(); return START_NOT_STICKY }
        val stop = PendingIntent.getService(this, 6, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notifications.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_jarvis).setContentTitle("JARVIS hört zu")
            .setContentText("Sag „Hey JARVIS“ und deine Frage.").setOngoing(true).setSilent(true)
            .addAction(0, "Beenden", stop).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(3, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (!running) { running = true; listen(false) }
        return START_STICKY
    }

    private fun listen(cmdMode: Boolean) {
        if (!running) return
        rec?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                scope.launch {
                    delay(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) 1500 else 400)
                    listen(false)
                }
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.lowercase() ?: ""
                if (cmdMode) { if (t.isBlank()) listen(false) else handle(t); return }
                val m = wake.find(t)
                if (m == null) { listen(false); return }
                val cmd = t.substring(m.range.last + 1).trim(' ', ',', '.', '!', '?')
                if (cmd.isEmpty()) scope.launch { Voice.speak(applicationContext, "Ja?"); listen(true) } else handle(cmd)
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE"))
    }

    private fun handle(cmd: String) {
        scope.launch {
            Ai.chat.add(ChatMsg(true, cmd))
            val r = withContext(Dispatchers.IO) { Ai.ask(applicationContext, cmd, voiceMode = true) }
            Ai.chat.add(ChatMsg(false, r.text, r.sources))
            Voice.speak(applicationContext, r.text)
            listen(false)
        }
    }

    override fun onDestroy() {
        running = false
        rec?.destroy(); scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "de.jarvis.app.WAKE_STOP"
        @Volatile var running = false
        fun start(ctx: Context) { try { ContextCompat.startForegroundService(ctx, Intent(ctx, ListenService::class.java)) } catch (_: Exception) {} }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ListenService::class.java)) }
    }
}

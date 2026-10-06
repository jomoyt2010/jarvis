package de.jarvis.app

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telecom.DisconnectCause
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * JARVIS-Anruf: klingelt (Telecom + Full-Screen), nach dem Annehmen echtes Gespraech,
 * bis du auflegst. Auflegen ist immer sichtbar. Kein echter Mobilfunkanruf.
 */
class CallActivity : ComponentActivity() {
    companion object { @Volatile var active: CallActivity? = null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var callJob: Job? = null
    private var rec: SpeechRecognizer? = null
    private lateinit var audio: AudioManager
    private var ended = false

    private var accepted by mutableStateOf(false)
    private var phase by mutableStateOf("")
    private var caption by mutableStateOf("")
    private var seconds by mutableIntStateOf(0)
    private var muted by mutableStateOf(false)
    private var speakerOn by mutableStateOf(true)
    private var showText by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AudioManager::class.java)
        val text = intent.getStringExtra("text") ?: ""
        setContent {
            JarvisTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CallScreen(accepted, phase, caption, seconds, muted, speakerOn, showText,
                        onAccept = { startCall(text, false) },
                        onDecline = { endCall() },
                        onMute = { muted = !muted },
                        onSpeaker = { setSpeaker(!speakerOn) },
                        onToggleText = { showText = !showText })
                }
            }
        }
        if (intent.getBooleanExtra("outgoing", false)) startCall("", true)
        else if (intent.getBooleanExtra("accept", false)) startCall(text, false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("accept", false) && !accepted) startCall(intent.getStringExtra("text") ?: "", false)
    }

    fun hangup() = endCall()

    private fun stopRinging() = NotificationManagerCompat.from(this).cancel(Notifications.CALL_ID)

    private fun setSpeaker(on: Boolean) {
        speakerOn = on
        if (!JarvisCalls.route(on)) audio.isSpeakerphoneOn = on
    }

    private fun tone(type: Int, ms: Int) {
        try {
            val g = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
            g.startTone(type, ms)
            Handler(Looper.getMainLooper()).postDelayed({ try { g.release() } catch (_: Exception) {} }, ms + 300L)
        } catch (_: Exception) {}
    }

    private fun startCall(text: String, outgoing: Boolean) {
        if (accepted) return
        stopRinging()
        accepted = true
        Voice.inCall = true
        JarvisCalls.answered()
        if (JarvisCalls.connection == null) audio.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(true)
        Notifications.showOngoingCall(this)
        tone(ToneGenerator.TONE_PROP_ACK, 200)
        scope.launch { while (isActive) { delay(1000); seconds++ } }
        callJob = scope.launch {
            val en = Lang.en(this@CallActivity)
            say(if (outgoing) (if (intent.getBooleanExtra("assist", false)) (if (en) "Yes?" else "Ja?") else if (en) "JARVIS here. How may I help?" else "Hier ist JARVIS. Was kann ich für dich tun?")
                else text.ifBlank { "Hier ist JARVIS." })
            if (!Perms.isGranted(this@CallActivity, Step.MIC)) { say("Mir fehlt die Mikrofon-Erlaubnis. Ich lege auf."); endCall(); return@launch }
            var context = if (outgoing) "" else "[Kontext: Du hast den Nutzer gerade angerufen und gesagt: \"$text\"] "
            var silent = 0
            while (isActive) {
                while (muted) { phase = "Stumm"; delay(300) }
                phase = "Ich höre zu …"
                val heard = listenOnce()
                if (heard == null) {
                    if (++silent >= 2) { say("Ich lege dann mal auf. Bis später."); break }
                    continue
                }
                silent = 0
                if (isGoodbye(heard)) { say("Alles klar. Bis bald."); break }
                phase = "Denke nach …"
                Ai.chat.add(ChatMsg(true, heard))
                val r = withContext(Dispatchers.IO) { Ai.ask(applicationContext, context + heard, emptyList(), true) }
                context = ""
                Ai.chat.add(ChatMsg(false, r.text, r.sources, model = r.model))
                say(r.text)
            }
            endCall()
        }
    }

    private suspend fun say(t: String) { phase = "JARVIS spricht"; caption = t; Voice.speak(applicationContext, t) }

    private fun isGoodbye(s: String): Boolean {
        val l = s.lowercase()
        return listOf("tschüss", "tschüs", "auf wiedersehen", "das war's", "das wars", "auflegen", "leg auf", "bis später", "ciao").any { l.contains(it) }
    }

    private suspend fun listenOnce(): String? {
        val result = suspendCancellableCoroutine<String?> { cont ->
            val r = Recog.create(this)
            rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
                override fun onResults(results: Bundle?) {
                    if (cont.isActive) cont.resume(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                }
            })
            cont.invokeOnCancellation { try { r.destroy() } catch (_: Exception) {} }
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.stt(this)))
        }
        try { rec?.destroy() } catch (_: Exception) {}
        rec = null
        return result
    }

    private fun endCall() {
        if (ended) return
        ended = true
        if (accepted) tone(ToneGenerator.TONE_PROP_BEEP2, 400)
        callJob?.cancel()
        Voice.stop()
        JarvisCalls.ended(if (accepted) DisconnectCause.LOCAL else DisconnectCause.REJECTED)
        NotificationManagerCompat.from(this).cancel(Notifications.ONGOING_ID)
        stopRinging()
        finish()
    }

    override fun onDestroy() {
        if (active === this) active = null
        callJob?.cancel(); scope.cancel()
        try { rec?.destroy() } catch (_: Exception) {}
        Voice.stop(); Voice.inCall = false
        if (JarvisCalls.connection == null) { audio.isSpeakerphoneOn = false; audio.mode = AudioManager.MODE_NORMAL }
        NotificationManagerCompat.from(this).cancel(Notifications.ONGOING_ID)
        stopRinging()
        super.onDestroy()
    }
}

@Composable
private fun RoundButton(emoji: String, label: String, bg: Color, size: Int = 68, rot: Float = 0f, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 26.sp, modifier = Modifier.rotate(rot))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Color.LightGray)
    }
}

@Composable
private fun Pulse(active: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, if (active) 1.2f else 1.04f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(170.dp).scale(s).clip(CircleShape).background(Cyan.copy(alpha = 0.10f)))
        Box(Modifier.size(140.dp).scale((s + 1f) / 2f).clip(CircleShape).background(Cyan.copy(alpha = 0.18f)))
        Box(Modifier.size(110.dp).clip(CircleShape).background(Color(0xFF16324A)), contentAlignment = Alignment.Center) {
            Text("J", fontSize = 48.sp, color = Cyan, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
fun CallScreen(
    accepted: Boolean, phase: String, caption: String, seconds: Int, muted: Boolean, speakerOn: Boolean, showText: Boolean,
    onAccept: () -> Unit, onDecline: () -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit, onToggleText: () -> Unit
) {
    val dim = Color(0xFF223344)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF12283B), Color(0xFF05080F))))) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Kopf (fest)
            Text(if (accepted) "Anruf" else "Eingehender Anruf", color = Color.LightGray, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text("JARVIS", fontSize = 34.sp, color = Color.White, fontWeight = FontWeight.Light)
            Text(if (accepted) "%02d:%02d".format(seconds / 60, seconds % 60) else "JARVIS Assistent", color = Color.Gray)
            Spacer(Modifier.height(20.dp))
            Pulse(active = !accepted || phase.contains("spricht") || phase.contains("höre"))
            if (accepted) Text(phase, color = Cyan, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            // Mitte: scrollbarer Text, nimmt nur den uebrigen Platz
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp)) {
                if (accepted && showText && caption.isNotBlank())
                    Text(caption, fontSize = 17.sp, textAlign = TextAlign.Center, color = Color(0xFFDDE6EE),
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
            }
            // Unten (fest, immer sichtbar)
            if (!accepted) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton("📞", "Ablehnen", Red, 76, 135f, onDecline)
                    RoundButton("📞", "Annehmen", Green, 76, 0f, onAccept)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(if (muted) "🔇" else "🎤", "Stumm", if (muted) Cyan.copy(alpha = 0.5f) else dim, onClick = onMute)
                    RoundButton(if (speakerOn) "🔊" else "📱", if (speakerOn) "Lautsprecher" else "Hörer", dim, onClick = onSpeaker)
                    RoundButton("💬", "Text", if (showText) Cyan.copy(alpha = 0.5f) else dim, onClick = onToggleText)
                }
                Spacer(Modifier.height(22.dp))
                RoundButton("📞", "Auflegen", Red, 76, 135f, onDecline)
            }
        }
    }
}

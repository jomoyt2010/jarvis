package de.jarvis.app

import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * JARVIS-Anruf: klingelt (Full-Screen-Intent), nach dem Annehmen echtes Gespraech
 * (JARVIS spricht, hoert zu, antwortet ...) bis zum Auflegen. Kein echter Telefonanruf.
 */
class CallActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var callJob: Job? = null
    private var rec: SpeechRecognizer? = null
    private lateinit var audio: AudioManager

    private var accepted by mutableStateOf(false)
    private var phase by mutableStateOf("")
    private var caption by mutableStateOf("")
    private var seconds by mutableIntStateOf(0)
    private var muted by mutableStateOf(false)
    private var speakerOn by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(AudioManager::class.java)
        val text = intent.getStringExtra("text") ?: ""
        val outgoing = intent.getBooleanExtra("outgoing", false)
        setContent {
            JarvisTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CallScreen(accepted, phase, caption, seconds, muted, speakerOn,
                        onAccept = { startCall(text, false) },
                        onDecline = { endCall() },
                        onMute = { muted = !muted },
                        onSpeaker = { speakerOn = !speakerOn; audio.isSpeakerphoneOn = speakerOn })
                }
            }
        }
        if (outgoing) startCall("", true) else if (intent.getBooleanExtra("accept", false)) startCall(text, false)
    }

    private fun stopRinging() = NotificationManagerCompat.from(this).cancel(Notifications.CALL_ID)

    private fun startCall(text: String, outgoing: Boolean) {
        if (accepted) return
        stopRinging()
        accepted = true
        Voice.inCall = true
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = speakerOn
        scope.launch { while (isActive) { delay(1000); seconds++ } }
        callJob = scope.launch {
            val first = if (outgoing) "Hier ist JARVIS. Was kann ich für dich tun?" else text.ifBlank { "Hier ist JARVIS." }
            say(first)
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
                val r = withContext(Dispatchers.IO) { Ai.ask(applicationContext, context + heard) }
                context = ""
                Ai.chat.add(ChatMsg(false, r.text, r.sources))
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
                override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
                override fun onResults(results: Bundle?) {
                    if (cont.isActive) cont.resume(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                }
            })
            cont.invokeOnCancellation { try { r.destroy() } catch (_: Exception) {} }
            r.startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE"))
        }
        try { rec?.destroy() } catch (_: Exception) {}
        rec = null
        return result
    }

    private fun endCall() {
        stopRinging()
        finish()
    }

    override fun onDestroy() {
        callJob?.cancel(); scope.cancel()
        try { rec?.destroy() } catch (_: Exception) {}
        Voice.stop(); Voice.inCall = false
        audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
        stopRinging()
        super.onDestroy()
    }
}

@Composable
fun CallScreen(
    accepted: Boolean, phase: String, caption: String, seconds: Int, muted: Boolean, speakerOn: Boolean,
    onAccept: () -> Unit, onDecline: () -> Unit, onMute: () -> Unit, onSpeaker: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Text("JARVIS", fontSize = 38.sp, color = Cyan, letterSpacing = 6.sp)
            Spacer(Modifier.height(8.dp))
            Text(if (!accepted) "☎️ Eingehender Anruf" else "%02d:%02d".format(seconds / 60, seconds % 60), color = Color.Gray, fontSize = 18.sp)
            if (accepted) {
                Text(phase, color = Cyan, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(36.dp))
                Text(caption, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
        }
        if (!accepted) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = onDecline, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("ABLEHNEN") }
                Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black)) { Text("ANNEHMEN") }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onMute) { Text(if (muted) "🔇 Stumm an" else "🎤 Stumm") }
                    OutlinedButton(onClick = onSpeaker) { Text(if (speakerOn) "🔊 Lautsprecher" else "📱 Hörer") }
                }
                Button(onClick = onDecline, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("AUFLEGEN") }
            }
        }
    }
}

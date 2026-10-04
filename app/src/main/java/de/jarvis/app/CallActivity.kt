package de.jarvis.app

import android.os.Bundle
import android.speech.tts.TextToSpeech
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
import java.util.Locale

/** Simulierter JARVIS-Anruf (kein echter Telefonanruf). Erscheint auch auf dem Sperrbildschirm. */
class CallActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra("text") ?: "Ich habe eine Nachricht für dich."
        tts = TextToSpeech(this) { s ->
            if (s == TextToSpeech.SUCCESS) { tts?.language = Locale.GERMAN; ttsReady = true }
        }
        setContent {
            JarvisTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CallScreen(text,
                        onAccept = { stopRinging(); if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "call") },
                        onEnd = { stopRinging(); finish() })
                }
            }
        }
    }

    private fun stopRinging() = NotificationManagerCompat.from(this).cancel(Notifications.CALL_ID)

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}

@Composable
fun CallScreen(text: String, onAccept: () -> Unit, onEnd: () -> Unit) {
    var accepted by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Text("JARVIS", fontSize = 36.sp, color = Cyan, letterSpacing = 6.sp)
            Spacer(Modifier.height(8.dp))
            Text(if (accepted) "Verbunden" else "☎️ Eingehender Anruf", color = Color.Gray)
            if (accepted) {
                Spacer(Modifier.height(40.dp))
                Text(text, fontSize = 22.sp, textAlign = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (!accepted) {
                Button(onClick = onEnd, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("ABLEHNEN") }
                Button(onClick = { accepted = true; onAccept() },
                    colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black)) { Text("ANNEHMEN") }
            } else {
                Button(onClick = onEnd, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("AUFLEGEN") }
            }
        }
    }
}

package de.jarvis.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * JARVIS-Stimme: Gemini-TTS (kostenlos, limitiert) oder Handy-Stimme als Fallback,
 * danach ein eigener Roboter-Effekt (Ringmodulation + metallischer Kammfilter + Bitcrusher).
 */
object Voice {
    @Volatile var inCall = false
    private var current: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var geminiBlockedUntil = 0L
    private val TTS_MODELS = listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")

    fun stop() {
        current?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        current = null
    }

    suspend fun speak(ctx: Context, raw: String) = withContext(Dispatchers.IO) {
        val text = raw.replace(Regex("https?://\\S+"), "").replace(Regex("[*_#`]"), "").trim().take(600)
        if (text.isEmpty()) return@withContext
        val sp = appPrefs(ctx)
        var pcm: ShortArray? = null
        var rate = 24000
        var gemini = false
        if (sp.getBoolean("voice_gemini", true) && System.currentTimeMillis() > geminiBlockedUntil) {
            try { pcm = geminiTts(ctx, text); gemini = true }
            catch (e: Exception) { geminiBlockedUntil = System.currentTimeMillis() + 60_000 }
        }
        if (pcm == null) {
            val r = androidTts(ctx, text) ?: return@withContext
            pcm = r.first; rate = r.second
        }
        val out = robotize(pcm, rate, sp.getFloat("robot", 0.6f))
        play(out, if (gemini) (rate * 0.94).roundToInt() else rate)
    }

    private fun geminiTts(ctx: Context, text: String): ShortArray {
        val key = Secrets.apiKey(ctx)
        if (key.isBlank()) throw IllegalStateException("kein Schluessel")
        val voice = appPrefs(ctx).getString("voice_name", "Charon") ?: "Charon"
        val prompt = "Sage mit tiefer, ruhiger, präziser Stimme, kühl und souverän wie ein hochintelligenter Roboter-Butler: $text"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        for (m in TTS_MODELS) {
            val (code, raw) = Gemini.post(key, m, body)
            if (code == 404) continue
            if (code != 200) throw IllegalStateException("TTS $code")
            val data = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
            val sb = ByteBuffer.wrap(Base64.decode(data, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(sb.remaining()).also { sb.get(it) }
        }
        throw IllegalStateException("kein TTS-Modell")
    }

    private suspend fun ttsInstance(ctx: Context): TextToSpeech? {
        tts?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val t = withContext(Dispatchers.Main) { TextToSpeech(ctx.applicationContext) { ready.complete(it == TextToSpeech.SUCCESS) } }
        if (withTimeoutOrNull(5000) { ready.await() } != true) return null
        t.language = Locale.GERMAN
        tts = t
        return t
    }

    private suspend fun androidTts(ctx: Context, text: String): Pair<ShortArray, Int>? {
        val t = ttsInstance(ctx) ?: return null
        val f = File(ctx.cacheDir, "tts_${System.nanoTime()}.wav")
        val done = CompletableDeferred<Boolean>()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done.complete(false) }
        })
        t.setPitch(0.75f); t.setSpeechRate(0.95f)
        t.synthesizeToFile(text, null, f, "jarvis")
        val ok = withTimeoutOrNull(20000) { done.await() } == true
        val bytes = if (ok && f.exists()) f.readBytes() else null
        f.delete()
        return bytes?.let { parseWav(it) }
    }

    private fun parseWav(b: ByteArray): Pair<ShortArray, Int>? {
        if (b.size < 44) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val ch = bb.getShort(22).toInt().coerceAtLeast(1)
        val sr = bb.getInt(24)
        var pos = 12; var start = -1; var len = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4); val l = bb.getInt(pos + 4)
            if (l < 0) break
            if (id == "data") { start = pos + 8; len = if (start + l > b.size) b.size - start else l; break }
            pos += 8 + l + (l and 1)
        }
        if (start < 0) return null
        val frames = len / (2 * ch)
        return ShortArray(frames) { bb.getShort(start + it * 2 * ch) } to sr
    }

    /** Roboter-Effekt. strength 0..1. */
    private fun robotize(x: ShortArray, sr: Int, strength: Float): ShortArray {
        val s = strength.coerceIn(0f, 1f)
        val total = x.size + sr / 5
        val y = FloatArray(total)
        val mod = 0.55f * s
        for (i in x.indices) {
            val v = x[i] / 32768f
            val m = sin(2.0 * PI * 55.0 * i / sr).toFloat()
            y[i] = v * (1 - mod) + v * m * mod
        }
        val d = (sr * 0.011).toInt().coerceAtLeast(1)
        val fb = 0.5f * s
        for (i in d until total) y[i] += y[i - d] * fb
        val levels = 256f - 200f * s
        var peak = 0.0001f
        for (i in 0 until total) { y[i] = (y[i] * levels).roundToInt() / levels; peak = max(peak, abs(y[i])) }
        val g = 0.9f / peak
        return ShortArray(total) { (y[it] * g * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    private suspend fun play(pcm: ShortArray, sr: Int) {
        stop()
        val usage = if (inCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        track.write(pcm, 0, pcm.size)
        current = track
        track.play()
        try { delay(pcm.size * 1000L / sr + 150) }
        finally { if (current === track) { try { track.release() } catch (_: Exception) {}; current = null } }
    }
}

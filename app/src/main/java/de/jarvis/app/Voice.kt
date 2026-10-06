package de.jarvis.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private class Synth(val pcm: ShortArray, val rate: Int, val engine: String)

/**
 * JARVIS-Stimme. Engines (alle kostenlos): Edge-Neural (ohne Schluessel), Google Chirp 3 HD,
 * ElevenLabs, Gemini, Handy-Stimme. Danach ein eigener Roboter-/KI-Effekt.
 */
object Voice {
    @Volatile var inCall = false
    @Volatile var lastEngine = ""
    @Volatile var lastError = ""
    private var current: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private val blocked = ConcurrentHashMap<String, Long>()
    private val cache = LinkedHashMap<String, Pair<ShortArray, Int>>()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var edgeSkew = 0L

    private const val EDGE_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val EDGE_VER = "1-143.0.3650.75"
    private const val EDGE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    fun stop() {
        current?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        current = null
    }

    private fun clean(raw: String) = raw
        .replace(Regex("https?://\\S+"), "").replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        .replace(Regex("[*_#`]"), "").trim().take(600)

    /** Sprache schon vorab erzeugen (z. B. waehrend das Handy klingelt). */
    fun prefetch(ctx: Context, raw: String) {
        val app = ctx.applicationContext
        bg.launch { try { render(app, clean(raw)) } catch (_: Exception) {} }
    }

    suspend fun speak(ctx: Context, raw: String) = withContext(Dispatchers.IO) {
        val text = clean(raw)
        if (text.isEmpty()) return@withContext
        val r = render(ctx, text) ?: return@withContext
        play(r.first, r.second)
    }

    private suspend fun render(ctx: Context, text: String): Pair<ShortArray, Int>? {
        val sp0 = appPrefs(ctx)
        val ck = text + "\u0000" + listOf("voice_engine", "voice_edge", "voice_edge_en", "lang", "voice_google", "voice_eleven")
            .joinToString("|") { sp0.getString(it, "") ?: "" } + sp0.getFloat("robot", 0.5f)
        synchronized(cache) { cache[ck] }?.let { return it }
        val s = synth(ctx, text) ?: return null
        val out = robotize(s.pcm, s.rate, appPrefs(ctx).getFloat("robot", 0.5f))
        val res = out to (if (s.engine == "phone") s.rate else (s.rate * 0.97).roundToInt())
        synchronized(cache) { cache[ck] = res; while (cache.size > 4) cache.remove(cache.keys.first()) }
        return res
    }

    private suspend fun synth(ctx: Context, text: String): Synth? {
        val pref = appPrefs(ctx).getString("voice_engine", "auto") ?: "auto"
        val auto = listOf("edge", "google", "eleven", "gemini", "phone")
        val order = (if (pref != "auto") listOf(pref) else emptyList()) + auto.filter { it != pref }
        val now = System.currentTimeMillis()
        lastError = ""
        for (e in order) {
            if ((blocked[e] ?: 0L) > now) continue
            try {
                val r: Pair<ShortArray, Int>? = when (e) {
                    "google" -> if (Secrets.get(ctx, "gtts_key").isBlank()) null else googleTts(ctx, text)
                    "eleven" -> if (Secrets.get(ctx, "eleven_key").isBlank()) null else elevenTts(ctx, text)
                    "edge" -> edgeTts(ctx, text)
                    "gemini" -> if (Secrets.apiKey(ctx).isBlank()) null else geminiTts(ctx, text)
                    else -> androidTts(ctx, text)
                }
                if (r != null) { lastEngine = e; return Synth(r.first, r.second, e) }
            } catch (ex: Exception) {
                lastError += "$e: ${ex.message}; "
                blocked[e] = now + (if (e == "eleven" || e == "google") 600_000 else 90_000)
            }
        }
        return null
    }

    // ---------- Edge (Microsoft Neural, kostenlos, ohne Schluessel) ----------
    private fun secMsGec(): String {
        var t = System.currentTimeMillis() / 1000 + edgeSkew + 11644473600L
        t -= t % 300
        val s = (t * 10_000_000L).toString() + EDGE_TOKEN
        return MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02X".format(it) }
    }

    private fun edgeTts(ctx: Context, text: String): Pair<ShortArray, Int> {
        val voice = if (Lang.en(ctx)) appPrefs(ctx).getString("voice_edge_en", "en-GB-ThomasNeural") ?: "en-GB-ThomasNeural"
        else appPrefs(ctx).getString("voice_edge", "de-DE-ConradNeural") ?: "de-DE-ConradNeural"
        var lastFail = "unbekannt"
        for (attempt in 0 until 2) {
            val mp3 = ByteArrayOutputStream()
            val done = CountDownLatch(1)
            var failed = false
            var retry = false
            val reqId = UUID.randomUUID().toString().replace("-", "")
            val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$EDGE_TOKEN" +
                "&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=$EDGE_VER&ConnectionId=$reqId"
            val muid = UUID.randomUUID().toString().replace("-", "").uppercase()
            val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(url)
                .header("Pragma", "no-cache").header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", EDGE_UA).header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=$muid;").build()
            val ts = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(java.util.Date()) + " GMT+0000 (Coordinated Universal Time)"
            val parts = voice.split("-")
            val longName = "Microsoft Server Speech Text to Speech Voice (${parts[0]}-${parts[1]}, ${parts.drop(2).joinToString("-")})"
            val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${parts[0]}-${parts[1]}'><voice name='$longName'>" +
                "<prosody pitch='-10Hz' rate='-3%' volume='+0%'>$esc</prosody></voice></speak>"
            val ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                    webSocket.send("X-RequestId:$reqId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml")
                }
                override fun onMessage(webSocket: WebSocket, text: String) { if (text.contains("Path:turn.end")) done.countDown() }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val b = bytes.toByteArray()
                    if (b.size < 2) return
                    val hl = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                    if (2 + hl > b.size) return
                    if (String(b, 2, hl).contains("Path:audio")) mp3.write(b, 2 + hl, b.size - 2 - hl)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failed = true
                    lastFail = "${response?.code ?: ""} ${t.message ?: ""}"
                    if (response?.code == 403) {
                        response.headers.getDate("Date")?.let { edgeSkew = it.time / 1000 - System.currentTimeMillis() / 1000; retry = true }
                    }
                    done.countDown()
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
            })
            done.await(20, TimeUnit.SECONDS)
            try { ws.close(1000, null) } catch (_: Exception) {}
            client.dispatcher.executorService.shutdown()
            if (mp3.size() > 0) return decodeToPcm(mp3.toByteArray()) ?: throw IllegalStateException("MP3 nicht lesbar")
            if (!(failed && retry)) break
        }
        throw IllegalStateException("Edge: $lastFail")
    }

    // ---------- Google Cloud TTS (Chirp 3 HD) ----------
    private fun http(url: String, headers: Map<String, String>, body: String): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 60000; c.doOutput = true
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val bytes = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        return code to bytes
    }

    private fun googleTts(ctx: Context, text: String): Pair<ShortArray, Int> {
        val key = Secrets.get(ctx, "gtts_key")
        val voice = appPrefs(ctx).getString("voice_google", "")?.takeIf { it.isNotBlank() } ?: "de-DE-Chirp3-HD-Orus"
        val body = JSONObject().put("input", JSONObject().put("text", text))
            .put("voice", JSONObject().put("languageCode", "de-DE").put("name", voice))
            .put("audioConfig", JSONObject().put("audioEncoding", "LINEAR16").put("sampleRateHertz", 24000))
        val (code, raw) = http("https://texttospeech.googleapis.com/v1/text:synthesize?key=$key", mapOf("Content-Type" to "application/json"), body.toString())
        if (code != 200) throw IllegalStateException("Google-TTS $code")
        val b = Base64.decode(JSONObject(String(raw)).getString("audioContent"), Base64.DEFAULT)
        return parseWav(b) ?: throw IllegalStateException("WAV nicht lesbar")
    }

    // ---------- ElevenLabs (Free-Tier) ----------
    private fun elevenTts(ctx: Context, text: String): Pair<ShortArray, Int> {
        val key = Secrets.get(ctx, "eleven_key")
        val voice = appPrefs(ctx).getString("voice_eleven", "")?.takeIf { it.isNotBlank() } ?: "onwK4e9ZLuTAKqWW03F9"
        val body = JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2")
            .put("voice_settings", JSONObject().put("stability", 0.55).put("similarity_boost", 0.75))
        val (code, bytes) = http("https://api.elevenlabs.io/v1/text-to-speech/$voice",
            mapOf("xi-api-key" to key, "Content-Type" to "application/json", "Accept" to "audio/mpeg"), body.toString())
        if (code != 200) throw IllegalStateException("ElevenLabs $code")
        return decodeToPcm(bytes) ?: throw IllegalStateException("MP3 nicht lesbar")
    }

    // ---------- Gemini-TTS ----------
    private fun geminiTts(ctx: Context, text: String): Pair<ShortArray, Int> {
        val key = Secrets.apiKey(ctx)
        val prompt = "Sage mit tiefer, ruhiger, präziser Stimme, kühl und souverän wie ein hochintelligenter Roboter-Butler: $text"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Charon")))))
        for (m in listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")) {
            val (code, raw) = Gemini.post(key, m, body)
            if (code == 404) continue
            if (code != 200) throw IllegalStateException("Gemini-TTS $code")
            val data = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data")
            val sb = ByteBuffer.wrap(Base64.decode(data, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(sb.remaining()).also { sb.get(it) } to 24000
        }
        throw IllegalStateException("kein Gemini-TTS-Modell")
    }

    // ---------- Handy-Stimme (Fallback) ----------
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
        t.language = if (Lang.en(ctx)) Locale.UK else Locale.GERMAN
        val f = File(ctx.cacheDir, "tts_${System.nanoTime()}.wav")
        val done = CompletableDeferred<Boolean>()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { done.complete(false) }
        })
        t.setPitch(0.72f); t.setSpeechRate(0.95f)
        t.synthesizeToFile(text, null, f, "jarvis")
        val ok = withTimeoutOrNull(20000) { done.await() } == true
        val bytes = if (ok && f.exists()) f.readBytes() else null
        f.delete()
        return bytes?.let { parseWav(it) }
    }

    // ---------- Audio-Hilfen ----------
    private fun parseWav(b: ByteArray): Pair<ShortArray, Int>? {
        if (b.size < 44) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val ch = bb.getShort(22).toInt().coerceAtLeast(1)
        val sr = bb.getInt(24)
        var pos = 12; var start = -1; var len = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4); val l = bb.getInt(pos + 4)
            if (id == "data") { start = pos + 8; len = if (l <= 0 || start + l > b.size) b.size - start else l; break }
            if (l < 0) break
            pos += 8 + l + (l and 1)
        }
        if (start < 0) return null
        val frames = len / (2 * ch)
        return ShortArray(frames) { bb.getShort(start + it * 2 * ch) } to sr
    }

    /** MP3 -> PCM ueber den Android-Decoder. */
    private fun decodeToPcm(data: ByteArray): Pair<ShortArray, Int>? {
        val ex = MediaExtractor()
        ex.setDataSource(object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= data.size) return -1
                val n = minOf(size, (data.size - position).toInt())
                System.arraycopy(data, position.toInt(), buffer, offset, n)
                return n
            }
            override fun getSize(): Long = data.size.toLong()
            override fun close() {}
        })
        var track = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        if (track < 0) { ex.release(); return null }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val deadline = System.currentTimeMillis() + 15000
        while (!outDone && System.currentTimeMillis() < deadline) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!
                val chunk = ByteArray(info.size)
                b.position(info.offset); b.get(chunk); out.write(chunk)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        try { codec.stop() } catch (_: Exception) {}
        codec.release(); ex.release()
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames = bytes.size / (2 * ch)
        return ShortArray(frames) { bb.getShort(it * 2 * ch) } to sr
    }

    /** KI-/Roboter-Klang: Doppelstimme, leichte Ringmodulation, metallischer Kammfilter, Saettigung. s = 0..1 */
    private fun robotize(x: ShortArray, sr: Int, strength: Float): ShortArray {
        val s = strength.coerceIn(0f, 1f)
        val total = x.size + sr / 4
        val inp = FloatArray(total)
        for (i in x.indices) inp[i] = x[i] / 32768f
        val y = FloatArray(total)
        val d1 = (sr * 0.014).toInt().coerceAtLeast(1)
        val mod = 0.35f * s * s
        for (i in 0 until total) {
            val dry = inp[i] + (if (i >= d1) inp[i - d1] * 0.30f * s else 0f)
            val m = sin(2.0 * PI * 130.0 * i / sr).toFloat()
            y[i] = dry * (1 - mod) + dry * m * mod
        }
        val d2 = (sr * 0.006).toInt().coerceAtLeast(1)
        val fb = 0.35f * s
        for (i in d2 until total) y[i] += y[i - d2] * fb
        val drive = 1f + 1.2f * s
        val levels = if (s > 0.7f) 4096f - (s - 0.7f) / 0.3f * 3800f else 0f
        var peak = 0.0001f
        for (i in 0 until total) {
            var v = if (s > 0f) tanhf(y[i] * drive) else y[i]
            if (levels > 0f) v = (v * levels).roundToInt() / levels
            y[i] = v; peak = max(peak, abs(v))
        }
        val g = 0.92f / peak
        return ShortArray(total) { (y[it] * g * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun tanhf(v: Float): Float { val e = exp(2.0 * v.toDouble()); return ((e - 1) / (e + 1)).toFloat() }

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

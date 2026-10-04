package de.jarvis.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMsg(val fromUser: Boolean, val text: String, val sources: List<String> = emptyList())
data class Reply(val text: String, val sources: List<String>)
class ApiError(val code: Int, val raw: String) : Exception()

/** API-Schluessel verschluesselt (Android Keystore) speichern. */
object Secrets {
    private fun sp(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, "jarvis_secrets",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun apiKey(ctx: Context): String = try { sp(ctx).getString("api_key", "") ?: "" } catch (e: Exception) { "" }
    fun setApiKey(ctx: Context, k: String) { try { sp(ctx).edit().putString("api_key", k).apply() } catch (_: Exception) {} }
}

fun ttsEnabled(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).getBoolean("tts", true)
fun setTtsEnabled(ctx: Context, v: Boolean) { ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().putBoolean("tts", v).apply() }

object Ai {
    private const val FALLBACK_MODEL = "claude-haiku-4-5-20251001"
    private var model = "claude-sonnet-5-5"
    private var webOk = true
    private var history = JSONArray()
    val chat = mutableStateListOf<ChatMsg>()

    fun reset() { history = JSONArray(); chat.clear() }

    private fun trim() {
        while (history.length() > 30) {
            history.remove(0)
            while (history.length() > 0) {
                val m = history.getJSONObject(0)
                if (m.getString("role") == "user" && m.get("content") is String) break
                history.remove(0)
            }
        }
    }

    private suspend fun systemPrompt(ctx: Context): String {
        val now = SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN).format(Date())
        val mem = AppDb.get(ctx).memories().all()
        val memText = if (mem.isEmpty()) "(nichts gespeichert)" else mem.joinToString("\n") { "[${it.id}] ${it.text}" }
        return """Du bist JARVIS, der persönliche Sprachassistent des Nutzers auf seinem Android-Handy.
Antworte immer auf Deutsch, kurz (meist 1-3 Sätze), natürlich gesprochen, ohne Markdown und ohne Aufzählungszeichen.
Aktuelle Zeit: $now (Zeitzone des Nutzers).
Nutze Tools selbstständig: Kalender, Erinnerungen, Gerätestatus, Gedächtnis. Für aktuelle Informationen nutze web_search.
Zeitangaben für Tools immer als lokale Zeit im Format yyyy-MM-ddTHH:mm.
Speichere etwas im Gedächtnis nur, wenn der Nutzer es ausdrücklich verlangt (z. B. "merke dir ...").
Termine löschen: rufe das Tool auf, die App fragt den Nutzer selbst nach Bestätigung.
Wenn ein Tool meldet, dass eine Erlaubnis fehlt, sage: "Dafür brauche ich deine Erlaubnis." und erkläre kurz wo (Einstellungen der App).
Du kannst derzeit NICHT: Gmail lesen/senden. Sage das ehrlich, wenn danach gefragt wird.
Gespeichertes Gedächtnis:
$memText"""
    }

    private fun tools(): JSONArray {
        val a = JSONArray()
        ToolRegistry.all.forEach { a.put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", JSONObject(it.schema))) }
        if (webOk) a.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3))
        return a
    }

    private fun post(key: String, body: String): Pair<Int, String> {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 90000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to txt
    }

    private suspend fun runTool(ctx: Context, b: JSONObject): JSONObject {
        var err = false
        val out = try {
            ToolRegistry.find(b.getString("name"))?.run(ctx, b.optJSONObject("input") ?: JSONObject())
                ?: run { err = true; "Unbekanntes Tool" }
        } catch (e: Exception) { err = true; "Fehler: ${e.message}" }
        return JSONObject().put("type", "tool_result").put("tool_use_id", b.getString("id")).put("content", out)
            .apply { if (err) put("is_error", true) }
    }

    suspend fun ask(ctx: Context, text: String): Reply {
        val key = Secrets.apiKey(ctx)
        if (key.isBlank()) return Reply("Mir fehlt noch mein KI-Schlüssel. Du kannst ihn im Chat oder in den Einstellungen einfügen.", emptyList())
        val mark = history.length()
        try {
            trim()
            history.put(JSONObject().put("role", "user").put("content", text))
            val sources = linkedSetOf<String>()
            var finalText = ""
            for (turn in 0 until 8) {
                val body = JSONObject().put("model", model).put("max_tokens", 1024)
                    .put("system", systemPrompt(ctx)).put("tools", tools()).put("messages", history)
                val (code, raw) = post(key, body.toString())
                if (code == 404 && model != FALLBACK_MODEL) { model = FALLBACK_MODEL; continue }
                if (code == 400 && webOk && raw.contains("web_search")) { webOk = false; continue }
                if (code != 200) throw ApiError(code, raw)
                val resp = JSONObject(raw)
                val content = resp.getJSONArray("content")
                history.put(JSONObject().put("role", "assistant").put("content", content))
                val sb = StringBuilder()
                val results = JSONArray()
                for (j in 0 until content.length()) {
                    val b = content.getJSONObject(j)
                    when (b.optString("type")) {
                        "text" -> sb.append(b.optString("text"))
                        "web_search_tool_result" -> b.optJSONArray("content")?.let { c ->
                            for (k in 0 until c.length()) {
                                val r = c.optJSONObject(k)
                                if (r != null && r.optString("type") == "web_search_result") sources.add("${r.optString("title")} – ${r.optString("url")}")
                            }
                        }
                        "tool_use" -> results.put(runTool(ctx, b))
                    }
                }
                finalText = sb.toString()
                if (results.length() > 0) { history.put(JSONObject().put("role", "user").put("content", results)); continue }
                if (resp.optString("stop_reason") == "pause_turn") continue
                break
            }
            return Reply(finalText.trim().ifBlank { "Erledigt." }, sources.take(4))
        } catch (e: ApiError) {
            while (history.length() > mark) history.remove(history.length() - 1)
            val msg = when {
                e.code == 401 -> "Mein KI-Schlüssel wird nicht akzeptiert. Bitte prüfe ihn in den Einstellungen."
                e.raw.contains("credit", true) -> "Dein Anthropic-Guthaben ist aufgebraucht. Bitte lade es in der Anthropic-Console auf."
                e.code == 429 -> "Ich bin gerade überlastet. Versuch es bitte gleich nochmal."
                else -> "Meine KI meldet ein Problem (${e.code}). Ich versuche es später nochmal."
            }
            return Reply(msg, emptyList())
        } catch (e: Exception) {
            while (history.length() > mark) history.remove(history.length() - 1)
            return Reply("Ich kann meine KI gerade nicht erreichen. Bitte prüfe die Internetverbindung.", emptyList())
        }
    }
}

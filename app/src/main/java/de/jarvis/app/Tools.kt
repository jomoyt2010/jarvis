package de.jarvis.app

import android.content.Context
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/** Tool-Schnittstelle fuer die KI (Phase 3). Neue Tools: Interface implementieren, in ToolRegistry eintragen. */
interface JarvisTool {
    val name: String
    val description: String
    val schema: String // JSON-Schema der Parameter
    suspend fun run(ctx: Context, args: JSONObject): String
}

object ToolRegistry {
    val all: List<JarvisTool> = listOf(CreateReminderTool, DeleteReminderTool)
    fun find(name: String) = all.firstOrNull { it.name == name }
}

object CreateReminderTool : JarvisTool {
    override val name = "create_reminder"
    override val description = "Erstellt eine Erinnerung zu einem bestimmten Zeitpunkt."
    override val schema = """{"type":"object","properties":{"text":{"type":"string"},"time":{"type":"string","description":"Lokale Zeit ISO-8601, z.B. 2026-10-05T17:00"},"repeat":{"type":"string","enum":["NONE","DAILY","WEEKLY"]},"as_call":{"type":"boolean"}},"required":["text","time"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        val at = LocalDateTime.parse(args.getString("time")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        Reminders.add(ctx, args.getString("text"), at, args.optString("repeat", "NONE"), args.optBoolean("as_call", false))
        return "Erinnerung erstellt fuer ${args.getString("time")}"
    }
}

object DeleteReminderTool : JarvisTool {
    override val name = "delete_reminder"
    override val description = "Loescht eine Erinnerung anhand ihrer ID."
    override val schema = """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        Reminders.remove(ctx, args.getLong("id")); return "Erinnerung geloescht"
    }
}

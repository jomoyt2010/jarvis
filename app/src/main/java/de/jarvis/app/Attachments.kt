package de.jarvis.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/** Anhang fuer die KI: Bild/PDF/Audio als Bytes, Textdateien als Text. */
class Attachment(val name: String, val mime: String, val data: ByteArray? = null, val text: String? = null, val thumb: Bitmap? = null)

object Attachments {
    const val MAX_BYTES = 12_000_000
    private val TEXT_EXT = setOf("txt", "md", "csv", "json", "xml", "html", "htm", "log", "kt", "java", "py", "js", "ts", "css", "yml", "yaml", "ini", "tex")

    fun name(ctx: Context, uri: Uri): String = (try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null }) ?: "Datei"

    fun load(ctx: Context, uri: Uri): Attachment? = try {
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = name(ctx, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            mime.startsWith("image/") -> image(ctx, uri, name)
            mime == "application/pdf" || mime.startsWith("audio/") -> {
                val b = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (b == null || b.size > MAX_BYTES) null else Attachment(name, mime, data = b)
            }
            mime.startsWith("text/") || ext in TEXT_EXT || mime == "application/json" || mime == "application/xml" -> {
                val t = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (t == null) null else Attachment(name, "text/plain", text = t.take(200_000))
            }
            else -> null
        }
    } catch (e: Exception) { null }

    private fun image(ctx: Context, uri: Uri, name: String): Attachment? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rot = try { cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1 } catch (e: Exception) { 1 }
        val deg = when (rot) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val sc = 240f / maxOf(bmp.width, bmp.height)
        val thumb = Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true)
        return Attachment(name, "image/jpeg", data = out.toByteArray(), thumb = thumb)
    }
}

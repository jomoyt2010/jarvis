package de.jarvis.app

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.roundToInt

class MediaInfo(val title: String, val artist: String, val art: Bitmap?, val playing: Boolean,
                val position: Long, val duration: Long, val stamp: Long, val speed: Float, val controller: MediaController)
class TimerInfo(val key: String, val label: String, val whenMs: Long, val countDown: Boolean, val staticText: String)
class CallInfo(val key: String, val name: String, val actions: List<Pair<String, PendingIntent>>)

/** Sammelt Musik (MediaSession), Timer, Anrufe und JARVIS-Zustand fuer die Island. */
object IslandHub {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var media: MediaInfo? = null
    @Volatile var timer: TimerInfo? = null
    @Volatile var call: CallInfo? = null
    @Volatile var jarvisState = ""
    @Volatile var jarvisText = ""
    @Volatile var mediaPkg = ""
    @Volatile var mediaChannel = ""
    @Volatile var timerPkg = ""
    @Volatile var timerChannel = ""
    @Volatile private var lastPlayingAt = 0L
    private var controllers: List<MediaController> = emptyList()
    private val cancelled = HashMap<String, Long>()
    private val clockPkgs = setOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock",
        "com.oneplus.deskclock", "com.coloros.alarmclock", "com.huawei.deskclock")

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { updateMedia() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { updateMedia() }
        override fun onSessionDestroyed() { updateMedia() }
    }

    fun setJarvis(state: String, text: String) {
        jarvisState = state; jarvisText = text
        main.post { Island.refresh() }
    }

    fun note(text: String, seconds: Int = 5) {
        setJarvis("note", text)
        main.postDelayed({ if (jarvisState == "note" && jarvisText == text) setJarvis("", "") }, seconds * 1000L)
    }

    fun mediaActive(): Boolean {
        val m = media ?: return false
        return m.playing || System.currentTimeMillis() - lastPlayingAt < 60_000
    }

    fun connect(l: NotificationListenerService) {
        val app = l.applicationContext
        try {
            val msm = app.getSystemService(MediaSessionManager::class.java)
            val cn = ComponentName(app, IslandListener::class.java)
            msm.addOnActiveSessionsChangedListener(MediaSessionManager.OnActiveSessionsChangedListener { list -> setControllers(list ?: emptyList()) }, cn, main)
            setControllers(msm.getActiveSessions(cn))
        } catch (e: Exception) {}
        try { for (s in l.activeNotifications) onNotification(l, s, true) } catch (e: Exception) {}
    }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { try { it.unregisterCallback(cb) } catch (_: Exception) {} }
        controllers = list
        list.forEach { try { it.registerCallback(cb, main) } catch (_: Exception) {} }
        updateMedia()
    }

    private fun updateMedia() {
        val best = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
            ?: controllers.firstOrNull { it.metadata != null }
        if (best == null) media = null else {
            val md = best.metadata!!
            val st = best.playbackState
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            var art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (art != null && art.width > 200) art = Bitmap.createScaledBitmap(art, 160, (160f * art.height / art.width).toInt().coerceAtLeast(1), true)
            val playing = st?.state == PlaybackState.STATE_PLAYING
            if (playing) lastPlayingAt = System.currentTimeMillis()
            media = MediaInfo(title, artist, art, playing, st?.position ?: 0L, md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                st?.lastPositionUpdateTime ?: 0L, st?.playbackSpeed ?: 1f, best)
        }
        Island.refresh()
    }

    private fun tryCancel(l: NotificationListenerService, sbn: StatusBarNotification) {
        val now = System.currentTimeMillis()
        if (now - (cancelled[sbn.key] ?: 0L) < 3000) return
        cancelled[sbn.key] = now
        try { l.cancelNotification(sbn.key) } catch (_: Exception) {}
    }

    fun onNotification(l: NotificationListenerService, sbn: StatusBarNotification, posted: Boolean) {
        val n = sbn.notification ?: return
        val pkg = sbn.packageName
        if (pkg == l.packageName) return
        val ex = n.extras ?: return
        val p = appPrefs(l)
        val tpl = ex.getString(Notification.EXTRA_TEMPLATE) ?: ""
        if (tpl.endsWith("MediaStyle")) {
            if (posted) {
                mediaPkg = pkg; mediaChannel = n.channelId ?: ""
                if (p.getBoolean("island_hide_media", false)) tryCancel(l, sbn)
            }
            return
        }
        if (n.category == Notification.CATEGORY_CALL) {
            if (posted) {
                val acts = (n.actions ?: emptyArray()).mapNotNull { a -> a.actionIntent?.let { (a.title?.toString() ?: "") to it } }.take(3)
                call = CallInfo(sbn.key, ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Anruf", acts)
            } else if (call?.key == sbn.key) call = null
            main.post { Island.refresh() }
            return
        }
        if (pkg in clockPkgs) {
            if (posted) {
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
                if (chrono) {
                    timerPkg = pkg; timerChannel = n.channelId ?: ""
                    timer = TimerInfo(sbn.key, title.ifBlank { "Timer" }, n.`when`, ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN), "")
                    if (p.getBoolean("island_hide_timer", false)) tryCancel(l, sbn)
                } else if (Regex("timer|stoppuhr|stopwatch", RegexOption.IGNORE_CASE).containsMatchIn("$title $text")) {
                    val m = Regex("\\d{1,2}:\\d{2}(:\\d{2})?").find("$title $text")
                    if (m != null) {
                        timerPkg = pkg; timerChannel = n.channelId ?: ""
                        timer = TimerInfo(sbn.key, title.ifBlank { "Timer" }, 0L, false, m.value)
                        if (p.getBoolean("island_hide_timer", false)) tryCancel(l, sbn)
                    }
                }
            } else if (timer?.key == sbn.key) timer = null
            main.post { Island.refresh() }
        }
    }
}

class IslandListener : NotificationListenerService() {
    override fun onListenerConnected() { IslandHub.connect(this) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { if (sbn != null) IslandHub.onNotification(this, sbn, true) }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { if (sbn != null) IslandHub.onNotification(this, sbn, false) }
}

/** Die Insel selbst: ein schwarzes Overlay oben an der Kamera. */
object Island {
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var bg: GradientDrawable? = null
    private var row: FrameLayout? = null
    private var leftImg: ImageView? = null
    private var leftTxt: TextView? = null
    private var rightTxt: TextView? = null
    private var box: LinearLayout? = null
    private var expanded = false
    private var idx = 0
    private var anim: ValueAnimator? = null
    private var callbacks = false
    private var ticking = false
    private val collapse = Runnable { expanded = false; refreshNow() }
    private val tickRun = object : Runnable {
        override fun run() { refreshNow(); if (ticking) main.postDelayed(this, 1000) }
    }

    private fun dp(v: Int): Int = ((appCtx?.resources?.displayMetrics?.density ?: 2.5f) * v).roundToInt()

    fun apply(ctx: Context) { appCtx = ctx.applicationContext; main.post { applyNow() } }
    fun refresh() { main.post { refreshNow() } }

    private fun applyNow() {
        val c = appCtx ?: return
        val on = appPrefs(c).getBoolean("island_on", false) && Settings.canDrawOverlays(c)
        if (!on) { remove(); return }
        if (root == null) create(c)
        if (!callbacks) {
            callbacks = true
            c.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) { refresh() }
                override fun onLowMemory() {}
            })
        }
        refreshNow()
    }

    private fun remove() {
        ticking = false; main.removeCallbacks(tickRun)
        try { root?.let { wm?.removeView(it) } } catch (_: Exception) {}
        root = null; lp = null
    }

    private fun tv(c: Context, text: String, sp: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView = TextView(c).apply {
        this.text = text; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }

    private fun create(c: Context) {
        wm = c.getSystemService(WindowManager::class.java)
        val bgd = GradientDrawable().apply { setColor(Color.BLACK) }
        val r = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; background = bgd }
        val rw = FrameLayout(c)
        val li = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        val lt = tv(c, "", 15f).apply { visibility = View.GONE }
        val rt = tv(c, "", 13f).apply { maxLines = 1 }
        rw.addView(li, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(14) })
        rw.addView(lt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(14) })
        rw.addView(rt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(16) })
        val bx = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(14)); visibility = View.GONE }
        r.addView(rw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        r.addView(bx, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.setOnClickListener { onTap() }
        r.setOnLongClickListener { openJarvis(c); true }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val params = WindowManager.LayoutParams(dp(126), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { wm?.addView(r, params) } catch (e: Exception) { return }
        root = r; lp = params; bg = bgd; row = rw; leftImg = li; leftTxt = lt; rightTxt = rt; box = bx
    }

    private fun activeList(): List<String> {
        val l = ArrayList<String>()
        if (IslandHub.call != null) l.add("call")
        if (IslandHub.jarvisState.isNotBlank()) l.add("jarvis")
        if (IslandHub.timer != null) l.add("timer")
        if (IslandHub.mediaActive()) l.add("media")
        return l
    }

    private fun stateLabel(): String = when (IslandHub.jarvisState) {
        "listening" -> "Höre zu"; "thinking" -> "Denke …"; "speaking" -> "Spricht"; else -> "JARVIS"
    }

    private fun timerText(t: TimerInfo): String {
        if (t.whenMs == 0L) return t.staticText
        val now = System.currentTimeMillis()
        val ms = if (t.countDown) t.whenMs - now else now - t.whenMs
        val s = (if (ms < 0) 0L else ms) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun mediaPos(m: MediaInfo): Long {
        val p = if (m.playing) m.position + ((SystemClock.elapsedRealtime() - m.stamp) * m.speed).toLong() else m.position
        return p.coerceIn(0L, if (m.duration > 0) m.duration else p)
    }

    private fun refreshNow() {
        val c = appCtx ?: return
        val r = root ?: return
        val l = lp ?: return
        val p = appPrefs(c)
        val calib = p.getBoolean("island_calib", false)
        val idle = p.getBoolean("island_idle", false)
        val baseW = dp(p.getInt("island_w", 126)); val h = dp(p.getInt("island_h", 36)); val expW = dp(p.getInt("island_ew", 340))
        val active = activeList()
        if (idx >= active.size) idx = 0
        val cur = active.getOrNull(idx)
        if (cur == null) expanded = false
        val landscape = c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val visible = !landscape && (cur != null || idle || calib)
        r.visibility = if (visible) View.VISIBLE else View.GONE

        l.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        l.x = dp(p.getInt("island_x", 0)); l.y = dp(p.getInt("island_y", 6))
        row?.let { val rl = it.layoutParams; rl.height = h; it.layoutParams = rl }

        val isExp = cur != null && expanded
        val targetW = if (isExp) expW else if (cur != null) baseW + dp(100) else baseW
        bg?.cornerRadius = if (isExp) dp(30).toFloat() else h / 2f
        bg?.setStroke(if (calib) dp(2) else 0, if (calib) Color.RED else Color.TRANSPARENT)

        // Kompakte Anzeige links/rechts der Kamera
        leftImg?.visibility = View.GONE; leftTxt?.visibility = View.GONE; rightTxt?.text = ""
        when (cur) {
            "media" -> {
                val m = IslandHub.media
                if (m?.art != null) { leftImg?.setImageBitmap(m.art); leftImg?.visibility = View.VISIBLE }
                else { leftTxt?.text = "♪"; leftTxt?.visibility = View.VISIBLE }
                rightTxt?.text = if (m?.playing == true) "▮▮▮" else "❚❚"
            }
            "timer" -> { leftTxt?.text = "⏱"; leftTxt?.visibility = View.VISIBLE; IslandHub.timer?.let { rightTxt?.text = timerText(it) } }
            "call" -> { leftTxt?.text = "📞"; leftTxt?.visibility = View.VISIBLE; rightTxt?.text = (IslandHub.call?.name ?: "").take(10) }
            "jarvis" -> { leftTxt?.text = "●"; leftTxt?.setTextColor(0xFF3DDCFF.toInt()); leftTxt?.visibility = View.VISIBLE; rightTxt?.text = stateLabel() }
        }
        if (cur != "jarvis") leftTxt?.setTextColor(Color.WHITE)

        // Aufgeklappter Bereich
        box?.visibility = if (isExp) View.VISIBLE else View.GONE
        if (isExp) renderBox(c, cur!!, active.size)

        if (l.width != targetW) {
            anim?.cancel()
            val from = if (l.width <= 0) targetW else l.width
            anim = ValueAnimator.ofInt(from, targetW).apply {
                duration = 220
                addUpdateListener { l.width = it.animatedValue as Int; try { wm?.updateViewLayout(r, l) } catch (_: Exception) {} }
                start()
            }
        } else try { wm?.updateViewLayout(r, l) } catch (_: Exception) {}

        if (visible && cur != null) { if (!ticking) { ticking = true; main.postDelayed(tickRun, 1000) } }
        else { ticking = false; main.removeCallbacks(tickRun) }
    }

    private fun renderBox(c: Context, cur: String, count: Int) {
        val b = box ?: return
        b.removeAllViews()
        val grey = 0xFFAAAAAA.toInt()
        val cyan = 0xFF3DDCFF.toInt()
        fun btn(label: String, size: Float, color: Int = Color.WHITE, action: () -> Unit): TextView = tv(c, label, size, color).apply {
            setPadding(dp(18), dp(6), dp(18), dp(6))
            setOnClickListener { try { action() } catch (_: Exception) {}; resetCollapse() }
        }
        when (cur) {
            "media" -> {
                val m = IslandHub.media ?: return
                val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val art = ImageView(c).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (m.art != null) setImageBitmap(m.art) else setBackgroundColor(0xFF222222.toInt())
                }
                top.addView(art, LinearLayout.LayoutParams(dp(56), dp(56)))
                val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                col.addView(tv(c, m.title.ifBlank { "Unbekannt" }, 16f, Color.WHITE, true))
                col.addView(tv(c, m.artist, 13f, grey))
                top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                b.addView(top)
                if (m.duration > 0) {
                    val pb = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000; progress = (mediaPos(m) * 1000 / m.duration).toInt().coerceIn(0, 1000)
                        progressTintList = ColorStateList.valueOf(cyan)
                    }
                    b.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(10) })
                }
                val btns = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                btns.addView(btn("⏮", 26f) { m.controller.transportControls.skipToPrevious() })
                btns.addView(btn(if (m.playing) "⏸" else "▶", 26f) { if (m.playing) m.controller.transportControls.pause() else m.controller.transportControls.play() })
                btns.addView(btn("⏭", 26f) { m.controller.transportControls.skipToNext() })
                b.addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
            "timer" -> {
                val t = IslandHub.timer ?: return
                b.addView(tv(c, timerText(t), 38f, Color.WHITE, true).apply { gravity = Gravity.CENTER_HORIZONTAL })
                b.addView(tv(c, t.label, 14f, grey).apply { gravity = Gravity.CENTER_HORIZONTAL })
            }
            "call" -> {
                val cl = IslandHub.call ?: return
                b.addView(tv(c, cl.name, 20f, Color.WHITE, true))
                val rb = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                cl.actions.forEach { (title, pi) -> rb.addView(btn(title, 16f, cyan) { pi.send() }) }
                b.addView(rb)
            }
            "jarvis" -> {
                b.addView(tv(c, "● " + stateLabel(), 15f, cyan, true))
                if (IslandHub.jarvisText.isNotBlank()) b.addView(tv(c, IslandHub.jarvisText.take(200), 15f, Color.WHITE).apply { maxLines = 5 })
            }
        }
        if (count > 1) b.addView(tv(c, "${idx + 1}/$count ›", 12f, grey).apply {
            gravity = Gravity.END
            setOnClickListener { idx = (idx + 1) % count; refreshNow(); resetCollapse() }
        })
    }

    private fun onTap() {
        val c = appCtx ?: return
        if (activeList().isEmpty()) { openJarvis(c); return }
        expanded = !expanded
        refreshNow()
        if (expanded) resetCollapse()
    }

    private fun resetCollapse() { main.removeCallbacks(collapse); main.postDelayed(collapse, 9000) }

    private fun openJarvis(c: Context) {
        try {
            c.startActivity(Intent(c, CallActivity::class.java).putExtra("outgoing", true).putExtra("assist", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (_: Exception) {}
    }
}

// ======================= Tools: JARVIS steuert die Island =======================
object IslandConfigTool : JarvisTool {
    override val name = "island_config"
    override val description = "Stellt die Dynamic Island ein: ein/aus, Breite, Höhe, Abstand oben, horizontale Position, aufgeklappte Breite (alles in dp)."
    override val schema = """{"type":"object","properties":{"enabled":{"type":"boolean"},"width_dp":{"type":"integer"},"height_dp":{"type":"integer"},"offset_y_dp":{"type":"integer"},"offset_x_dp":{"type":"integer"},"expanded_width_dp":{"type":"integer"}}}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        val e = appPrefs(ctx).edit()
        if (args.has("enabled")) {
            val on = args.getBoolean("enabled")
            if (on && !Settings.canDrawOverlays(ctx)) { Perms.request(ctx, Step.OVERLAY, false) {}; return "Dafür brauche ich deine Erlaubnis: „Über anderen Apps einblenden“. Ich öffne die Seite." }
            e.putBoolean("island_on", on)
        }
        if (args.has("width_dp")) e.putInt("island_w", args.getInt("width_dp").coerceIn(60, 260))
        if (args.has("height_dp")) e.putInt("island_h", args.getInt("height_dp").coerceIn(20, 80))
        if (args.has("offset_y_dp")) e.putInt("island_y", args.getInt("offset_y_dp").coerceIn(-20, 60))
        if (args.has("offset_x_dp")) e.putInt("island_x", args.getInt("offset_x_dp").coerceIn(-200, 200))
        if (args.has("expanded_width_dp")) e.putInt("island_ew", args.getInt("expanded_width_dp").coerceIn(240, 400))
        e.apply()
        Island.apply(ctx)
        return "Die Dynamic Island wurde angepasst."
    }
}

object IslandNotifyTool : JarvisTool {
    override val name = "island_notify"
    override val description = "Zeigt kurz einen Text in der Dynamic Island an."
    override val schema = """{"type":"object","properties":{"text":{"type":"string"},"seconds":{"type":"integer"}},"required":["text"]}"""
    override suspend fun run(ctx: Context, args: JSONObject): String {
        IslandHub.note(args.getString("text"), args.optInt("seconds", 5).coerceIn(2, 20))
        return "Angezeigt."
    }
}

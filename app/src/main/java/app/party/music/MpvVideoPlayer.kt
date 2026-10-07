package app.party.music

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import io.github.yuroyami.libmpvkt.MpvProperties
import io.github.yuroyami.libmpvkt.TrackType
import io.github.yuroyami.libmpvkt.getOrThrow
import io.github.yuroyami.libmpvkt.getOrNull
import io.github.yuroyami.libmpvkt.view.MpvOptions
import io.github.yuroyami.libmpvkt.view.MpvView
import java.util.concurrent.Executors

/**
 * TEST APP — YOUTUBE ke liye MPV (VIDEO + AUDIO).
 *
 * Ye view root me SAB SE NEECHE (WebView ke peeche) lagta hai. Page (WebView) upar rehta hai
 * magar player area me CSS "hole" bana diya jata hai — us jagah MPV ka surface nazar aata hai.
 * Nateeja: MPV ki video dikhti hai, aawaz MPV se aati hai, aur page ke apne controls
 * (play/pause/seek/quality) jaise hain waise hi kaam karte hain (wo WebView me hain, upar hain).
 */
class MpvVideoPlayer(private val act: Activity, private val root: FrameLayout) {

    private var view: MpvView? = null
    private var currentSource = ""
    private var fullscreen = false
    @Volatile private var coreReady = false
    @Volatile private var ensuring = false
    @Volatile private var localError: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // ── ACT2: MPV surface ke GOL corners (page ke .player-wrap jaisa) ──
    // Page ka card: 21px radius (mini bar 13px). Android 12+ (API 31) par SurfaceView outline
    // clip hota hai -> seedha clipToOutline + rounded outline. Purane Android par 4 chhote
    // corner-patch views (page bg rang) MPV ke UPAR magar WebView ke NEECHE lagte hain.
    private var cornerDp = 21f
    private var masks: Array<CornerMaskView>? = null
    private var maskColor = 0xFF0F0C29L.toInt()
    private val cornerOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = if (fullscreen) 0f else cornerDp * act.resources.displayMetrics.density
            outline.setRoundRect(0, 0, view.width, view.height, r)
        }
    }

    /** View banao + core tayyar karo (background me prepare, main thread par attach). */
    fun ensure() {
        if (ensuring || coreReady) return
        ensuring = true
        main.post {
            try {
                if (view == null) {
                    val v = MpvView(act)
                    v.isClickable = false
                    v.isFocusable = false
                    v.visibility = View.INVISIBLE
                    // index 0 = WebView ke PEECHE (video page ke "hole" se nazar aati hai)
                    root.addView(v, 0, FrameLayout.LayoutParams(dp(160), dp(90)))
                    view = v
                    // ACT2: Android 12+ -> surface khud gol corners me clip ho jata hai
                    if (Build.VERSION.SDK_INT >= 31) {
                        v.clipToOutline = true
                        v.outlineProvider = cornerOutline
                    }
                    ensureMasks(v)   // Android < 12: 4 corner patches
                }
                val v = view ?: return@post
                io.execute {
                    try {
                        val prepared = v.prepare(slowNetOptions())
                        main.post {
                            try {
                                v.attach(prepared)
                                coreReady = true
                                localError = null
                                Log.i(TAG, "core ready")
                            } catch (t: Throwable) {
                                localError = t.message
                                Log.e(TAG, "attach fail", t)
                            }
                        }
                    } catch (t: Throwable) {
                        localError = t.message
                        Log.e(TAG, "prepare fail", t)
                    }
                }
            } catch (t: Throwable) {
                localError = t.message
                Log.e(TAG, "ensure fail", t)
            }
        }
    }

    val isReady: Boolean get() = coreReady && view?.mpv != null
    val error: String? get() = localError

    /** URL chalao (pos par). startMuted=true -> aawaz baad me kholi jayegi (double audio se bachne ke liye). */
    fun play(url: String, pos: Double, startMuted: Boolean, audioUrl: String? = null,
             userAgent: String = YtAudioSource.UA, referer: String? = null) {
        main.post {
            try {
                val v = view ?: return@post
                if (v.mpv == null) { localError = "core tayyar nahi"; return@post }
                setSyncSpeed(1.0)
                currentSource = url
                dispPos = -1.0
                localError = null
                v.muted = startMuted
                v.paused = false
                // MPV length-quoted option values protect URL commas/quotes. Attach audio
                // during loadfile, not via a racing audio-add after video has started.
                fun quoted(value: String) = "%${value.toByteArray(Charsets.UTF_8).size}%$value"
                val options = mutableListOf("start=${if (pos.isFinite()) pos.coerceAtLeast(0.0) else 0.0}",
                    "user-agent=" + quoted(userAgent), "audio-files-clr=",
                    // Separate YouTube audio is a second demuxer: split the budget.
                    "demuxer-max-bytes=${(if (audioUrl.isNullOrBlank()) 100L else 50L) * 1024 * 1024}",
                    "demuxer-max-back-bytes=${(if (audioUrl.isNullOrBlank()) 8L else 4L) * 1024 * 1024}")
                AudioTrackMemory.get(url)?.let { options += "aid=$it" }
                if (!audioUrl.isNullOrBlank()) options += "audio-files-append=" + quoted(audioUrl)
                if (!referer.isNullOrBlank()) options += "http-header-fields-append=" + quoted("Referer: $referer")
                v.mpv?.command("loadfile", url, "replace", "-1", options.joinToString(","))?.getOrThrow()
                Log.i(TAG, "play (muted=$startMuted) pos=$pos")
            } catch (t: Throwable) {
                localError = t.message
                Log.e(TAG, "play fail", t)
            }
        }
    }

    fun pause() { main.post { try { view?.paused = true } catch (t: Throwable) {} } }
    fun resume() { main.post { try { view?.paused = false } catch (t: Throwable) {} } }
    fun seekTo(pos: Double) { main.post { try { view?.timePos = pos; dispPos = pos } catch (t: Throwable) {} } }
    fun setMuted(m: Boolean) { main.post { try { view?.muted = m } catch (t: Throwable) {} } }

    /* MPV ka time-pos kabhi kabhi +-0.25s peeche jump karta hai (A/V sync jitter) — display par
       time line hilti rehti. Is liye display position smooth ki jati hai: aage sirf asli barhaat,
       peeche sirf asli seek (>1.5s), pause par exact freeze. (Watch-Party-Mpv repo se idea) */
    @Volatile private var dispPos: Double = -1.0

    fun position(): Double {
        val raw = try { view?.timePos } catch (t: Throwable) { null }
        if (raw == null || !raw.isFinite() || raw < 0) return if (dispPos >= 0) dispPos else 0.0
        if (dispPos < 0) dispPos = raw
        else if (raw > dispPos) dispPos = raw
        else if (isPaused()) dispPos = raw
        else if (dispPos - raw > 1.5) dispPos = raw
        return dispPos
    }

    /** Video ki asli lambai (seconds) — 0.0 agar abhi maloom na ho. */
    fun duration(): Double = try {
        val d = view?.duration ?: 0.0
        if (d.isFinite() && d > 0) d else 0.0
    } catch (t: Throwable) { 0.0 }

    fun isPaused(): Boolean = try { view?.paused != false } catch (t: Throwable) { true }

    fun isMuted(): Boolean = try { view?.muted == true } catch (t: Throwable) { false }

    /** muxed stream na mile: aawaz alag stream se jodo (MPV: audio-add) — video 360p + audio alag. */
    fun addAudio(url: String) {
        main.post {
            try {
                val mpv = view?.mpv ?: return@post
                mpv.command("audio-add", url)
                Log.i(TAG, "audio-add: alag aawaz stream lagi")
            } catch (t: Throwable) { Log.e(TAG, "audio-add fail", t) }
        }
    }

    /** Pehla frame aa gaya? (timePos null hota hai jab tak video shuru na ho) */
    fun hasFrame(): Boolean = try { view != null && view?.mpv != null && view?.timePos != null && actualHeight() > 0 } catch (t: Throwable) { false }

    fun ended(): Boolean = try { view?.mpv?.get(MpvProperties.EofReached)?.getOrNull() == true } catch (_: Throwable) { false }

    fun actualHeight(): Int = try { view?.mpv?.get(MpvProperties.Height)?.getOrNull()?.toInt() ?: 0 } catch (_: Throwable) { 0 }
    fun audioCodec(): String = try { view?.mpv?.get(MpvProperties.AudioCodecName)?.getOrNull().orEmpty() } catch (_: Throwable) { "" }

    fun show() { main.post { try { view?.visibility = View.VISIBLE } catch (t: Throwable) {}; syncMasks() } }
    fun hide() { main.post { try { view?.visibility = View.INVISIBLE } catch (t: Throwable) {}; syncMasks() } }

    /** Player area ke exactly upar: CSS px -> device px (density se multiply).
        mini=true -> page ki mini bar (radius 13px), warna 21px (page ka .player-wrap radius). */
    fun setRect(x: Float, y: Float, w: Float, h: Float, mini: Boolean = false) {
        main.post {
            try {
                val v = view
                if (v != null && !fullscreen) {
                    cornerDp = if (mini) 13f else 21f
                    if (Build.VERSION.SDK_INT >= 31) v.invalidateOutline()
                    val d = act.resources.displayMetrics.density
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    val nw = (w * d).toInt(); val nh = (h * d).toInt()
                    val nx = (x * d).toInt(); val ny = (y * d).toInt()
                    if (lp.width != nw || lp.height != nh || lp.leftMargin != nx || lp.topMargin != ny) {
                        lp.width = nw; lp.height = nh; lp.leftMargin = nx; lp.topMargin = ny
                        v.layoutParams = lp
                    }
                }
            } catch (t: Throwable) {}
            syncMasks()
        }
    }

    // Device-local display choice. Never published as Party playback state.
    val aspectLabels = listOf("Original", "16:9", "16:10", "4:3", "2.35:1", "Pan & Scan")
    var aspectIndex = 0
        private set
    fun setAspect(index: Int): Boolean {
        if (index !in aspectLabels.indices) return false
        val mpv = view?.mpv ?: return false
        return try {
            val ratio = listOf("-1", "1.777778", "1.600000", "1.333333", "2.350000", "-1")[index]
            mpv.setString("video-aspect-override", ratio).getOrThrow()
            mpv.setString("panscan", if (index == 5) "1" else "0").getOrThrow()
            aspectIndex = index
            true
        } catch (_: Throwable) { false }
    }
    fun setSyncSpeed(rate: Double) {
        if (rate !in listOf(0.95, 0.995, 1.0, 1.005)) return
        try { view?.mpv?.setString("speed", rate.toString())?.getOrThrow() } catch (_: Throwable) {}
    }
    fun syncSpeed(): Double = try { view?.mpv?.get(MpvProperties.Speed)?.getOrNull() ?: 1.0 } catch (_: Throwable) { 1.0 }
    fun rawPosition(): Double = try { view?.timePos?.takeIf { it.isFinite() && it >= 0 } ?: 0.0 } catch (_: Throwable) { 0.0 }

    fun setFullscreen(on: Boolean) {
        fullscreen = on
        if(on) view?.layoutParams = FrameLayout.LayoutParams(-1, -1)
        try {
            val v = view
            if (v != null && Build.VERSION.SDK_INT >= 31) v.invalidateOutline()
        } catch (t: Throwable) {}
        if (Looper.myLooper() == Looper.getMainLooper()) syncMasks() else main.post { syncMasks() }
    }
    fun loaded(): Boolean = try { view?.timePos != null && (audioCodec().isNotBlank() || actualHeight()>0) } catch (_: Throwable) { false }
    fun hasArtwork(): Boolean = try { view?.mpv?.get(MpvProperties.TrackList)?.getOrNull()?.any { it.isAlbumArt || it.isImage } == true } catch (_: Throwable) { false }
    data class AudioTrack(val id: Int, val label: String, val selected: Boolean)
    fun audioTracks(): List<AudioTrack> = try {
        view?.mpv?.get(MpvProperties.TrackList)?.getOrNull().orEmpty().filter { it.type == TrackType.Audio }.map {
            AudioTrack(it.id, listOfNotNull(it.lang?.takeIf { l -> l.isNotBlank() }, it.title?.takeIf { t -> t.isNotBlank() }, it.codec).joinToString(" · ").ifBlank { "Audio ${it.id}" }, it.selected)
        }
    } catch (_: Throwable) { emptyList() }
    fun selectAudio(id: Int, done: (Boolean) -> Unit) {
        if(audioTracks().none { it.id==id }) { done(false); return }
        val source=currentSource
        try {
            view?.mpv?.setString("aid", id.toString())?.getOrThrow()
            main.postDelayed({
                val ok=source==currentSource && audioTracks().any { it.id==id && it.selected }
                if(ok)AudioTrackMemory.put(source,id)
                done(ok)
            },450)
        } catch (_: Throwable) { done(false) }
    }
    fun title(): String = try { view?.mpv?.get(MpvProperties.Metadata)?.getOrNull()?.get("title").orEmpty() } catch (_: Throwable) { "" }

    fun stop() { main.post { try { view?.mpv?.command("stop"); dispPos = -1.0 } catch (t: Throwable) {} } }

    fun destroy() {
        main.post {
            try { view?.destroy() } catch (t: Throwable) {}
            try { val v = view; if (v != null && v.parent === root) root.removeView(v) } catch (t: Throwable) {}
            try { masks?.forEach { m -> root.removeView(m) } } catch (t: Throwable) {}
            masks = null
            view = null; coreReady = false; ensuring = false
        }
    }

    // Active foreground core: 100 MiB forward / 8 MiB backward packet cache.
    // 24h read-ahead lets short finite songs reach EOF; byte cap still bounds cache.
    // This is not a whole-process RAM cap, nor a download-completion guarantee.
    private fun slowNetOptions(): MpvOptions = MpvOptions(
        demuxerMaxBytes = 100L * 1024 * 1024,
        extra = mapOf(
            "demuxer-max-back-bytes" to (8L * 1024 * 1024).toString(),
            "demuxer-readahead-secs" to "86400",
            "cache" to "yes",
            "cache-secs" to "86400",
            "cache-pause-wait" to "2",
            "network-timeout" to "30",
            "stream-lavf-o" to "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5"
        )
    )

    /** Cache khali hui to MPV khud ruk jata hai (buffering) — user ko dikhane ke liye. */
    fun buffering(): Boolean = try {
        val mpv = view?.mpv ?: return false
        mpv[MpvProperties.PausedForCache].getOrNull() == true
    } catch (t: Throwable) { false }

    /** Buffer kitna bhar chuka (0-100%). */
    fun cachePct(): Int = try {
        val mpv = view?.mpv ?: return 0
        (mpv[MpvProperties.CacheBufferingState].getOrNull() ?: 0L).toInt().coerceIn(0, 100)
    } catch (t: Throwable) { 0 }

    /* ── ACT2: gol corners ── */

    /** Page ka --wp-bg1 rang (hole JS JSON ke 'bg' se aata hai). Corner patches isi rang ke bante hain. */
    fun setMaskColor(col: String) {
        val c = parseColorSafe(col) ?: return
        maskColor = c
        main.post {
            try { masks?.forEach { m -> m.baseColor = c; m.invalidate() } } catch (t: Throwable) {}
        }
    }

    /** 4 corner-patch views banao (main thread): MPV ke UPAR magar WebView ke NEECHE.
        ACT3: HAR Android par bante hain — outline clip (12+) ke sath sath double-suraksha. */
    private fun ensureMasks(v: View) {
        if (masks != null || v.parent !== root) return
        try {
            val arr = arrayOf(CornerMaskView(act, 0), CornerMaskView(act, 1), CornerMaskView(act, 2), CornerMaskView(act, 3))
            for (m in arr) {
                m.visibility = View.GONE
                val idx = if (root.indexOfChild(v) >= 0) root.indexOfChild(v) + 1 else root.childCount
                root.addView(m, idx, FrameLayout.LayoutParams(1, 1))
                m.baseColor = maskColor
            }
            masks = arr
        } catch (t: Throwable) { Log.e(TAG, "corner masks fail", t) }
    }

    /** Patches ko MPV rect ke charon corners par set karo (main thread). Fullscreen/hidden -> GONE. */
    private fun syncMasks() {
        val arr = masks ?: return
        try {
            val v = view
            val r = if (fullscreen) 0f else cornerDp * act.resources.displayMetrics.density
            val lp = if (v != null) v.layoutParams as? FrameLayout.LayoutParams else null
            val ok = !fullscreen && r > 0f && v != null && v.visibility == View.VISIBLE && lp != null &&
                lp.width > 0 && lp.height > 0
            val bleed = (1.5f * act.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            for (m in arr) {
                if (!ok) { if (m.visibility != View.GONE) m.visibility = View.GONE; continue }
                val rInt = r.toInt().coerceAtLeast(1)
                val size = rInt + bleed   // patch gol se 1.5dp BAHAR tak jata hai (koi patli line na bache)
                val mx = when (m.corner) {
                    1, 3 -> lp.leftMargin + lp.width - rInt
                    else -> lp.leftMargin - bleed
                }
                val my = when (m.corner) {
                    2, 3 -> lp.topMargin + lp.height - rInt
                    else -> lp.topMargin - bleed
                }
                m.radius = r
                if (m.visibility != View.VISIBLE) m.visibility = View.VISIBLE
                val mlp = m.layoutParams as FrameLayout.LayoutParams
                if (mlp.width != size || mlp.height != size || mlp.leftMargin != mx || mlp.topMargin != my) {
                    mlp.width = size; mlp.height = size; mlp.leftMargin = mx; mlp.topMargin = my
                    m.layoutParams = mlp
                }
                m.invalidate()
            }
        } catch (t: Throwable) {}
    }

    /** '#rrggbb' ya 'rgb(r,g,b)' -> Int color, warna null (kabhi crash nahi). */
    private fun parseColorSafe(s: String): Int? = try {
        when {
            s.startsWith("#") && s.length >= 7 -> Color.parseColor(s.substring(0, 7))
            s.startsWith("rgb") -> {
                val p = s.replace(Regex("[^0-9,]"), "").split(",")
                    .map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }
                if (p.size >= 3) Color.rgb(p[0].coerceIn(0, 255), p[1].coerceIn(0, 255), p[2].coerceIn(0, 255)) else null
            }
            else -> null
        }
    } catch (_: Throwable) { null }

    /** Corner patch: radius x radius chhota view; page-bg rang bharta hai, andar ki chauthai (gol) chhod deta hai. */
    private class CornerMaskView(act: Activity, val corner: Int) : View(act) {
        var radius = 0f
        var baseColor = 0xFF0F0C29L.toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun onDraw(c: Canvas) {
            val r = radius
            if (r <= 0f || width <= 0 || height <= 0) return
            val w = width.toFloat(); val h = height.toFloat()
            // 135deg gradient (page ke bars jaisa): TL=bg1, BR=#302b63, TR/BL=beech ka mix
            val t = when (corner) { 0 -> 0f; 3 -> 1f; else -> 0.5f }
            paint.color = lerpColor(baseColor, 0xFF302B63L.toInt(), t)
            path.reset()
            path.addRect(0f, 0f, w, h, Path.Direction.CCW)
            // Gol ka markaz MPV rect ke corner se r andar; patch bahar ki taraf 1.5dp phaila hai
            val cx = if (corner == 0 || corner == 2) w else 0f
            val cy = if (corner == 0 || corner == 1) h else 0f
            path.addCircle(cx, cy, r, Path.Direction.CW)
            path.fillType = Path.FillType.EVEN_ODD
            c.drawPath(path, paint)
        }

        private fun lerpColor(a: Int, b: Int, t: Float): Int {
            fun ch(v: Int, s: Int) = (v shr s) and 0xFF
            fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
            return (255 shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    companion object { private const val TAG = "WPMpvVideo" }
}

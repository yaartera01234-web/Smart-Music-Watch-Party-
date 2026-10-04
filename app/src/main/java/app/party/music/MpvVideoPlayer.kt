package app.party.music

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import io.github.yuroyami.libmpvkt.MpvProperties
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
    @Volatile private var coreReady = false
    @Volatile private var ensuring = false
    @Volatile private var localError: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

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
    fun play(url: String, pos: Double, startMuted: Boolean) {
        main.post {
            try {
                val v = view ?: return@post
                if (v.mpv == null) { localError = "core tayyar nahi"; return@post }
                dispPos = -1.0
                v.muted = startMuted
                v.paused = false
                v.playFile(url)
                main.postDelayed({ try { v.timePos = pos } catch (t: Throwable) {} }, 700L)
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
    fun hasFrame(): Boolean = try { view != null && view?.mpv != null && view?.timePos != null } catch (t: Throwable) { false }

    fun show() { main.post { try { view?.visibility = View.VISIBLE } catch (t: Throwable) {} } }
    fun hide() { main.post { try { view?.visibility = View.INVISIBLE } catch (t: Throwable) {} } }

    /** Player area ke exactly upar: CSS px -> device px (density se multiply). */
    fun setRect(x: Float, y: Float, w: Float, h: Float) {
        main.post {
            try {
                val v = view ?: return@post
                val d = act.resources.displayMetrics.density
                val lp = v.layoutParams as FrameLayout.LayoutParams
                val nw = (w * d).toInt(); val nh = (h * d).toInt()
                val nx = (x * d).toInt(); val ny = (y * d).toInt()
                if (lp.width == nw && lp.height == nh && lp.leftMargin == nx && lp.topMargin == ny) return@post
                lp.width = nw; lp.height = nh; lp.leftMargin = nx; lp.topMargin = ny
                v.layoutParams = lp
            } catch (t: Throwable) {}
        }
    }

    fun stop() { main.post { try { view?.paused = true } catch (t: Throwable) {} } }

    fun destroy() {
        main.post {
            try { view?.destroy() } catch (t: Throwable) {}
            try { val v = view; if (v != null && v.parent === root) root.removeView(v) } catch (t: Throwable) {}
            view = null; coreReady = false; ensuring = false
        }
    }

    /* SLOW NET: bara cache + network timeouts + reconnect.
       - demuxer cache: 96MB aage + 24MB peeche (360p par ~20+ minute buffer)
       - readahead 300s: jitna net deta hai, utna aage bharta rahe (ruke nahi)
       - network-timeout 30s + lavf reconnect: stream toote to khud jude
       - cache-pause-wait 2s: cache khali ho to 2s ruk kar bhare, phir chale (kam stop-start) */
    private fun slowNetOptions(): MpvOptions = MpvOptions(
        demuxerMaxBytes = 96L * 1024 * 1024,
        extra = mapOf(
            "demuxer-max-back-bytes" to (24L * 1024 * 1024).toString(),
            "demuxer-readahead-secs" to "300",
            "cache" to "yes",
            "cache-secs" to "300",
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

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    companion object { private const val TAG = "WPMpvVideo" }
}

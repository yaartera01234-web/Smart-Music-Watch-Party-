package app.party.music

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v26: Native Player (Media3) + naye controls.
 * v29: dual audio (audio track switch).
 * v31: crash guard.
 * v32: HAR STEP apna try/catch — koi ek cheez fail ho to native phir bhi khule.
 *      + MediaController (background/notification) fail ho to LOCAL ExoPlayer fallback.
 *      + error poori screen pe (tap = hatao) aur crash.txt me (agle launch pe banner).
 * v36: DO-player bug fix (grace 2.6s + local release) + status line + saaf error screen.
 * v33: immersive() ab setContentView ke BAAD (wahi NPE tha jo app girata tha) + telemetry.
 *
 *  - Left side pe ungli se UPAR/NEEche -> BRIGHTNESS
 *  - Right side pe ungli se UPAR/NEEche -> VOLUME
 *  - Upar-daayen: Fit / Fill / Zoom  |  Upar-baayen: Audio 1/2
 */
@UnstableApi
class PlayerActivity : Activity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var localPlayer: ExoPlayer? = null
    private var localMode = false
    private var started = false
    private var curUrl = ""
    private var curTitle = "Video"
    private var altUrl: String? = null
    private var altTried = false
    private var web: android.webkit.WebView? = null
    private var webMode = false

    private var root: FrameLayout? = null
    private var status2: TextView? = null
    private var note: TextView? = null
    private var errText: TextView? = null
    private var pv: PlayerView? = null
    private var sv: SurfaceView? = null
    private var modeBtn: TextView? = null
    private var audioBtn: TextView? = null
    private var hud: LinearLayout? = null
    private var hudText: TextView? = null
    private var hudBar: ProgressBar? = null

    private var am: AudioManager? = null
    private var prefs: SharedPreferences? = null
    private val hideRunnable = Runnable { hud?.visibility = View.GONE }
    private var maxVol = 15
    private val stepErrs = StringBuilder()

    private val modes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val modeNames = arrayOf("Fit", "Fill", "Zoom")
    private val modeIcons = arrayOf("\u2b1c", "\u26f6", "\u26f6")
    private var modeIdx = 0

    private var gActive = false
    private var gZone = 0          // 1 = brightness (left), 2 = volume (right)
    private var gStartX = 0f
    private var gStartY = 0f
    private var gStartBright = 0.5f
    private var gStartVol = 0

    /* ================= lifecycle ================= */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /* v31: crash guard. v32: har step alag — ek fail ho to baqi chalta rahe. */
        try {
            buildUi()
        } catch (t: Throwable) {
            fail("create", t)
            showErrPanel()
        }
    }

    private fun buildUi() {
        step("audio") {
            am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            maxVol = am!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        }
        step("prefs") {
            prefs = getSharedPreferences("yp_player", Context.MODE_PRIVATE)
            val m = prefs!!.getInt("mode", 0)
            modeIdx = if (m < 0 || m > 2) 0 else m
        }

        val r = FrameLayout(this)
        r.setBackgroundColor(0xFF000000.toInt())
        root = r

        step("playerView") {
            val p = PlayerView(this)
            p.useController = true
            p.setShowNextButton(false)
            p.setShowPreviousButton(false)
            p.resizeMode = modes[modeIdx]
            pv = p
            r.addView(p, FrameLayout.LayoutParams(-1, -1))
        }
        if (pv == null) {
            /* PlayerView ban na sake to simple SurfaceView se video chalao */
            step("surface") {
                val s = SurfaceView(this)
                sv = s
                r.addView(s, FrameLayout.LayoutParams(-1, -1))
            }
        }

        step("note") {
            val t = TextView(this).apply {
                setTextColor(0xFFFF5FA2.toInt())
                textSize = 13f
                visibility = View.GONE
            }
            note = t
            r.addView(t, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(40); topMargin = dp(60) })
        }

        step("modeBtn") {
            val b = TextView(this).apply {
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(0x99000000.toInt())
                text = modeIcons[modeIdx] + "  " + modeNames[modeIdx]
                setOnClickListener { cycleMode() }
            }
            modeBtn = b
            r.addView(b, FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(16)
                rightMargin = dp(16)
            })
        }

        step("audioBtn") {
            val b = TextView(this).apply {
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(0x99000000.toInt())
                text = "\uD83D\uDD0A  Audio"
                setOnClickListener { cycleAudio() }
            }
            audioBtn = b
            r.addView(b, FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(16)
                leftMargin = dp(16)
            })
        }

        step("hud") {
            hudText = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(0, 0, dp(12), 0)
            }
            hudBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 50
            }
            val l = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xCC000000.toInt())
                setPadding(dp(16), dp(12), dp(16), dp(12))
                visibility = View.GONE
                addView(hudText, LinearLayout.LayoutParams(-2, -2))
                addView(hudBar, LinearLayout.LayoutParams(dp(220), dp(10)))
            }
            hud = l
            r.addView(l, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER })
        }

        step("status2") {
            val t = TextView(this).apply {
                textSize = 10.5f
                setTextColor(0xFFBFF3FF.toInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(0x99000000.toInt())
                text = "v36 \u2022 starting\u2026"
            }
            status2 = t
            r.addView(t, FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dp(12)
                bottomMargin = dp(12)
            })
        }

        step("show") { setContentView(r) }
        /* v33 FIX: fullscreen sirf content lagne ke BAAD (pehle decor view null hota hai -> NPE) */
        step("immersive") { immersive() }

        altUrl = try { intent.getStringExtra("alt") } catch (t: Throwable) { null }
        val url = try { intent.getStringExtra("url") } catch (t: Throwable) { null }
        val title = try { intent.getStringExtra("title") } catch (t: Throwable) { null } ?: "Video"
        val pos = try { intent.getLongExtra("pos", 0L) } catch (t: Throwable) { 0L }
        if (url.isNullOrBlank()) { finish(); return }
        curUrl = url
        curTitle = title

        /* 1) MediaController (background play + notification). Fail ho to 2) local player. */
        val ok = step("session") { startController(url, title, pos) }
        if (ok) {
            /* v36 FIX: controller ko connect hone ka waqt do (2.6s).
               Pehle hum foran local player bhi chala dete the -> DO player ek sath
               (aawaz double / video black / foran error -> screen band). */
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ if (!started) startLocalIfNeeded(url, title, pos) }, 2600)
        } else {
            startLocalIfNeeded(url, title, pos)
        }

        if (stepErrs.isNotEmpty()) showErrPanel()
    }

    /* ================= player start ================= */

    private fun attach(p: Player) {
        try {
            val v = pv
            if (v != null) v.player = p
            else sv?.let { p.setVideoSurfaceView(it) }
        } catch (t: Throwable) { fail("attach", t) }
    }

    private fun mediaItem(url: String, title: String): MediaItem {
        val lower = url.lowercase()
        val b = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        if (lower.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        else if (lower.contains(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD)
        return b.build()
    }

    private fun startController(url: String, title: String, pos: Long) {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            try {
                val c = f.get()
                if (localMode) {           /* v36: local fallback chal raha tha -> band karo, warna double aawaz */
                    try { localPlayer?.release() } catch (t: Throwable) {}
                    localPlayer = null
                    localMode = false
                }
                controller = c
                started = true
                setStatus("v36 \u2022 controller OK")
                attach(c)
                c.setMediaItem(mediaItem(url, title), pos)
                c.prepare()
                c.play()
                logLine("session OK \u2014 playing")
                c.addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) { try { refreshAudioBtn() } catch (t: Throwable) {} }
                    override fun onPlaybackStateChanged(state: Int) {
                        logLine("state " + state)
                        setStatus("v36 \u2022 controller \u2022 state " + state)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        showPlayErr("code " + error.errorCode + " \u2b1c " + error.errorCodeName)
                    }
                })
            } catch (t: Throwable) {
                fail("controller", t)
                runOnUiThread { startLocalIfNeeded(url, title, pos) }
            }
        }, MoreExecutors.directExecutor())
    }

    /** Background service na bane to seedha yahin player — video phir bhi chale. */
    private fun startLocalIfNeeded(url: String, title: String, pos: Long) {
        if (started) return
        try {
            startLocal(url, title, pos)
        } catch (t: Throwable) {
            fail("localPlayer", t)
            showErrPanel()
            showNote("\u26a0\ufe0f Native player start nahi ho saka")
        }
    }

    private fun startLocal(url: String, title: String, pos: Long) {
        val ua = try { android.webkit.WebSettings.getDefaultUserAgent(this) } catch (t: Throwable) { "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile Safari/537.36" }
        val base = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)
        val withCookies = ResolvingDataSource.Factory(base) { spec ->
            val u = spec.uri.toString()
            val hdrs = HashMap<String, String>()
            try {
                val ck = CookieManager.getInstance().getCookie(u)
                if (!ck.isNullOrBlank()) hdrs["Cookie"] = ck
            } catch (t: Throwable) {}
            hdrs["Referer"] = "https://yaartera01234-web.github.io/"
            spec.withRequestHeaders(hdrs)
        }
        val ds = DefaultDataSource.Factory(this, withCookies)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            p.setHandleAudioBecomingNoisy(true)
        } catch (t: Throwable) {}
        localPlayer = p
        localMode = true
        started = true
        attach(p)
        p.setMediaItem(mediaItem(url, title), pos)
        p.prepare()
        p.play()
        logLine("local OK \u2014 playing")
        setStatus("v36 \u2022 local player OK")
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { try { refreshAudioBtn() } catch (t: Throwable) {} }
            override fun onPlaybackStateChanged(state: Int) {
                logLine("local state " + state)
                setStatus("v36 \u2022 local \u2022 state " + state)
            }
            override fun onPlayerError(error: PlaybackException) {
                showPlayErr("code " + error.errorCode + " \u2b1c " + error.errorCodeName)
            }
        })
    }

    private fun cur(): Player? = controller ?: localPlayer

    /* ---------------- v29: dual audio (track switch) ---------------- */

    private fun audioList(p: Player): MutableList<Pair<androidx.media3.common.TrackGroup, Int>> {
        val out = ArrayList<Pair<androidx.media3.common.TrackGroup, Int>>()
        try {
            for (g in p.currentTracks.groups) {
                if (g.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until g.length) out.add(g.mediaTrackGroup to i)
            }
        } catch (t: Throwable) {}
        return out
    }

    private fun audioCur(p: Player): Int {
        try {
            var k = 0
            for (g in p.currentTracks.groups) {
                if (g.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until g.length) {
                    if (g.isTrackSelected(i)) return k
                    k++
                }
            }
        } catch (t: Throwable) {}
        return -1
    }

    private fun refreshAudioBtn() {
        val p = cur() ?: return
        val list = audioList(p); val idx = audioCur(p)
        val b = audioBtn ?: return
        b.text = if (list.size > 1) "\uD83D\uDD0A  " + (idx + 1).coerceAtLeast(1) + "/" + list.size else "\uD83D\uDD0A  Audio"
    }

    private fun cycleAudio() {
        if (webMode) { showNote("\u26a0\ufe0f Is mode (page jaisa player) me dual audio nahi milta"); return }
        val p = cur() ?: return
        val list = audioList(p)
        if (list.size <= 1) { showNote("\u26a0\ufe0f Is video me sirf ek hi audio hai"); return }
        val curI = audioCur(p)
        val nxt = ((curI + 1) % list.size + list.size) % list.size
        val pair = list[nxt]
        try {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(pair.first, pair.second))
                .build()
        } catch (t: Throwable) {}
        refreshAudioBtn()
        showHud("\uD83D\uDD0A", (nxt + 1) * 100 / list.size, "Audio " + (nxt + 1) + "/" + list.size)
    }

    /* ---------------- crop mode ---------------- */

    private fun cycleMode() {
        modeIdx = (modeIdx + 1) % modes.size
        try { pv?.resizeMode = modes[modeIdx] } catch (t: Throwable) {}
        if (webMode) webAspect()
        modeBtn?.text = modeIcons[modeIdx] + "  " + modeNames[modeIdx]
        try { prefs?.edit()?.putInt("mode", modeIdx)?.apply() } catch (e: Throwable) {}
        showHud("\u26f6", (modeIdx + 1) * 33, modeNames[modeIdx])
    }

    /* ---------------- brightness + volume gestures ---------------- */

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        try {
            val h = resources.displayMetrics.heightPixels.toFloat()
            val w = resources.displayMetrics.widthPixels.toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gStartX = ev.x; gStartY = ev.y; gActive = false; gZone = 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.y - gStartY
                    val dx = ev.x - gStartX
                    if (!gActive) {
                        if (abs(dy) > dp(22).toFloat() && abs(dy) > abs(dx) * 1.5f) {
                            gActive = true
                            gZone = if (gStartX < w / 2f) 1 else 2
                            gStartBright = currentBrightness()
                            gStartVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                        }
                    }
                    if (gActive) {
                        val pct = -dy / (h * 0.55f)          // upar = zyada
                        if (gZone == 1) {
                            val b = (gStartBright + pct).coerceIn(0.02f, 1f)
                            applyBrightness(b)
                            val percent = (b * 100f).roundToInt()
                            showHud("\u2600\ufe0f", percent, "$percent%")
                        } else {
                            val nv = (gStartVol + pct * maxVol).roundToInt().coerceIn(0, maxVol)
                            try { am?.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0) } catch (e: Throwable) {}
                            val percent = ((nv * 100f) / maxVol).roundToInt()
                            showHud("\uD83D\uDD0A", percent, "$nv/$maxVol")
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gActive) {
                        gActive = false
                        hideHudLater()
                        return true
                    }
                }
            }
        } catch (t: Throwable) {}
        return super.dispatchTouchEvent(ev)
    }

    private fun currentBrightness(): Float {
        val a = window.attributes
        if (a.screenBrightness >= 0f) return a.screenBrightness
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Throwable) { 0.5f }
    }

    private fun applyBrightness(b: Float) {
        try {
            val lp = window.attributes
            lp.screenBrightness = b
            window.attributes = lp
        } catch (e: Throwable) {}
    }

    /* ---------------- HUD ---------------- */

    private fun showHud(icon: String, percent: Int, label: String) {
        runOnUiThread {
            hudText?.text = "$icon  $label"
            hudBar?.progress = percent.coerceIn(0, 100)
            hud?.visibility = View.VISIBLE
            hud?.removeCallbacks(hideRunnable)
        }
    }

    private fun hideHudLater() {
        hud?.postDelayed(hideRunnable, 900)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun showNote(msg: String) {
        runOnUiThread {
            note?.text = msg
            note?.visibility = View.VISIBLE
            try { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } catch (t: Throwable) {}
        }
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            try { window.setDecorFitsSystemWindows(false) } catch (t: Throwable) { fail("decorFits", t) }
            /* v33: getWindowInsetsController() decor view na hone pe khud NPE deta hai -> poori tarah guard */
            val ic = try { window.insetsController } catch (t: Throwable) { null }
            if (ic != null) {
                try {
                    ic.hide(android.view.WindowInsets.Type.systemBars())
                    ic.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    return
                } catch (t: Throwable) { fail("hideBars", t) }
            }
            try {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
            } catch (t: Throwable) { fail("legacyBars", t) }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) try { immersive() } catch (t: Throwable) {}
    }

    /* ---------------- error reporting (v32/v33) ---------------- */

    private fun setStatus(msg: String) {
        runOnUiThread {
            try {
                status2?.text = msg
                status2?.visibility = View.VISIBLE
            } catch (t: Throwable) {}
        }
    }

    /** v36: playback fail hone pe screen pe saaf wajah + tap = page player (khud se band nahi hoti). */
    private fun showPlayErr(why: String) {
        logLine("playFail " + why + " | url=" + curUrl.take(150))
        /* v36: pehla link nahi chala? doosra link khud try karo (self-test / mirror links) */
        val a = altUrl
        if (!altTried && !a.isNullOrBlank()) {
            altTried = true
            val p = cur()
            if (p != null) {
                try {
                    logLine("trying ALT url")
                    setStatus("v36 \u2022 alt link try ho raha hai\u2026")
                    p.setMediaItem(mediaItem(a, curTitle))
                    p.prepare()
                    p.play()
                    return
                } catch (t: Throwable) {
                    fail("altTry", t)
                }
            }
        }
        /* v36: Media3 us link ko handle nahi kar pa raha? to wahi engine chalao jo
           premium player me chalta hai (WebView) — jo page pe chalta hai wahi yahan bhi chalega */
        if (!webMode) {
            startWebFallback(curUrl)
            return
        }
        runOnUiThread {
            try {
                setStatus("v36 \u2022 FAIL: " + why)
                val t = TextView(this).apply {
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12.5f
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    setBackgroundColor(0xF01A0B18.toInt())
                    text = "\u26a0\ufe0f Native player ye link nahi chala\n" + why + "\n\n(Tap karo = page wala player)"
                    isClickable = true
                    setOnClickListener { finish() }
                }
                root?.addView(t, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.CENTER })
                t.postDelayed({ try { finish() } catch (e: Throwable) {} }, 10000)
            } catch (e: Throwable) {}
        }
    }

    /* ================= v36: WEBVIEW PLAYER (guaranteed fallback) ================= */

    private fun startWebFallback(url: String) {
        try {
            webMode = true
            started = true
            /* Media3 band karo taake double aawaz na ho */
            try { controller?.stop() } catch (t: Throwable) {}
            try { localPlayer?.pause() } catch (t: Throwable) {}
            logLine("webview fallback: " + url.take(140))

            val w = android.webkit.WebView(this)
            w.setBackgroundColor(0xFF000000.toInt())
            try {
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.mediaPlaybackRequiresUserGesture = false
                w.settings.useWideViewPort = true
                w.settings.loadWithOverviewMode = true
                val ua = android.webkit.WebSettings.getDefaultUserAgent(this)
                if (!ua.isNullOrBlank()) w.settings.userAgentString = ua
            } catch (t: Throwable) { fail("webSettings", t) }
            w.webChromeClient = android.webkit.WebChromeClient()
            w.webViewClient = android.webkit.WebViewClient()
            web = w
            root?.addView(w, FrameLayout.LayoutParams(-1, -1))
            w.loadDataWithBaseURL("https://yaartera01234-web.github.io/", webHtml(url), "text/html", "utf-8", null)
            setStatus("v36 \u2022 webview player (page jaisa)")
            /* aspect button ko shuru me fit kar do */
            w.postDelayed({ webAspect() }, 800)
        } catch (t: Throwable) {
            fail("webView", t)
        }
    }

    private fun webHtml(u: String): String {
        val src = org.json.JSONObject.quote(u)
        return """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}
video{width:100%;height:100%;object-fit:contain;background:#000;outline:none}
#b{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);width:76px;height:76px;border-radius:50%;
background:linear-gradient(135deg,#ff5ebc,#8b72ff);display:none;place-items:center;font-size:32px;color:#fff;z-index:9;
box-shadow:0 10px 30px rgba(0,0,0,.5)}</style></head>
<body><video id="v" playsinline controls autoplay preload="metadata"></video><div id="b">&#9654;</div>
<script>
var v=document.getElementById('v'), b=document.getElementById('b');
v.src=""" + src + """;
window.pvAspect=function(m){ v.style.objectFit=(m==='zoom'?'cover':(m==='fill'?'fill':'contain')); };
window.pvBright=function(x){ v.style.filter='brightness('+x+')'; };
window.pvPlay=function(){ try{ v.play(); }catch(e){} };
try{ v.volume=1; v.play(); }catch(e){}
setTimeout(function(){ if(v.paused){ b.style.display='grid'; } },1500);
b.onclick=function(){ try{ v.play(); }catch(e){} b.style.display='none'; };
v.addEventListener('playing',function(){ b.style.display='none'; });
v.addEventListener('error',function(){ try{ document.title='ERR'; }catch(e){} });
</script></body></html>"""
    }

    private fun webAspect() {
        val m = when (modeIdx) { 0 -> "fit"; 1 -> "fill"; else -> "zoom" }
        try { web?.evaluateJavascript("window.pvAspect && window.pvAspect('" + m + "')", null) } catch (t: Throwable) {}
    }

    private fun logLine(msg: String) {
        try {
            getFileStreamPath("crash.txt").appendText("\n[" + Date() + "] v33 " + msg + "\n")
        } catch (e: Throwable) {}
    }


    private fun step(name: String, block: () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (t: Throwable) {
            fail(name, t)
            false
        }
    }

    private fun fail(name: String, t: Throwable) {
        try {
            stepErrs.append("\n\u2022 ").append(name).append(": ").append(t.message ?: t.javaClass.name)
        } catch (e: Throwable) {}
        try {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val f = getFileStreamPath("crash.txt")
            f.appendText("\n[" + Date() + "] v32 " + name + "\n" + sw.toString().take(1200) + "\n")
        } catch (e: Throwable) {}
    }

    private fun showErrPanel() {
        runOnUiThread {
            try {
                val r = root
                if (r == null || errText != null) return@runOnUiThread
                val t = TextView(this).apply {
                    setTextColor(0xFFFFD9E6.toInt())
                    textSize = 11.5f
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    setBackgroundColor(0xF0100A1E.toInt())
                    text = "NATIVE PLAYER \u2014 v36 error\n" + stepErrs.toString().take(900) + "\n\n(koi bhi jagah tap = yeh hata do)"
                    setOnClickListener { visibility = View.GONE }
                }
                errText = t
                r.addView(t, FrameLayout.LayoutParams(-1, -2).apply {
                    gravity = Gravity.TOP
                    topMargin = dp(64)
                })
                if (started) t.postDelayed({ try { t.visibility = View.GONE } catch (e: Throwable) {} }, 12000)
            } catch (e: Throwable) {}
        }
    }

    /* ---------------- lifecycle end ---------------- */

    override fun onStop() {
        super.onStop()
        val c = controller
        if (c != null) {
            try { if (isFinishing) c.pause() } catch (t: Throwable) {}
            controllerFuture?.let { runCatching { MediaController.releaseFuture(it) } }
            controllerFuture = null
            controller = null
        }
        if (localMode) {
            /* Background me chalne ke liye service wala player behtar hai; local chalta rahe jab tak activity zinda hai. */
            if (isFinishing) runCatching { localPlayer?.pause() }
        }
    }

    override fun onDestroy() {
        try {
            if (localMode) {
                runCatching { localPlayer?.release() }
            }
        } catch (t: Throwable) {}
        localPlayer = null
        try {
            web?.stopLoading()
            web?.destroy()
        } catch (t: Throwable) {}
        web = null
        super.onDestroy()
    }
}

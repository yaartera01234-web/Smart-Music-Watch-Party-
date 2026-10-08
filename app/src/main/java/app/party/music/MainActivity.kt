package app.party.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import android.webkit.PermissionRequest
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/**
 * Hosts the watch-party web app in a WebView.
 *
 * Background strategy: Picture-in-Picture. When the user leaves (home button), the activity
 * continues as the system's little PiP window, so the WebView never becomes "hidden" and
 * Chromium keeps the party sounding — the same mechanism YouTube's own app uses. The foreground
 * service + wake lock additionally keep the process and CPU alive.
 *
 * A global crash handler writes any fatal error to crash.txt; on the next launch the trace is
 * shown briefly (8s) so it can be photographed, then cleared.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: GifWebView
    private lateinit var splash: LinearLayout
    private lateinit var status: TextView
    private lateinit var pipCover: TextView
    private lateinit var banner: TextView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<android.net.Uri>>? = null
    private var pendingWebPerm: PermissionRequest? = null
    private var pendingIncomingCallAction: Pair<String, String>? = null
    private var pageLoaded = false
    private var resumed = false
    private var callAudioActive = false
    private var callOriginalMode = AudioManager.MODE_NORMAL
    private var callOriginalSpeaker = false
    private var callOriginalDevice: AudioDeviceInfo? = null

    /* v30: native player ka apna (Android) button + auto-detect — page ke JS pe bharosa nahi */
    private var nBtn: TextView? = null
    private var lastAutoUrl = ""

    /* ══ v111-FIX (build 3): background / lock-screen handoff ══
       App saamne  -> page ka premium player boss (jaisa tha waisa)
       Lock/peeche -> wahi gaana native MPV engine par (audio only, surface ki zarurat nahi)

       Build 2 ka bug: snapshot onStop ke 1.5s BAAD maanga jata tha — utne me WebView freeze
       ho chuka hota hai, evaluateJavascript ka jawab nahi aata, is liye handoff hi nahi hota tha.
       Ab:
         • app saamne hone par state har 3 second cache hoti hai (jab JS zinda hai)
         • screen band hote hi (ACTION_SCREEN_OFF) FORAN usi cached state se handoff
         • onStop ek fallback hai (Home button waghera) */
    private val handoffHandler = Handler(Looper.getMainLooper())
    private val startHandoff = Runnable { handoffNow("onStop") }

    @Volatile private var lastSnap: WebBridge.Snapshot? = null
    @Volatile private var lastSnapAt: Long = 0L
    @Volatile private var lastQueueJson: String? = null
    @Volatile private var handoffAttempted = false
    @Volatile private var prewarmId: String = ""
    @Volatile private var blockedBannerId: String = ""   // build 14: AV1/MKV banner ek hi dafa
    @Volatile private var prewarmAt: Long = 0L
    private var resumeTries = 0

    /* ══ YOUTUBE = MPV (VIDEO + AUDIO) ══ */
    private lateinit var mpvVideo: MpvVideoPlayer
    private val ytResolver = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var ytVideoId: String = ""        // kis YouTube item ka MPV chal raha hai
    @Volatile private var ytVideoState: Int = 0         // 0=off 1=starting 2=active 3=fail
    @Volatile private var ytVideoBanner: Boolean = false
    @Volatile private var ytVideoTry: Int = 0           // isi item par kitni koshish ho chuki
    @Volatile private var ytFailAt: Long = 0L           // aakhri fail kab hua
    @Volatile private var ytFastPoll: Boolean = false   // YouTube chal raha -> page tez check karo
    @Volatile private var ytLastPos: Double = 0.0
    @Volatile private var ytLastPlaying: Boolean = false
    @Volatile private var ytFsOn: Boolean = false       // apna fullscreen (Android ka kala nahi)
    @Volatile private var ytHandbackDone: Boolean = false
    @Volatile private var lastYtBuf: Boolean = false      // slow net: buffering indicator ki aakhri halat
    @Volatile private var lastYtBufPct: Int = 0
    @Volatile private var ytStallPos: Double = -1.0        // video ruki to pakarne ke liye
    @Volatile private var ytStallAt: Long = 0L
    @Volatile private var ytSlowBanner: Boolean = false
    @Volatile private var ytQuality: Int = 144            // user ki chuni hui quality (144/240/360)
    @Volatile private var ytLastUrl: String = ""          // fallback ke liye aakhri stream
    @Volatile private var ytLastAudio: String? = null
    private var directMode = false
    private var directKind = "mp4"
    private var mediaTitle = "Watch Party"
    private var availableQualities = listOf(144,240,360,480,720,1080)
    private var fullscreenControls: MpvFullscreenControls? = null
    private var webAccessibilityBeforeFs = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var mpvOnlyMuted = false
    private var mpvOnlyRevision = 0
    private var mpvOnlySeekUntil = 0L
    private var mpvOnlySeekTarget = 0.0
    private var mpvOnlyDialogId = ""
    private var ytResolveGeneration = 0
    private var ytDiagnostic = ""
    private var fsTick: Int = 0

    private val snapPoller = object : Runnable {
        override fun run() {
            pollSnapshot()
            // YouTube chal raha / MPV tayyar ho raha -> tez (warna iframe pehle bajta rehta hai)
            handoffHandler.postDelayed(this, if (ytFastPoll || ytVideoState != 0) 600L else 3000L)
        }
    }

    /** Power (lock) button — ye broadcast WebView freeze hone se PEHLE aata hai. */
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                try { handoffNow("screen-off") } catch (t: Throwable) {}
                try {   /* lock ke waqt bhi audio mode saaf kar do (call background me khatam hui ho to) */
                    if (!CallForegroundService.running) {
                        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        if (am.mode != android.media.AudioManager.MODE_NORMAL) resetCallAudioRoute()
                    }
                } catch (t: Throwable) {}
            }
        }
    }

    /* v27: app background/band hone pe DM notifications ke liye chhupa WebView service */
    private val bgHandler = Handler(Looper.getMainLooper())
    private val bgStarter = Runnable {
        try { BgNotifyService.start(this) } catch (t: Throwable) { Log.e("MusicParty", "bg start fail", t) }
    }

    private val url = "https://yaartera01234-web.github.io/watch-party/party-final1.html"

    private val AD_HOSTS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "/pagead",
        "imasdk.googleapis.com",
        "googleads.g.",
        "googletagservices.com",
        "adservice.google.com"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingIncomingCallAction = parseIncomingCallAction(intent)

        // Self-reporting crashes: write trace to a file, show it briefly next launch.
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                getFileStreamPath("crash.txt").writeText("${Date()}\n${sw}")
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.TRANSPARENT)   // MPV surface is ke PEECHE hota hai

        web = GifWebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)    // page ke "hole" se MPV video dikhti hai
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        // Branded, professional loading screen: icon + spinner + pulsing label.
        splash = LinearLayout(this)
        splash.orientation = LinearLayout.VERTICAL
        splash.gravity = Gravity.CENTER
        splash.setBackgroundColor(Color.parseColor("#0d0716"))   // root transparent hai -> apna rang
        val logo = ImageView(this)
        logo.setImageDrawable(getDrawable(R.drawable.app_icon))
        val lp = LinearLayout.LayoutParams(dp(96), dp(96))
        logo.layoutParams = lp
        splash.addView(logo)
        val spin = ProgressBar(this)
        val spp = LinearLayout.LayoutParams(dp(34), dp(34))
        spp.topMargin = dp(18)
        spin.layoutParams = spp
        splash.addView(spin)
        val loading = TextView(this)
        loading.text = "L O A D I N G"
        loading.setTextColor(Color.parseColor("#c86bd8"))
        loading.textSize = 13f
        loading.letterSpacing = 0.3f
        val ltp = LinearLayout.LayoutParams(-2, -2)
        ltp.topMargin = dp(14)
        loading.layoutParams = ltp
        splash.addView(loading)
        loading.alpha = 0.4f
        loading.animate().setDuration(900).alpha(1f).setInterpolator(
            android.view.animation.AccelerateDecelerateInterpolator()
        ).withEndAction {
            loading.animate().setDuration(900).alpha(0.4f).withEndAction { pulse(loading) }
        }.start()
        val slp = FrameLayout.LayoutParams(-1, -1)
        root.addView(splash, slp)

        // Errors / one-time crash banner only.
        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 14f
        val sp = FrameLayout.LayoutParams(-2, -2)
        sp.gravity = Gravity.CENTER
        root.addView(status, sp)
        status.visibility = View.GONE

        // Dark music bubble shown only inside PiP (the page's own UI reads like a video call).
        pipCover = TextView(this)
        pipCover.setBackgroundColor(Color.parseColor("#12081f"))
        pipCover.setTextColor(Color.parseColor("#ff5fa2"))
        pipCover.textSize = 22f
        pipCover.gravity = Gravity.CENTER
        pipCover.text = "♪ Party ON"
        pipCover.visibility = View.GONE
        root.addView(pipCover, FrameLayout.LayoutParams(-1, -1))

        // Branded replacement for the page's raw JS alerts (connection notices etc.).
        banner = TextView(this)
        banner.setTextColor(Color.WHITE)
        banner.textSize = 13f
        banner.setPadding(dp(18), dp(12), dp(18), dp(12))
        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(Color.parseColor("#e612081f"))
        bg.setStroke(dp(1), Color.parseColor("#ff5fa2"))
        bg.cornerRadius = dp(14).toFloat()
        banner.background = bg
        banner.visibility = View.GONE
        val bnp = FrameLayout.LayoutParams(-2, -2)
        bnp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        bnp.bottomMargin = dp(34)
        root.addView(banner, bnp)

        /* v38: native player BAND (user: "native ko dafa kro") — android ka ⛶ button
           aur auto-open poller hata diya. Ab sirf premium (page wala) player chalta hai. */


        setContentView(root, FrameLayout.LayoutParams(-1, -1))

        // Purane internal crash notes ko silently discard karo; raw stack trace kabhi screen par nahi.
        runCatching { getFileStreamPath("crash.txt").delete() }

        // Full cookie support so logged-in sessions and the iframe player behave normally.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Desktop Chrome identity — the exact combo that tested working (v10/v15): YouTube
            // played without the sign-in wall on it.
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        }
        web.webViewClient = object : WebViewClient() {
            // v20 me AD_HOSTS list bani thi magar use kabhi nahi hui — yahan asal blocking.
            // Sirf sub-resources block hote hain (main page kabhi nahi). YouTube ke andar wale
            // ads ka kuch hissa isse skip ho jata hai; video/ads dono ke apne googlevideo.com
            // domain ko chhua nahi jata (warna playback hi ruk jati).
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val r = request ?: return null
                if (r.isForMainFrame) return null
                MpvOnlyWeb.response(this@MainActivity, r.url.toString())?.let { return it }
                val host = r.url.host ?: ""
                val full = r.url.toString()
                val blocked = AD_HOSTS.any { h ->
                    if (h.startsWith("/")) full.contains(h) else host.contains(h)
                }
                if (!blocked) return null
                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                splash.visibility = View.GONE
                pageLoaded = true
                injectBridge(view)          // v111-FIX: native bridge (page ko chhua nahi jata)
                dispatchIncomingCallAction()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    splash.visibility = View.GONE
                    status.text = "Page load nahi hui — internet check karein."
                    status.visibility = View.VISIBLE
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                /* MPV video mode me Android ka fullscreen WebView KALA hota hai aur MPV ko dhak deta hai.
                   Is liye usay cancel karo aur apna fullscreen (page poori screen) laga do. */
                if (ytVideoState == 2) {
                    try { callback.onCustomViewHidden() } catch (t: Throwable) {}
                    try { web.evaluateJavascript("window.__wpMpvFsSet && window.__wpMpvFsSet(1)", null) } catch (t: Throwable) {}
                    enterYtFs()
                    return
                }
                customView?.let { (it.parent as? FrameLayout)?.removeView(it) }
                customView = view
                customViewCallback = callback
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                web.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let { (it.parent as? FrameLayout)?.removeView(it) }
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
                web.visibility = View.VISIBLE
            }

            // v25: page 🎤 (getUserMedia) -> WebView ka audio-capture request grant karo
            override fun onPermissionRequest(request: PermissionRequest) {
                val wantsAudio = request.resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                if (!wantsAudio) { request.deny(); return }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    pendingWebPerm = request
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                }
            }

            // The page's raw JS alert() (e.g. "tower se jur raha hai") becomes a branded banner.
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                showBanner(message ?: "")
                result.confirm()
                return true
            }

            // The page's Photo/DP button: open the system picker and hand the choice back.
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<android.net.Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                return try {
                    startActivityForResult(Intent.createChooser(pick, "Photo chunein"), 777)
                    true
                } catch (t: Throwable) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // v25: voice message ke liye mic (page getUserMedia use karta hai)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }

        try {
            MusicService.start(this)
        } catch (t: Throwable) {
            Log.e("MusicParty", "service start failed", t)
        }

        /* v111-FIX: notification/lockscreen ke Play-Pause buttons page se sync rahen */
        try {
            MusicService.onRemote { playing -> runOnUiThread { onNativeRemote(playing) } }
        } catch (t: Throwable) {}

        /* v111-FIX: OEM battery killers (Infinix/Redmi/Vivo) ke liye ek dafa exemption */
        try { ensureBatteryExemption() } catch (t: Throwable) {}

        // v25 bridge: page se native player kholne ke liye (window.YaarNative.openPlayer)
        // ACT7: ACT6 ka kaan ab do raston wala faisla karta hai —
        //  - page jaag raha (resumed=true): purana tuned rasta (forward -> onMsg) — bilkul ACT6, zero regression
        //  - page soya/band: NativeControl (seedha MpvVideoPlayer) — wahi rasta jo notification
        //    ke pause/play buttons lock par use karte hain (sabit hai ke lock me chalta hai)
        NativeControl.applyCmd = { c -> run {
            try {
                if (!::mpvVideo.isInitialized) return@run false
                val t = c.optDouble("time", Double.NaN)
                when (c.optString("action")) {
                    "pause" -> { try { mpvVideo.pause() } catch (_: Throwable) {}; true }
                    "play" -> {
                        try {
                            if (!t.isNaN() && kotlin.math.abs(mpvVideo.position() - t) > 2.0) mpvVideo.seekTo(t)
                        } catch (_: Throwable) {}
                        try { mpvVideo.resume() } catch (_: Throwable) {}
                        true
                    }
                    "sync" -> {
                        try { if (!t.isNaN()) mpvVideo.seekTo(t) } catch (_: Throwable) {}
                        if (c.optBoolean("playing", true)) try { mpvVideo.resume() } catch (_: Throwable) {}
                        else try { mpvVideo.pause() } catch (_: Throwable) {}
                        true
                    }
                    "seek" -> {
                        if (!t.isNaN() && t >= 0.0) { try { mpvVideo.seekTo(t) } catch (_: Throwable) {}; true } else false
                    }
                    "load" -> {
                        val v = c.optJSONObject("video") ?: return@run false
                        val url = v.optString("url", "")
                        val low = url.substringBefore('?').lowercase()
                        val direct = low.endsWith(".mp4") || low.endsWith(".mp3") ||
                            low.endsWith(".m3u8") || low.endsWith(".mkv") || low.endsWith(".webm")
                        if (!url.startsWith("http") || !direct) return@run false   // YT/unknown — unlock par page
                        try {
                            mpvVideo.play(url, if (t.isNaN()) 0.0 else t, startMuted = false)
                        } catch (_: Throwable) { return@run false }
                        true
                    }
                    else -> false
                }
            } catch (_: Throwable) { false }
        } }
        NativePresence.onMessage = { topic, payload ->
            runOnUiThread {
                try {
                    if (!resumed) NativeControl.apply(topic, payload)   // ACT7: lock/background -> native haath
                    val t = org.json.JSONObject.quote(topic)
                    val p = org.json.JSONObject.quote(payload)
                    web.evaluateJavascript("(window.__wpNativeRoomMsg||function(){})($t,$p)", null)
                } catch (_: Throwable) {}
            }
        }
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun openPlayer(videoUrl: String, title: String?) { runOnUiThread { openNative(videoUrl,title) } }

            /* ACT5: native presence — page (publishPresence ke sath) config bhejta hai; page so
               jaye to bhi NativePresence (wake lock wali side) member refresh karti rehti hai. */
            @android.webkit.JavascriptInterface
            fun wpPresence(json: String?) {
                if (json.isNullOrBlank()) return
                try { NativePresence.start(json) } catch (t: Throwable) { Log.e("MusicParty", "wpPresence", t) }
            }

            @android.webkit.JavascriptInterface
            fun wpPresenceStop() { try { NativePresence.stop(true) } catch (t: Throwable) {} }

            /* v26: DM notification — page se aati hai, sirf jab app saamne na ho */
            @android.webkit.JavascriptInterface
            fun notify(title: String?, text: String?) { postNote(title, text, null) }

            /* v40: notification me "Reply" button -> jawab isi WebView ke page se jayega */
            @android.webkit.JavascriptInterface
            fun notifyFrom(title: String?, text: String?, code: String?) {
                registerForegroundReplyTarget()
                postNote(title, text, code)
            }

            @android.webkit.JavascriptInterface
            fun replyResult(requestId: String?, sent: Boolean) {
                if (!requestId.isNullOrEmpty()) NotifHub.completeReply(requestId, sent)
            }

            /* v44: WebRTC call ke liye earpiece / speaker route (foreground only). */
            @android.webkit.JavascriptInterface
            fun setCallAudioRoute(speaker: Boolean) { this@MainActivity.setCallAudioRoute(speaker) }

            @android.webkit.JavascriptInterface
            fun resetCallAudioRoute() { this@MainActivity.resetCallAudioRoute() }

            @android.webkit.JavascriptInterface
            fun startOngoingCall(): Boolean {
                return try { CallForegroundService.start(this@MainActivity); true }
                catch (t: Throwable) { Log.e("MusicParty", "call foreground service start failed", t); false }
            }

            @android.webkit.JavascriptInterface
            fun stopOngoingCall() { CallForegroundService.stop(this@MainActivity) }

            @android.webkit.JavascriptInterface
            fun showIncomingCall(caller: String?, callId: String?): Boolean {
                return try {
                    if (callId.isNullOrEmpty()) false else {
                        CallForegroundService.showIncoming(this@MainActivity, caller ?: "Private contact", callId)
                        true
                    }
                } catch (t: Throwable) { Log.e("MusicParty", "incoming call alert failed", t); false }
            }

            @android.webkit.JavascriptInterface
            fun clearIncomingCall() { CallForegroundService.stop(this@MainActivity) }

            @android.webkit.JavascriptInterface
            fun mpvDirectLoad(url: String, kind: String, title: String, pos: Double, play: Boolean, revision: Int) {
                val uri = try { Uri.parse(url) } catch (_: Throwable) { return }
                if (uri.scheme !in listOf("http","https") || uri.host.isNullOrBlank() || !pos.isFinite()) return
                runOnUiThread {
                    mpvOnlyRevision=revision;directMode=true;directKind=if(kind=="mp3")"mp3" else if(kind=="hls")"hls" else "mp4"
                    mediaTitle=title.take(200);mpvOnlyDialogId="";ytVideoTry=1
                    if(!MusicService.nativeAlive())beginDirect(url,pos,play)
                }
            }

            @android.webkit.JavascriptInterface
            fun mpvOnlyLoad(id: String, pos: Double, play: Boolean, revision: Int) {
                if (!Regex("[A-Za-z0-9_-]{11}").matches(id) || !pos.isFinite()) return
                runOnUiThread {
                    directMode=false;mediaTitle="YouTube"
                    mpvOnlyRevision = revision
                    mpvOnlyDialogId = ""
                    ytLastPos = pos.coerceAtLeast(0.0); ytLastPlaying = play
                    ytVideoTry = 1; ytFastPoll = true
                    if (!MusicService.nativeAlive()) beginYtVideo(id, ytLastPos, play)
                }
            }

            @android.webkit.JavascriptInterface
            fun mpvOnlyCommand(cmd: String, revision: Int) {
                runOnUiThread {
                    if (revision < mpvOnlyRevision) return@runOnUiThread
                    mpvOnlyRevision = revision
                    when {
                        cmd == "play" -> ytLastPlaying = true
                        cmd == "pause" -> ytLastPlaying = false
                        cmd == "mute:1" -> mpvOnlyMuted = true
                        cmd == "mute:0" -> mpvOnlyMuted = false
                        cmd.startsWith("seekabs:") -> cmd.substringAfter(':').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let {
                            ytLastPos = it; mpvOnlySeekTarget = it; mpvOnlySeekUntil = android.os.SystemClock.elapsedRealtime() + 1800
                        }
                    }
                    if (!MusicService.nativeAlive()) handleMpvCommand(cmd)
                }
            }

            /* Page ke controls (play/pause/seek/mute) -> MPV */
            @android.webkit.JavascriptInterface
            fun mpvCmd(cmd: String?) {
                runOnUiThread { handleMpvCommand(cmd) }
            }

            /* v54: website ka fullscreen activity toast (Paused/Seek/Resumed/Joined/Left/msg) -> native fullscreen column.
               Native fullscreen me WebView invisible hota hai (web.alpha = 0), is liye page JSON yahan bhejta hai. */
            @android.webkit.JavascriptInterface
            fun wpActivity(json: String?) {
                val j = json ?: return
                runOnUiThread { try { fullscreenControls?.activity(j) } catch (_: Throwable) {} }
            }

            /* Page par naya YouTube item aaya -> foran kaam shuru (iframe pehle bajne se bachao) */
            @android.webkit.JavascriptInterface
            fun ytSeen(id: String?) {
                runOnUiThread {
                    try {
                        val v = (id ?: "").trim()
                        if (v.length != 11) return@runOnUiThread
                        ytFastPoll = true
                        if (v != ytVideoId) {
                            // stream abhi se tayyar kar lo -> MPV foran shuru ho jayega
                            ytResolver.execute {
                                try { YtAudioSource.resolve(v, validate = false, preferHeight = ytQuality) } catch (t: Throwable) {}
                            }
                            pollSnapshot()
                        }
                    } catch (t: Throwable) {}
                }
            }

            @android.webkit.JavascriptInterface
            fun appVersion(): Int = 41
        }, "YaarNative")
        registerForegroundReplyTarget()

        /* ══ YOUTUBE = MPV (VIDEO + AUDIO): surface page ke peeche, page ke controls upar ══ */
        try {
            getWindow().setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            mpvVideo = MpvVideoPlayer(this, root)
            mpvVideo.ensure()
            YtAudioSource.ensureInit(applicationContext)
            handoffHandler.postDelayed(mpvUiTick, 700L)
        } catch (t: Throwable) { Log.e("MusicParty", "mpv video init fail", t) }

        // Gboard ka GIF/sticker seedha chat me: upload hoke page ke wpSendGif se chala jata hai.
        web.onGif = { gifUrl, destination ->
            // Check and dispatch in ONE JS evaluation: a late upload must never target another chat.
            val script = """(function(){
              if (${GifWebView.DESTINATION_JS} !== ${org.json.JSONObject.quote(destination)}) return 'chat-changed';
              if (typeof window.wpSendGif !== 'function') return 'not-ready';
              window.wpSendGif(${org.json.JSONObject.quote(gifUrl)});return 'dispatched';
            })()"""
            web.evaluateJavascript(script) { result ->
                if (result == "\"chat-changed\"") showBanner("♡ Chat badal gayi — GIF dobara select karein.")
                else if (result != "\"dispatched\"") showBanner("♡ Chat abhi tayyar nahi — ek baar phir koshish karein.")
            }
        }
        web.onGifError = { msg -> showBanner(msg) }

        // GitHub Pages HTML ko ~10 min cache karta hai + WebView bhi cache karta hai.
        // Har launch pe naya query lagane se page TAZA aata hai, warna naye fixes app me
        // dikhte hi nahi (assets/libs cache me rehte hain, sirf ~100KB page dobara aata hai).
        web.loadUrl(url + "?v=" + System.currentTimeMillis())
    }

    private fun parseIncomingCallAction(source: Intent?): Pair<String, String>? {
        val method = when (source?.action) {
            CallForegroundService.ACTION_ANSWER_INCOMING -> "yaarAnswerIncomingCall"
            CallForegroundService.ACTION_DECLINE_INCOMING -> "yaarDeclineIncomingCall"
            else -> return null
        }
        // Boss fix: top se Accept/Decline pe ring band karo - simple cancel, JS se proper stop hoga
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(9043)
        } catch (_: Throwable) {}
        return method to (source?.getStringExtra(CallForegroundService.EXTRA_CALL_ID) ?: "")
    }

    private fun dispatchIncomingCallAction() {
        val action = pendingIncomingCallAction ?: return
        if (!::web.isInitialized || !pageLoaded) return
        pendingIncomingCallAction = null
        val callId = org.json.JSONObject.quote(action.second)
        web.post { web.evaluateJavascript("window.${action.first} && window.${action.first}($callId)", null) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIncomingCallAction = parseIncomingCallAction(intent)
        dispatchIncomingCallAction()
    }

    private fun registerForegroundReplyTarget() {
        try {
            NotifHub.setReplyTarget("fg") { code, text, requestId ->
                web.post {
                    try { web.evaluateJavascript(NotifHub.quickReplyJs(requestId, code, text), null) } catch (t: Throwable) {
                        NotifHub.completeReply(requestId, false)
                    }
                }
            }
        } catch (t: Throwable) { Log.e("MusicParty", "foreground reply target registration failed", t) }
    }

    @Suppress("DEPRECATION")
    private fun setCallAudioRoute(speaker: Boolean) {
        runOnUiThread {
            try {
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (!callAudioActive) {
                    callOriginalMode = audio.mode
                    callOriginalSpeaker = audio.isSpeakerphoneOn
                    callOriginalDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.communicationDevice else null
                    callAudioActive = true
                }
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audio.availableCommunicationDevices
                    val target = if (speaker) {
                        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    } else {
                        callOriginalDevice?.takeIf { old -> old.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && devices.any { it.id == old.id } }
                            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    }
                    if (target != null) audio.setCommunicationDevice(target)
                    else if (speaker) audio.isSpeakerphoneOn = true
                    else audio.clearCommunicationDevice()
                } else {
                    audio.isSpeakerphoneOn = speaker
                }
            } catch (t: Throwable) {
                Log.w("MusicParty", "call audio route failed", t)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resetCallAudioRoute() {
        val restore = Runnable {
            try {
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                // Force normal mode - fixes volume zero but song 100% bug (was stuck in IN_COMMUNICATION)
                try { audio.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
                try { audio.isSpeakerphoneOn = false } catch (_: Throwable) {}
                try { audio.isMicrophoneMute = false } catch (_: Throwable) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try { audio.clearCommunicationDevice() } catch (_: Throwable) {}
                }
                try { audio.abandonAudioFocus(null) } catch (_: Throwable) {}
                callAudioActive = false
                callOriginalDevice = null
                // Re-take media focus to ensure media stream (not call stream)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val focusReq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            ).build()
                        try { audio.requestAudioFocus(focusReq) } catch (_: Throwable) {}
                        // Immediately abandon to reset
                        try { audio.abandonAudioFocusRequest(focusReq) } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {}
                // Force 3 times with delay - ensures media volume controls work
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val a2 = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        a2.mode = AudioManager.MODE_NORMAL
                        a2.isSpeakerphoneOn = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            try { a2.clearCommunicationDevice() } catch (_: Throwable) {}
                        }
                        try { a2.abandonAudioFocus(null) } catch (_: Throwable) {}
                    } catch (_: Throwable) {}
                }, 400)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val a3 = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        a3.mode = AudioManager.MODE_NORMAL
                        a3.isSpeakerphoneOn = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            try { a3.clearCommunicationDevice() } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }, 1200)
            } catch (t: Throwable) {
                Log.w("MusicParty", "call audio restore failed", t)
                callAudioActive = false
                callOriginalDevice = null
            }
        }
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) restore.run() else runOnUiThread(restore)
        } catch (_: Throwable) {
            callAudioActive = false
            callOriginalDevice = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            val r = pendingWebPerm
            pendingWebPerm = null
            if (r == null) return
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                r.deny()
                showBanner("🎤 Mic ki ijazat nahi mili — voice message ke liye Allow karein")
            }
        }
    }

    private fun showBanner(message: String) {
        if (!::banner.isInitialized) return
        banner.removeCallbacks(hideBanner)
        banner.text = message
        banner.visibility = View.VISIBLE
        banner.postDelayed(hideBanner, 2600)
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private fun pulse(v: View) {
        v.animate().setDuration(900).alpha(1f).withEndAction {
            v.animate().setDuration(900).alpha(0.4f).withEndAction { pulse(v) }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 777) {
            val res = if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            fileCallback?.onReceiveValue(res)
            fileCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Boss fix: call ke dauran PiP me na jao - warna app bahar phenkta hai
        if (CallForegroundService.running) return
        // Home button: shrink into PiP so the WebView stays visible and the party keeps playing.
        if (Build.VERSION.SDK_INT >= 26 && customView == null) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(android.util.Rational(1, 1))
                        .build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (::pipCover.isInitialized) {
            pipCover.visibility = if (isInPip) View.VISIBLE else View.GONE
            if (isInPip) banner.visibility = View.GONE
        }
    }

    private val stopBgIfForeground = Runnable {
        /* Start request ka onCreate/startForeground pehle complete hone do. */
        if (resumed && !BgNotifyService.noteMuted && BgNotifyService.running) {
            try { BgNotifyService.stop(this) } catch (t: Throwable) {}
        }
    }

    override fun onStart() {
        super.onStart()
        /* v111-FIX: wapas app me -> pehle native se position lo, phir page ko control do */
        handoffHandler.removeCallbacks(startHandoff)
        handoffHandler.removeCallbacks(snapPoller)
        handoffHandler.postDelayed(snapPoller, 1200L)
        try {
            registerReceiver(screenOffReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        } catch (t: Throwable) {}
        resumeFromNativeIfNeeded()
        try {
            bgHandler.removeCallbacks(bgStarter)
            bgHandler.removeCallbacks(stopBgIfForeground)
            /* onStart par foran stop karna FGS ke pending start ko race me cancel kar sakta tha.
               Thori dair baad sirf confirmed-running service band karo. */
            if (!BgNotifyService.noteMuted) bgHandler.postDelayed(stopBgIfForeground, 2000L)
        } catch (t: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        web.onResume()
        /* v111-FIX (build 5): WebView yahin (onResume) jaagta hai — is liye resume/watchdog yahan
           se shuru karo, onStart se nahi (wahan page abhi frozen hota hai). */
        try { if (MusicService.nativeAlive()) resumeFromNativeIfNeeded() } catch (t: Throwable) {}
        // Boss fix: volume zero but song 100% - media playing via call stream - ensure normal mode when not in call
        if (!CallForegroundService.running) {
            try { resetCallAudioRoute() } catch (_: Throwable) {}
            // Extra force normal mode immediately
            try {
                val audio = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                audio.mode = android.media.AudioManager.MODE_NORMAL
                audio.isSpeakerphoneOn = false
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    try { audio.clearCommunicationDevice() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        try { mpvVideo.setSyncSpeed(1.0); web.evaluateJavascript("window.__wpSyncSuspend && window.__wpSyncSuspend()", null) } catch (_: Throwable) {}
        /* v111-FIX: jab tak page saamne/JS zinda hai, aakhri state cache kar lo.
           (lock ya Home ke baad WebView freeze ho jata hai — phir poochhna bekaar hai) */
        try { pollSnapshot() } catch (t: Throwable) {}
    }

    override fun onStop() {
        super.onStop()
        if (!CallForegroundService.running) resetCallAudioRoute()

        /* v111-FIX: PiP me page saamne hota hai -> wahan handoff nahi. Warna page saamne nahi
           hai to cached state se handoff (turant koshish, phir 1.5s baad ek fallback). */
        try { unregisterReceiver(screenOffReceiver) } catch (t: Throwable) {}
        handoffHandler.removeCallbacks(snapPoller)
        try {
            val pipStillVisible = isInPictureInPictureMode && isScreenOn()
            if (!CallForegroundService.running && pageLoaded && !pipStillVisible && !isChangingConfigurations) {
                handoffNow("onStop-immediate")
                handoffHandler.removeCallbacks(startHandoff)
                handoffHandler.postDelayed(startHandoff, 1500L)
            }
        } catch (t: Throwable) {}

        try { bgHandler.removeCallbacks(stopBgIfForeground) } catch (t: Throwable) {}
        web.onResume()
        web.resumeTimers()
        /* Boss fix: call ke dauran BgNotifyService start na karo - clash se app bahar phenkta hai */
        if (CallForegroundService.running) return
        /* User app se bahar jaye to background DM listener foran start ho,
           taa ke 30-second blind window na rahe. */
        if (!isChangingConfigurations) {
            try {
                bgHandler.removeCallbacks(bgStarter)
                bgHandler.post(bgStarter)
            } catch (t: Throwable) {}
        }
    }

    // NOTE: onPause() intentionally does NOT call web.onPause() — background audio must keep flowing.

    /* ---------------- v30: native player ko page se khud pakro ---------------- */

    private val JS_WATCH = "(function(){try{if(window.__wpMpvOnly)return '';var v=document.getElementById('mp4-player');if(!v)return '';" +
            "var u=v.currentSrc||v.src||'';if((v.className||'').indexOf('hidden')>=0)return '';" +
            "if(u.indexOf('http')!==0&&u.indexOf('blob:')!==0)return '';return u;}catch(e){return '';}})()"

    private val JS_GET = "(function(){try{var v=document.getElementById('mp4-player');var u=v?(v.currentSrc||v.src||''):'';" +
            "var t=document.getElementById('mini-title');return (u||'')+'\\u0001'+(t?(t.textContent||''):'');}catch(e){return '';}})()"

    private fun jstr(res: String?): String {
        if (res == null || res == "null") return ""
        return try { org.json.JSONObject("{\"v\":$res}").getString("v") } catch (t: Throwable) { "" }
    }

    private fun pausePagePlayer() {
        try {
            web.evaluateJavascript(
                "(function(){try{if(typeof suppressMP4!=='undefined')suppressMP4=true;}catch(e){}" +
                "try{var v=document.getElementById('mp4-player');if(v)v.pause();}catch(e){}})()", null
            )
        } catch (t: Throwable) {}
    }

    private fun openNative(url: String, title: String?, alt: String? = null) {
        val data=org.json.JSONObject().put("url",url).put("type",if(url.substringBefore('?').endsWith(".mp3",true))"mp3" else "mp4").put("title",title?:"Media")
        web.evaluateJavascript("loadVideoLocal($data,true)",null)
    }

    private fun tapNative() {
        try {
            web.evaluateJavascript(JS_GET) { res ->
                val parts = jstr(res).split('\u0001')
                val url = parts.getOrNull(0)?.trim() ?: ""
                val title = parts.getOrNull(1)?.trim() ?: "Video"
                if (url.startsWith("http") || url.startsWith("blob:")) openNative(url, title)
                else showBanner("\u26a0\ufe0f Pehle koi direct video link lagao (mp4 / m3u8)")
            }
        } catch (t: Throwable) {}
    }

    private val watchMp4 = object : Runnable {
        override fun run() {
            try {
                if (resumed) {
                    web.evaluateJavascript(JS_WATCH) { res ->
                        try {
                            val u = jstr(res).trim()
                            if (u.length > 8 && u != lastAutoUrl) {
                                lastAutoUrl = u
                                openNative(u, null)
                            }
                        } catch (t: Throwable) {}
                    }
                }
            } catch (t: Throwable) {}
            bgHandler.postDelayed(this, 1500)
        }
    }

    /* ---------------- v26: DM notifications ---------------- */

    private fun postNote(title: String?, text: String?, code: String?) {
        if (resumed) return            // app saamne hai -> toast/page khud dikha dega
        NotifHub.post(this, title, text, "fg", code)
    }

    /* ════════════════ v111-FIX: background / lock-screen audio ════════════════ */

    private fun isScreenOn(): Boolean = try {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    } catch (t: Throwable) { true }

    /** Page ke andar bridge inject karo (website ko chhua nahi jata). */
    private fun injectBridge(view: WebView?) {
        try { view?.evaluateJavascript(WebBridge.JS_BRIDGE + ";window.__wpOnlyAttach && window.__wpOnlyAttach();", null) } catch (t: Throwable) {}
    }

    private fun readSnapshot(onDone: (WebBridge.Snapshot?) -> Unit) {
        try {
            web.evaluateJavascript(WebBridge.JS_SNAP) { res -> onDone(WebBridge.parse(res)) }
        } catch (t: Throwable) { onDone(null) }
    }

    /**
     * FAST START (build 9): jab gaana app ke andar chal raha ho, native engine ko pehle se tayyar
     * kar do — MPV core init + YouTube stream URL extraction. Lock karne par sirf unpause bachta
     * hai, is liye gaana 8 second ke bajaye 1-2 second me shuru ho jata hai.
     */
    /**
     * BUILD 14: AV1 / MKV files — ye page (premium player) par crash karti hain aur MPV par bhi
     * masla karti hain. Is liye in ko MPV ke paas bhejna hi nahi (na prewarm, na lock par handoff).
     */
    private fun isMpvBlocked(id: String?): Boolean = false

    private fun maybePrewarm(s: WebBridge.Snapshot) {
        try {
            if (!s.playing) return
            if (s.id.startsWith("blob:")) return
            if (!s.isYoutube && isMpvBlocked(s.id)) return      // build 14: AV1/MKV -> MPV ko mat chhedо
            val now = System.currentTimeMillis()
            if (s.id == prewarmId && now - prewarmAt < 60_000L) return
            prewarmId = s.id
            prewarmAt = now
            MusicService.prewarm(this, if (s.isYoutube) "youtube" else "direct", s.id, s.title)
            Log.i("MusicParty", "prewarm: ${s.type} ${s.id}")
        } catch (t: Throwable) {}
    }

    /**
     * ══ YOUTUBE = MPV (VIDEO + AUDIO) — v115 ══
     *
     * MPV ka surface page ke PEECHE hota hai; page ke player area me CSS "hole" bana diya jata hai,
     * is liye us jagah MPV ki video nazar aati hai aur aawaz MPV se aati hai.
     * Page ke apne controls (play/seek/quality) upar rehte hain — jaise hain waise kaam karte hain.
     * Queue / chat / party sync page hi chalata hai -> sab kuch salamat rehta hai.
     */
    private fun syncYtVideo(s: WebBridge.Snapshot) {
        try {
            if(s.type in listOf("mp4","mp3","hls")) {
                if(!MusicService.nativeAlive() && (ytVideoId!=s.id || !directMode)){directMode=true;directKind=s.type;mediaTitle=s.title;beginDirect(s.id,s.position,s.playing)}
                return
            }
            if (!s.isYoutube || s.id.isBlank() || s.id.length != 11) { endYtVideo(); return }
            if (MusicService.nativeAlive()) return              // lock/unlock ka apna rasta hai
            if (s.id != ytVideoId || directMode) { directMode=false; startYtVideo(s); return }
            // MPV is authoritative. Polling its own mirrored state must not replay/seek
            // the native engine. Remote/local controls reach the API adapter directly.

        } catch (t: Throwable) {}
    }

    /** Naya YouTube item: stream URL nikaalo aur MPV (video + audio) par chalao. */
    private fun startYtVideo(s: WebBridge.Snapshot) {
        if (s.id == ytVideoId) ytVideoTry += 1 else ytVideoTry = 1
        ytLastPos = s.position
        ytLastPlaying = s.playing
        ytFastPoll = true
        beginYtVideo(s.id, s.position, s.playing)
    }

    /** Fail ke baad khud dobara koshish (stored position/playing ke sath). */
    private fun retryYtVideo() {
        try {
            val id = ytVideoId
            if (id.isBlank()) return
            if (MusicService.nativeAlive()) return
            ytVideoTry += 1
            if(directMode)beginDirect(id,ytLastPos,ytLastPlaying) else beginYtVideo(id, ytLastPos, ytLastPlaying)
        } catch (t: Throwable) {}
    }

    private fun beginDirect(url: String, pos: Double, playing: Boolean) {
        val generation=++ytResolveGeneration
        ytVideoId=url;ytVideoState=1;ytFastPoll=true;ytLastPos=pos;ytLastPlaying=playing
        mpvVideo.ensure();mpvVideo.stop();lastYtBuf=false
        fun loadWhenReady(attempt: Int) {
            if(generation!=ytResolveGeneration||ytVideoId!=url)return
            if(!mpvVideo.isReady){
                if(attempt>=100){failYtVideo(url,"MPV core not ready");return}
                handoffHandler.postDelayed({loadWhenReady(attempt+1)},100);return
            }
            mpvVideo.play(url,ytLastPos,startMuted=true)
            if(!ytLastPlaying)mpvVideo.pause()
            armYtWatch(url,0)
        }
        loadWhenReady(0)
    }

    /** Asli kaam: stream nikaalo aur MPV par chalao. */
    private fun beginYtVideo(wantId: String, wantPos: Double, wantPlay: Boolean) {
        if (wantId.isBlank() || wantId.length != 11) return
        ytLastPos = wantPos; ytLastPlaying = wantPlay
        val generation = ++ytResolveGeneration
        val requestedQuality = ytQuality
        ytDiagnostic = ""
        ytVideoId = wantId
        ytVideoState = 1
        ytSlowBanner = false
        ytStallPos = -1.0
        mpvVideo.ensure()
        mpvVideo.stop() // Stop old stream/buffering while the next source resolves.
        setYtMute(true)          // page ki aawaz foran band — MPV jab tayyar hoga aawaz dega
        ytResolver.execute {
            // Exact requested height plus audio, resolved on-device by pinned yt-dlp.
            val r = try { YtAudioSource.resolve(wantId, validate = false, preferHeight = requestedQuality) } catch (t: Throwable) { null }
            handoffHandler.post {
                try {
                    if (ytVideoId != wantId || generation != ytResolveGeneration) return@post
                    if (r == null) { failYtVideo(wantId, "yt-dlp: ${requestedQuality}p + audio nahi mili"); return@post }
                    availableQualities = r.qualities.ifEmpty { listOf(ytQuality) }
                    mediaTitle = r.title ?: mediaTitle
                    web.evaluateJavascript("window.__wpOnlyQualities && window.__wpOnlyQualities(${org.json.JSONArray(availableQualities)})",null)
                    ytLastUrl = r.url
                    ytLastAudio = r.audioUrl
                    mpvVideo.play(r.url, ytLastPos, startMuted = true,
                        audioUrl = r.audioUrl, userAgent = r.userAgent, referer = r.referer)
                    if (!ytLastPlaying) mpvVideo.pause()
                    armYtWatch(wantId, 0)
                } catch (t: Throwable) {}
            }
        }
    }

    /** MPV ka intezaar: pehla frame aa gaya -> page chup + MPV ki video/aawaz (warna fallback). */
    private fun armYtWatch(id: String, attempt: Int) {
        handoffHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    if (ytVideoId != id) return
                    if (if(directMode)mpvVideo.loaded() else (mpvVideo.hasFrame() && mpvVideo.audioCodec().isNotBlank())) { activateYtVideo(id); return }
                    val err = mpvVideo.error
                    // slow net: pehla frame aane me waqt lag sakta hai -> 30s tak intezaar (user ko dikhao)
                    if (attempt == 16 && !ytSlowBanner) {
                        ytSlowBanner = true
                        showBanner("⏳ YouTube load ho raha hai (net slow)…")
                    }
                    if (err != null || attempt >= 120) { failYtVideo(id, err); return }
                    armYtWatch(id, attempt + 1)
                } catch (t: Throwable) {}
            }
        }, 250L)
    }

    /** MPV chal gaya: page ki aawaz band + player area me hole + MPV ki aawaz on. */
    private fun activateYtVideo(id: String) {
        try {
            setYtMute(true)
            mpvVideo.show()
            setYtHole(true)
            setYtLink(true)          // page ke controls ab MPV ko command bhejenge
            updateYtRect()
            mpvVideo.setMuted(mpvOnlyMuted)
            ytVideoState = 2
            try { web.evaluateJavascript(WebBridge.mpvQualityJs(ytQuality), null) } catch (t: Throwable) {}
            ytStallPos = -1.0
            lastYtBuf = false; lastYtBufPct = 0
            try { web.evaluateJavascript(WebBridge.mpvBufJs(false, 0), null) } catch (t: Throwable) {}
            if (!ytVideoBanner) { ytVideoBanner = true; showBanner("▶ MPV ready") }
            Log.i("MusicParty", "yt video active: $id")
        } catch (t: Throwable) {}
    }

    /** MPV na chala -> page hi apni aawaz chalata rahe (kabhi khamoshi nahi). */
    private fun failYtVideo(id: String, err: String?) {
        try {
            if (ytVideoId != id) return
            Log.w("MusicParty", "yt video fail: $err")
            setYtMute(false)
            quitYtFsQuiet()
            try { web.evaluateJavascript(WebBridge.mpvBufJs(false, 0), null) } catch (t: Throwable) {}
            lastYtBuf = false
            mpvVideo.stop(); mpvVideo.hide(); setYtHole(false); setYtLink(false)
            ytVideoState = 3
            ytFailAt = System.currentTimeMillis()
            // Never silently enable YouTube behind MPV. Retry or explicit external fallback.
            if (mpvOnlyDialogId != id && !isFinishing) {
                mpvOnlyDialogId = id
                AlertDialog.Builder(this)
                    .setTitle("MPV playback failed")
                    .setMessage("Browser media players are disabled. Check the link or retry MPV.")
                    .setPositiveButton("Retry MPV") { _, _ -> mpvOnlyDialogId = ""; retryYtVideo() }
                    .setNegativeButton("Cancel", null).show()
            }
            showBanner("⚠️ MPV failed — check link or Retry; browser player stays OFF")
        } catch (t: Throwable) {}
    }

    /** YouTube khatam / koi aur item -> MPV video band, page wapas normal. */
    private fun endYtVideo() {
        try {
            if (ytVideoState == 0 && ytVideoId.isBlank()) return
            quitYtFsQuiet()
            try { web.evaluateJavascript(WebBridge.mpvBufJs(false, 0), null) } catch (t: Throwable) {}
            lastYtBuf = false
            ytResolveGeneration++
            ytDiagnostic = ""
            ytVideoId = ""
            ytVideoState = 0
            ytVideoTry = 0
            ytFailAt = 0L
            mpvVideo.stop(); mpvVideo.hide(); setYtHole(false); setYtLink(false)
            setYtMute(false)
        } catch (t: Throwable) {}
    }

    /** Player area ka rect lo aur MPV surface wahan rakh do. */
    private fun updateYtRect() {
        try {
            if (ytVideoState != 2) return
            // holeJs(true) bars bhi update karta hai aur rect bhi wapas deta hai
            web.evaluateJavascript(WebBridge.holeJs(true)) { res ->
                try {
                    val json = WebBridge.rawJson(res)
                    if (!json.isNullOrBlank() && json != "{}") {
                        val o = org.json.JSONObject(json)
                        val w = o.optDouble("w", 0.0)
                        val h = o.optDouble("h", 0.0)
                        if (w >= 40 && h >= 40) {
                            mpvVideo.setRect(
                                o.optDouble("x", 0.0).toFloat(),
                                o.optDouble("y", 0.0).toFloat(),
                                w.toFloat(), h.toFloat()
                            )
                        }
                    }
                } catch (t: Throwable) {}
            }
        } catch (t: Throwable) {}
    }

    /** 🎚️ Quality badli: naya stream usi jagah se chalao (aawaz alag wali bhi jodi jaye). */
    private fun switchYtQuality(h: Int) {
        try {
            if(directMode || h !in listOf(144,240,360,480,720,1080))return
            val q = h
            if (q == ytQuality && mpvVideo.hasFrame()) return      // wahi quality pehle se
            val id = ytVideoId
            if (id.isBlank() || ytVideoState != 2) {
                ytQuality = q
                if (id.isNotBlank() && ytVideoState == 1) beginYtVideo(id, ytLastPos, ytLastPlaying)
                return
            }
            val generation = ++ytResolveGeneration
            val prevQ = ytQuality                                 // fail hua to isi par wapas
            ytQuality = q
            showBanner("🎚️ ${q}p + audio dhoond rahe hain…")
            ytResolver.execute {
                val r = try { YtAudioSource.resolve(id, validate = false, preferHeight = q) } catch (t: Throwable) { null }
                handoffHandler.post {
                    try {
                        if (ytVideoId != id || ytVideoState != 2 || generation != ytResolveGeneration) return@post
                        if (r == null) {   // naya stream nahi mila -> purana hi chalta rahe
                            ytQuality = prevQ
                            showBanner("⚠️ ${q}p stream nahi mili — ${prevQ}p hi chal rahi hai")
                            try { web.evaluateJavascript(WebBridge.mpvQualityJs(prevQ), null) } catch (t: Throwable) {}
                            return@post
                        }
                        ytLastUrl = r.url
                        ytLastAudio = r.audioUrl
                        val pos = mpvVideo.position()
                        val wasPlaying = !mpvVideo.isPaused()
                        ytDiagnostic = ""
                        mpvVideo.play(r.url, pos, startMuted = false, audioUrl = r.audioUrl,
                            userAgent = r.userAgent, referer = r.referer)
                        if (!wasPlaying) mpvVideo.pause()
                        web.evaluateJavascript(WebBridge.mpvQualityJs(q), null)
                        Log.i("MusicParty", "quality switch -> ${q}p @ $pos")
                    } catch (t: Throwable) {}
                }
            }
        } catch (t: Throwable) {}
    }

    /** Slow net ya URL expire: position kuch der se nahi barhi -> dobara koshish; baar baar ho to page par wapas.
        (App tang nahi karta: MPV apni thread par chalta hai; ye sirf recovery hai.) */
    private fun checkYtStall() {
        try {
            if (ytVideoState != 2) return
            if (mpvVideo.isPaused()) { ytStallPos = -1.0; return }
            val p = mpvVideo.position()
            val now = System.currentTimeMillis()
            if (ytStallPos < 0 || kotlin.math.abs(p - ytStallPos) > 0.3) {
                ytStallPos = p; ytStallAt = now
                return
            }
            // buffering ho to zyada mohlat (slow net normal hai), warna 25s
            val limit = if (mpvVideo.buffering()) 60000L else 25000L
            if (now - ytStallAt > limit) {
                if (ytVideoTry < 4) {
                    ytLastPos = p
                    ytLastPlaying = true
                    showBanner("⏳ Net slow — MPV dobara koshish kar raha hai…")
                    retryYtVideo()
                } else {
                    failYtVideo(ytVideoId, "network stalled")
                }
                ytStallPos = -1.0; ytStallAt = now
            }
        } catch (t: Throwable) {}
    }

    private fun setYtMute(m: Boolean) {
        try { web.evaluateJavascript(WebBridge.muteJs(m), null) } catch (t: Throwable) {}
    }

    private fun setYtHole(on: Boolean) {
        try { web.evaluateJavascript(WebBridge.holeJs(on), null) } catch (t: Throwable) {}
    }

    private fun setYtLink(on: Boolean) {
        try { web.evaluateJavascript(if (on) WebBridge.JS_MPV_ON else WebBridge.JS_MPV_OFF, null) } catch (t: Throwable) {}
    }

    /** Apna MPV fullscreen: page layout full-screen ho jata hai; hum rect + screen-on theek karte hain. */
    private fun enterYtFs() {
        if(ytFsOn || ytVideoState!=2)return
        ytFsOn=true
        mpvVideo.setFullscreen(true)
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(web.windowToken,0) } catch (_: Throwable) {}
        webAccessibilityBeforeFs=web.importantForAccessibility
        web.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        web.alpha=0f // Keep JS/Party state active while native fullscreen covers the page.
        val controls=MpvFullscreenControls(this,mpvVideo,
            send={cmd->if(cmd.startsWith("quality:"))handleMpvCommand(cmd) else web.evaluateJavascript("window.__wpUnifiedCommand && window.__wpUnifiedCommand(${org.json.JSONObject.quote(cmd)})",null)},
            exit={exitYtFs()},sourceTitle={mediaTitle},isYoutube={!directMode},isAudio={directMode&&(directKind=="mp3" || (mpvVideo.actualHeight()==0 && mpvVideo.audioCodec().isNotBlank()))},
            quality={ytQuality},qualities={availableQualities})
        fullscreenControls=controls;root.addView(controls,FrameLayout.LayoutParams(-1,-1))
    }
    private fun exitYtFs() {
        if(!ytFsOn)return
        ytFsOn=false
        fullscreenControls?.let { it.release();root.removeView(it) };fullscreenControls=null
        mpvVideo.setFullscreen(false);web.alpha=1f;web.importantForAccessibility=webAccessibilityBeforeFs
        web.evaluateJavascript("window.__wpMpvFsOn=false",null)
        for(delay in longArrayOf(50,250,600))handoffHandler.postDelayed({updateYtRect()},delay)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus&&ytFsOn)fullscreenControls?.immerse()
    }

    /** Fullscreen ko chup-chaap band karo (video khatam / fail par). */
    private fun quitYtFsQuiet() {
        try {
            if (!ytFsOn) return
            try { web.evaluateJavascript("window.__wpMpvFsSet && window.__wpMpvFsSet(0)", null) } catch (t: Throwable) {}
            exitYtFs()
        } catch (t: Throwable) {}
    }

    /** Page se aayi command (play/pause/seek/mute) -> MPV. Sirf jab MPV video active ho. */
    private fun handleMpvCommand(cmd: String?) {
        try {
            val c = (cmd ?: "").trim()
            if (c.isEmpty()) return
            if (c == "rect") { updateYtRect(); return }      // fullscreen/resize: foran surface set karo
            if (c == "fs:1") { enterYtFs(); return }
            if (c == "fs:0") { exitYtFs(); return }
            if (c.startsWith("quality:")) { c.substringAfter(':').toIntOrNull()?.let { switchYtQuality(it) }; return }
            if (c.startsWith("speed:")) { c.substringAfter(':').toDoubleOrNull()?.let { mpvVideo.setSyncSpeed(it) }; return }
            if (ytVideoState != 2) return
            when {
                c == "toggle" || c == "playpause" -> if (mpvVideo.isPaused()) mpvVideo.resume() else mpvVideo.pause()
                c == "pause" -> mpvVideo.pause()
                c == "play" -> mpvVideo.resume()
                c == "mute" -> mpvVideo.setMuted(!mpvVideo.isMuted())
                c == "mute:1" -> mpvVideo.setMuted(true)
                c == "mute:0" -> mpvVideo.setMuted(false)
                c.startsWith("seekrel:") -> {
                    val d = c.substringAfter(':').toDoubleOrNull() ?: return
                    mpvVideo.seekTo((mpvVideo.position() + d).coerceAtLeast(0.0))
                }
                c.startsWith("seekabs:") -> {
                    val t = c.substringAfter(':').toDoubleOrNull() ?: return
                    mpvVideo.seekTo(t.coerceAtLeast(0.0))
                }
            }
            setYtMute(true)   // page ki iframe chup hi rahe (double audio kabhi nahi)
            Log.i("MusicParty", "mpv cmd: $c")
        } catch (t: Throwable) {}
    }

    /** Lock ke baad: aawaz service se wapas MPV (video + audio) par — bina gap ke. */
    private fun handBackToMpvVideo() {
        val id=MusicService.nativeItemKey();val youtube=MusicService.nativeIsYoutube()
        val pos=MusicService.nativePosition();val play=MusicService.nativeWasPlaying()
        if(id.isBlank())return
        MusicService.stopNative()
        directMode=!youtube
        if(youtube)beginYtVideo(id,pos,play) else beginDirect(id,pos,play)
    }

    /** Fullscreen view + uske bachon ka background transparent (taake peeche MPV surface dikhe). */
    private fun makeTransparent(v: View) {
        try { v.setBackgroundColor(Color.TRANSPARENT) } catch (t: Throwable) {}
        try {
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    // WebView/surface jaise bhakti views ko chhero mat — sirf containers
                    if (c is android.view.ViewGroup || c.javaClass.name.contains("FrameLayout")) makeTransparent(c)
                }
            }
        } catch (t: Throwable) {}
    }

    /** MPV video chalte waqt page ko asli timing bhejo (time bar + mini player ka clock). */
    private val mpvUiTick = object : Runnable {
        override fun run() {
            try {
                if (ytVideoState == 2 && pageLoaded && !MusicService.nativeAlive()) {
                    // MPV ki ASLI timing page par (time line, icons, mini clock) + surface rect taaza (rotate/fullscreen)
                    web.evaluateJavascript(
                        WebBridge.mpvTimeJs(mpvVideo.position(), !mpvVideo.isPaused(), mpvVideo.duration(), mpvVideo.isMuted()),
                        null
                    )
                    updateYtRect()
                    // slow net: buffering ho raha hai? -> page par "⏳ Buffering" dikhao
                    val buf = mpvVideo.buffering()
                    val bpct = if (buf) mpvVideo.cachePct() else 0
                    if (buf != lastYtBuf || (buf && kotlin.math.abs(bpct - lastYtBufPct) >= 8)) {
                        lastYtBuf = buf; lastYtBufPct = bpct
                        web.evaluateJavascript(WebBridge.mpvBufJs(buf, bpct), null)
                    }
                    fullscreenControls?.tick()
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now >= mpvOnlySeekUntil || kotlin.math.abs(mpvVideo.position() - mpvOnlySeekTarget) < 1.0) {
                        val packet = org.json.JSONObject().put("id", ytVideoId).put("rev", mpvOnlyRevision)
                            .put("t", mpvVideo.position()).put("d", mpvVideo.duration())
                            .put("raw",mpvVideo.rawPosition()).put("buffering",buf).put("speed",mpvVideo.syncSpeed())
                            .put("ready",mpvVideo.loaded()).put("foreground",resumed && !CallForegroundService.running)
                            .put("playing", !mpvVideo.isPaused() && !mpvVideo.ended()).put("muted", mpvVideo.isMuted())
                            .put("ended", mpvVideo.ended()).put("art",mpvVideo.hasArtwork()).put("audioOnly",directMode && mpvVideo.actualHeight()==0 && mpvVideo.audioCodec().isNotBlank())
                        web.evaluateJavascript("window.__wpOnlyReport && window.__wpOnlyReport($packet)", null)
                    }
                    checkYtStall()   // video ruki hui hai? -> khud recovery
                }
            } catch (t: Throwable) {}
            // 🔊 watchdog: call khatam ho gaya magar audio mode atka (volume zero par bhi awaz) -> theek karo
            try {
                fsTick++
                if (fsTick % 20 == 0 && !CallForegroundService.running) {
                    val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    if (am.mode != android.media.AudioManager.MODE_NORMAL) resetCallAudioRoute()
                }
            } catch (t: Throwable) {}
            handoffHandler.postDelayed(this, 450L)
        }
    }

    /** App saamne hone par page ki state + queue cache karo (JS zinda hai, is liye bharosemand). */
    private fun pollSnapshot() {
        try {
            if (!pageLoaded) return
            web.evaluateJavascript(WebBridge.JS_SNAP) { res ->
                val s = WebBridge.parse(res)
                if (s != null && !s.isEmpty) {
                    lastSnap = s
                    lastSnapAt = System.currentTimeMillis()
                    if (s.isYoutube) {
                        ytFastPoll = s.playing
                        ytLastPos = s.position
                        ytLastPlaying = s.playing
                    }
                    maybePrewarm(s)     // build 9: FAST START — jab app saamne hai, sab tayyar kar lo
                    syncYtVideo(s)      // YouTube video + audio MPV par
                }
            }
            // build 7/9: queue bhi cache karo — background auto-next isi se chalta hai
            web.evaluateJavascript(WebBridge.JS_QUEUE) { res ->
                val json = WebBridge.rawJson(res)
                if (!json.isNullOrBlank() && json != "{}") {
                    lastQueueJson = json                 // handoff ke sath sath bhi bhejenge
                    MusicService.cacheQueue(this, json)
                }
            }
        } catch (t: Throwable) {}
    }

    /**
     * Background/lock hone par: page ka player chup, wahi gaana native engine par (usi second se).
     * Cached state use hoti hai — kyunki is waqt tak WebView freeze ho chuka hota hai.
     */
    private fun handoffNow(reason: String) {
        if (!pageLoaded) return
        ytHandbackDone = false
        val snap = lastSnap
        if (snap == null || snap.isEmpty) return
        val ageMs = System.currentTimeMillis() - lastSnapAt
        if (ageMs > 30000L) return                 // bohat purani state, bharosa nahi
        if (!snap.playing) return                  // user ne khud pause kiya hua tha
        if (snap.id.startsWith("blob:")) {
            showBanner("⚠️ Ye stream background me nahi chala sakta")
            return
        }
        if (!snap.isYoutube && isMpvBlocked(snap.id)) {
            // build 14: AV1/MKV ko MPV par bhejna hi nahi (page + MPV dono in par masla karte hain)
            if (blockedBannerId != snap.id) {
                blockedBannerId = snap.id
                showBanner("⚠️ AV1/MKV file — ye format MPV par nahi bheja jata")
            }
            Log.i("MusicParty", "handoff skip (AV1/MKV): ${snap.id}")
            return
        }
        val type = if (snap.isYoutube) "youtube" else "direct"
        // MPV video engine chal raha hai? -> uska position hi asli hai
        var hoPos = snap.position
        if (ytVideoState == 2) {
            try { val p = mpvVideo.position(); if (p > 0.5) hoPos = p } catch (t: Throwable) {}
        }
        handoffAttempted = true
        Log.i("MusicParty", "handoff[$reason] type=$type id=${snap.id} pos=$hoPos title=${snap.title}")
        MusicService.start(this)
        pollSnapshot()   // queue + taza state turant cache (JS abhi zinda hai)
        MusicService.handoff(this, type, snap.id, hoPos, snap.title, true, snap.queueIndex, lastQueueJson)
        try { web.evaluateJavascript(WebBridge.JS_PAUSE_PAGE, null) } catch (t: Throwable) {}
        // video engine ka kaam khatam — service (audio-only) sambhal leti hai
        if (ytVideoState == 2) {
            handoffHandler.postDelayed({
                try {
                    if (ytVideoState == 2) { mpvVideo.stop(); mpvVideo.hide(); setYtHole(false) }
                } catch (t: Throwable) {}
            }, 1500L)
        }
    }

    /**
     * Wapas app me aane par: page ko native ki position do, CONFIRM karo ke page chal raha hai,
     * phir hi native engine band karo. (Build 4 ka bug: stopNative foran ho jata tha magar page
     * resume nahi hota tha -> page pe gaana ruka, background me audio chalta reh jata tha.)
     */
    private fun resumeFromNativeIfNeeded() {
        try {
            if (!MusicService.nativeAlive()) {
                // handoff hua tha magar native chala nahi? -> wajah dikhao aur page wapas chala do
                val err = MusicService.nativeError()
                if (handoffAttempted) {
                    MusicService.clearError()
                    if (!err.isNullOrBlank()) showBanner("🔇 Background: $err — page par wapas chala diya")
                    web.evaluateJavascript(WebBridge.resumeJs(-1.0, true), null)
                }
                handoffAttempted = false
                return
            }
            handoffAttempted = false
            resumeTries = 0
            handoffHandler.removeCallbacks(resumeWatchdog)
            handoffHandler.postDelayed(resumeWatchdog, 500L)
        } catch (t: Throwable) {}
    }

    /** Har 800ms: page ko resume ki koshish + check. Page chal gaya -> native band. 10 tries baad
        bhi page nahi chala -> native sakhti se band (dohri awaz kabhi nahi).
        Build 7: auto-next ke baad page me wahi item load karo + usi second se seek karo. */
    private val resumeWatchdog: Runnable = object : Runnable {
        override fun run() {
            try {
                if (!MusicService.nativeAlive()) return
                resumeTries++
                val nativeKey = MusicService.nativeItemKey()
                val nativeIsYt = MusicService.nativeIsYoutube()
                val snap = lastSnap
                val sameItem = snap != null && nativeKey.isNotBlank() && snap.id == nativeKey

                val nativePos = MusicService.nativePosition()
                // same gaana hai? to jo aage hai wahi lo (yani kabhi peeche na jaye)
                val pos = if (sameItem) maxOf(nativePos, snap?.position ?: 0.0) else nativePos
                val play = MusicService.nativeWasPlaying()

                if (!sameItem) {
                    // background me auto-next hua hoga -> page me bhi wahi item load karo
                    MusicService.nativeItemJson()?.let { json ->
                        web.evaluateJavascript(WebBridge.playItemJs(json), null)
                    }
                    val idx = MusicService.nativeQueueIndex()
                    if (idx >= 0) web.evaluateJavascript(WebBridge.setQueueIndexJs(idx), null)
                }

                web.evaluateJavascript(
                    WebBridge.resumeJs(pos, play, if (nativeIsYt && nativeKey.length == 11) nativeKey else null),
                    null
                )

                web.evaluateJavascript(WebBridge.JS_IS_PAGE_PLAYING) { res ->
                    val playing = res?.contains("1") == true
                    if (playing || !play) {
                        // YouTube hai -> aawaz wapas MPV (video + audio) par le jao
                        if (!ytHandbackDone) {
                            ytHandbackDone = true
                            handBackToMpvVideo()
                        } else {
                            MusicService.stopNative()   // direct file -> page hi chala raha hai
                        }
                        resumeTries = 0
                    } else if (resumeTries >= 10) {
                        // ~8 second tak koshish (YouTube iframe ko reload hone me waqt lagta hai)
                        MusicService.stopNativeHard()
                        showBanner("🔇 Page ne resume nahi kiya — background audio band kar diya (app wapas kholo)")
                        resumeTries = 0
                    } else {
                        handoffHandler.postDelayed(resumeWatchdog, 800L)
                    }
                }
            } catch (t: Throwable) {
                try { handoffHandler.postDelayed(resumeWatchdog, 800L) } catch (t2: Throwable) {}
            }
        }
    }

    /** Notification / lockscreen / headset button -> page bhi sync rahe. */
    private fun onNativeRemote(playing: Boolean) {
        try {
            if (playing) web.evaluateJavascript(WebBridge.resumeJs(-1.0, true), null)
            else web.evaluateJavascript(WebBridge.JS_PAUSE_PAGE, null)
        } catch (t: Throwable) {}
    }

    /** Android ko batao: ye app background me gaana bajati hai — isay na maro. */
    private fun ensureBatteryExemption() {
        try {
            if (Build.VERSION.SDK_INT < 23) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            val prefs = getSharedPreferences("ypbg", Context.MODE_PRIVATE)
            if (prefs.getBoolean("askedBattery", false)) return
            prefs.edit().putBoolean("askedBattery", true).apply()
            AlertDialog.Builder(this)
                .setTitle("🔋 Lock screen fix — ek dafa ki setting")
                .setMessage(
                    "Gaana screen band hone par chalta rahe, is ke liye Android ko batana parta hai.\n\n" +
                        "\"Allow\" dabayein. Phir (Infinix / Redmi / Vivo me) Settings → Apps → is app ko " +
                        "\"No restrictions\" aur Autostart ON kar dein."
                )
                .setPositiveButton("Allow") { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    } catch (t: Throwable) {
                        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (t2: Throwable) {}
                    }
                }
                .setNegativeButton("Baad me", null)
                .show()
        } catch (t: Throwable) {}
    }

    override fun onDestroy() {
        try { NotifHub.setReplyTarget("fg", null) } catch (t: Throwable) {}
        /* v111-FIX (build 7): jab user app band kare (back se ya recents se) -> gaana bhi band.
           Screen lock / Home par isFinishing false hota hai, is liye wahan audio chalta rehta hai. */
        try {
            if (isFinishing) {
                // ACT2 hardening: swipe-away par leaked page (WebView + handlers) hi stale sync ka sabab tha.
                try { handoffHandler.removeCallbacksAndMessages(null) } catch (t: Throwable) {}
                try { mpvVideo.destroy() } catch (t: Throwable) {}
                try { val p = web.parent; if (p is android.view.ViewGroup) p.removeView(web); web.destroy() } catch (t: Throwable) {}
                try { NativeControl.applyCmd = null } catch (t: Throwable) {}   // ACT7: player gaya -> haath band
                try { NativePresence.onMessage = null } catch (t: Throwable) {}   // ACT6: page gaya -> kaan band
                try { NativePresence.stop(true) } catch (t: Throwable) {}   // ACT5: app khatam = sacha Left
                MusicService.stopNativeHard()
                MusicService.clearCaches()   // build 11: kaam khatam -> URLs/queue bhi RAM se saaf
                MusicService.stop(this)
                lastSnap = null
                lastQueueJson = null
            }
        } catch (t: Throwable) {}
        fullscreenControls?.release();fullscreenControls=null
        super.onDestroy()
    }

    @Deprecated("Handled below")
    override fun onBackPressed() {
        if (ytFsOn) {
            try { web.evaluateJavascript("window.__wpMpvFsSet && window.__wpMpvFsSet(0)", null) } catch (t: Throwable) {}
            exitYtFs()
            return
        }
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            return
        }
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}

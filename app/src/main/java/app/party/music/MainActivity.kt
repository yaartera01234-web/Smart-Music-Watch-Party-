package app.party.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        root.setBackgroundColor(Color.parseColor("#0d0716"))

        web = GifWebView(this)
        web.setBackgroundColor(Color.parseColor("#0d0716"))
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        // Branded, professional loading screen: icon + spinner + pulsing label.
        splash = LinearLayout(this)
        splash.orientation = LinearLayout.VERTICAL
        splash.gravity = Gravity.CENTER
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

        // v25 bridge: page se native player kholne ke liye (window.YaarNative.openPlayer)
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun openPlayer(videoUrl: String, title: String?) {
                runOnUiThread {
                    try {
                        startActivity(Intent(this@MainActivity, PlayerActivity::class.java).apply {
                            putExtra("url", videoUrl)
                            putExtra("title", title ?: "Video")
                        })
                    } catch (t: Throwable) {
                        showBanner("⚠️ Native player nahi khula")
                    }
                }
            }

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
            fun appVersion(): Int = 41
        }, "YaarNative")
        registerForegroundReplyTarget()

        // Gboard ka GIF/sticker seedha chat me: upload hoke page ke wpSendGif se chala jata hai.
        web.onGif = { gifUrl ->
            val safe = gifUrl.replace("\\", "").replace("'", "\\'")
            web.post { web.evaluateJavascript("window.wpSendGif && window.wpSendGif('" + safe + "')", null) }
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
                try { audio.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
                try { audio.isSpeakerphoneOn = false } catch (_: Throwable) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try { audio.clearCommunicationDevice() } catch (_: Throwable) {}
                }
                try { audio.abandonAudioFocus(null) } catch (_: Throwable) {}
                callAudioActive = false
                callOriginalDevice = null
                // Extra delayed force to release mic for WhatsApp - no crash
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val a2 = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        a2.mode = AudioManager.MODE_NORMAL
                        a2.isSpeakerphoneOn = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            try { a2.clearCommunicationDevice() } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }, 500)
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
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onStop() {
        super.onStop()
        if (!CallForegroundService.running) resetCallAudioRoute()
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

    private val JS_WATCH = "(function(){try{var v=document.getElementById('mp4-player');if(!v)return '';" +
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
        if (url.isBlank()) return
        pausePagePlayer()
        runOnUiThread {
            try {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra("url", url)
                    putExtra("title", title ?: "Video")
                    if (!alt.isNullOrBlank()) putExtra("alt", alt)
                })
            } catch (t: Throwable) { showBanner("\u26a0\ufe0f Native player nahi khula") }
        }
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

    override fun onDestroy() {
        try { NotifHub.setReplyTarget("fg", null) } catch (t: Throwable) {}
        super.onDestroy()
    }

    @Deprecated("Handled below")
    override fun onBackPressed() {
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            return
        }
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}

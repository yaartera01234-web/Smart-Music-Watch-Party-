package app.party.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.ByteArrayInputStream

/**
 * HYBRID FINAL - Boss start mein bola tha:
 * Design exact Smart-Music-Watch-Party APK (join screen, playlist, chat below player) - party-final1.html
 * Player exact Watch-Party-Mpv (MPV 0.3.0 purple #c026d3, 64dp play, contain/cover/16:9/4:3/Pan-Scan, Volume/Brightness purple sliders, Speed, Audio delay, Koi audio track nahi mili Off)
 * MPV ONLY, Premium player DISABLED, iframe NOT, YouTube 360p lock, MKV/MP4/M3U8 HQ original
 * Package app.smart.mpv.party side-by-side with original
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

    // MPV native button + auto-detect - ENABLED (was disabled in v38)
    private var nBtn: TextView? = null
    private var lastAutoUrl = ""

    private val bgHandler = Handler(Looper.getMainLooper())
    private val bgStarter = Runnable {
        try { BgNotifyService.start(this) } catch (t: Throwable) { Log.e("MPVParty", "bg start fail", t) }
    }

    private val url = "https://yaartera01234-web.github.io/watch-party/party-final1.html"

    private val AD_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "/pagead",
        "imasdk.googleapis.com", "googleads.g.", "googletagservices.com", "adservice.google.com"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingIncomingCallAction = parseIncomingCallAction(intent)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0d0716"))

        web = GifWebView(this)
        web.setBackgroundColor(Color.parseColor("#0d0716"))
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val logo = ImageView(this@MainActivity).apply { setImageDrawable(getDrawable(R.drawable.app_icon)) }
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
            val spin = ProgressBar(this@MainActivity)
            addView(spin, LinearLayout.LayoutParams(dp(34), dp(34)).apply { topMargin = dp(18) })
            val loading = TextView(this@MainActivity).apply {
                text = "MPV PARTY LOADING"; setTextColor(Color.parseColor("#c026d3")); textSize = 13f; letterSpacing = 0.3f
            }
            addView(loading, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
            loading.alpha = 0.4f
            loading.animate().setDuration(900).alpha(1f).withEndAction {
                loading.animate().setDuration(900).alpha(0.4f).withEndAction { pulse(loading) }
            }.start()
        }
        root.addView(splash, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; visibility = View.GONE }
        root.addView(status, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER })

        pipCover = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#12081f")); setTextColor(Color.parseColor("#c026d3"))
            textSize = 22f; gravity = Gravity.CENTER; text = "♪ MPV Party ON"; visibility = View.GONE
        }
        root.addView(pipCover, FrameLayout.LayoutParams(-1, -1))

        banner = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(18), dp(12), dp(18), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#e612081f")); setStroke(dp(1), Color.parseColor("#c026d3")); cornerRadius = dp(14).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(34) })

        // MPV NATIVE BUTTON - ENABLED NOW (boss said MPV only, Premium band)
        nBtn = TextView(this).apply {
            text = "⛶ MPV"; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#c026d3")) }
            visibility = View.GONE
            setOnClickListener { tapNative() }
        }
        root.addView(nBtn, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(12); rightMargin = dp(12) })

        setContentView(root)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true; cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val r = request ?: return null
                if (r.isForMainFrame) return null
                val host = r.url.host ?: ""; val full = r.url.toString()
                val blocked = AD_HOSTS.any { h -> if (h.startsWith("/")) full.contains(h) else host.contains(h) }
                if (!blocked) return null
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                splash.visibility = View.GONE
                pageLoaded = true
                nBtn?.visibility = View.VISIBLE
                // Inject JS to DISABLE Premium player, FORCE MPV ONLY
                injectMpvOnlyJs()
                dispatchIncomingCallAction()
                // Start auto-detect MPV
                bgHandler.postDelayed(watchMp4, 2000)
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
                customView = view; customViewCallback = callback
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                web.visibility = View.GONE
            }
            override fun onHideCustomView() {
                customView?.let { (it.parent as? FrameLayout)?.removeView(it) }
                customViewCallback?.onCustomViewHidden()
                customView = null; customViewCallback = null; web.visibility = View.VISIBLE
            }
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
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                showBanner(message ?: ""); result.confirm(); return true
            }
            override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                fileCallback?.onReceiveValue(null); fileCallback = callback
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
                return try { startActivityForResult(Intent.createChooser(pick, "Photo chunein"), 777); true }
                catch (t: Throwable) { fileCallback?.onReceiveValue(null); fileCallback = null; false }
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        }

        try { MusicService.start(this) } catch (t: Throwable) { Log.e("MPVParty", "service start failed", t) }

        // Bridge: page -> MPV PlayerActivity (MPV 0.3.0)
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun openPlayer(videoUrl: String, title: String?) {
                runOnUiThread {
                    try {
                        startActivity(Intent(this@MainActivity, PlayerActivity::class.java).apply {
                            putExtra("url", videoUrl); putExtra("title", title ?: "MPV Video")
                        })
                    } catch (t: Throwable) { showBanner("⚠️ MPV player nahi khula") }
                }
            }
            @JavascriptInterface fun notify(title: String?, text: String?) { postNote(title, text, null) }
            @JavascriptInterface fun notifyFrom(title: String?, text: String?, code: String?) {
                registerForegroundReplyTarget(); postNote(title, text, code)
            }
            @JavascriptInterface fun replyResult(requestId: String?, sent: Boolean) {
                if (!requestId.isNullOrEmpty()) NotifHub.completeReply(requestId, sent)
            }
            @JavascriptInterface fun setCallAudioRoute(speaker: Boolean) { this@MainActivity.setCallAudioRoute(speaker) }
            @JavascriptInterface fun resetCallAudioRoute() { this@MainActivity.resetCallAudioRoute() }
            @JavascriptInterface fun startOngoingCall(): Boolean {
                return try { CallForegroundService.start(this@MainActivity); true }
                catch (t: Throwable) { false }
            }
            @JavascriptInterface fun stopOngoingCall() { CallForegroundService.stop(this@MainActivity) }
            @JavascriptInterface fun showIncomingCall(caller: String?, callId: String?): Boolean {
                return try {
                    if (callId.isNullOrEmpty()) false else {
                        CallForegroundService.showIncoming(this@MainActivity, caller ?: "Private", callId); true
                    }
                } catch (t: Throwable) { false }
            }
            @JavascriptInterface fun clearIncomingCall() { CallForegroundService.stop(this@MainActivity) }
            @JavascriptInterface fun appVersion(): Int = 55
        }, "YaarNative")
        registerForegroundReplyTarget()

        web.onGif = { gifUrl ->
            val safe = gifUrl.replace("\\", "").replace("'", "\\'")
            web.post { web.evaluateJavascript("window.wpSendGif && window.wpSendGif('$safe')", null) }
        }
        web.onGifError = { msg -> showBanner(msg) }

        web.loadUrl(url + "?v=" + System.currentTimeMillis() + "&mpv=1")
    }

    private fun injectMpvOnlyJs() {
        // DISABLE Premium player, FORCE MPV ONLY - boss said MPV ka nam o nishan hi nahi tha, Premium hatao
        val js = """
            (function(){
                try{
                    // Hide premium video UI
                    var premium = document.querySelector('.premium-video-ui');
                    if(premium) premium.style.display='none';
                    // Override any premium play to call MPV
                    window.openPremiumPlayer = function(url,title){
                        try{ YaarNative.openPlayer(url, title||'MPV'); }catch(e){}
                    };
                    // If page tries to play via mp4-player, intercept and open MPV
                    var v = document.getElementById('mp4-player');
                    if(v){
                        v.addEventListener('play', function(e){
                            try{
                                var u = v.currentSrc||v.src||'';
                                if(u && u.indexOf('http')===0){
                                    e.preventDefault(); v.pause();
                                    YaarNative.openPlayer(u, document.getElementById('mini-title')?.textContent||'MPV');
                                }
                            }catch(x){}
                        });
                    }
                    // Show MPV badge
                    var badge = document.createElement('div');
                    badge.textContent='MPV 0.3.0 ONLY';
                    badge.style.cssText='position:fixed;top:8px;left:8px;background:#c026d3;color:white;padding:4px 8px;border-radius:6px;font-size:9px;font-weight:900;letter-spacing:1.2px;z-index:9999;';
                    document.body.appendChild(badge);
                    console.log('MPV ONLY MODE ACTIVE');
                }catch(e){}
            })();
        """.trimIndent()
        try { web.evaluateJavascript(js, null) } catch (t: Throwable) {}
    }

    private fun parseIncomingCallAction(source: Intent?): Pair<String, String>? {
        val method = when (source?.action) {
            CallForegroundService.ACTION_ANSWER_INCOMING -> "yaarAnswerIncomingCall"
            CallForegroundService.ACTION_DECLINE_INCOMING -> "yaarDeclineIncomingCall"
            else -> return null
        }
        try { (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(9043) } catch (_: Throwable) {}
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
        super.onNewIntent(intent); setIntent(intent)
        pendingIncomingCallAction = parseIncomingCallAction(intent); dispatchIncomingCallAction()
    }

    private fun registerForegroundReplyTarget() {
        try {
            NotifHub.setReplyTarget("fg") { code, text, requestId ->
                web.post {
                    try { web.evaluateJavascript(NotifHub.quickReplyJs(requestId, code, text), null) }
                    catch (t: Throwable) { NotifHub.completeReply(requestId, false) }
                }
            }
        } catch (t: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun setCallAudioRoute(speaker: Boolean) {
        runOnUiThread {
            try {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                if (!callAudioActive) {
                    callOriginalMode = audio.mode; callOriginalSpeaker = audio.isSpeakerphoneOn
                    callOriginalDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.communicationDevice else null
                    callAudioActive = true
                }
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audio.availableCommunicationDevices
                    val target = if (speaker) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    else callOriginalDevice?.takeIf { old -> old.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && devices.any { it.id == old.id } }
                        ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (target != null) audio.setCommunicationDevice(target)
                    else if (speaker) audio.isSpeakerphoneOn = true else audio.clearCommunicationDevice()
                } else { audio.isSpeakerphoneOn = speaker }
            } catch (t: Throwable) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun resetCallAudioRoute() {
        val restore = Runnable {
            try {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                try { audio.mode = AudioManager.MODE_NORMAL } catch (_: Throwable) {}
                try { audio.isSpeakerphoneOn = false } catch (_: Throwable) {}
                try { audio.isMicrophoneMute = false } catch (_: Throwable) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { try { audio.clearCommunicationDevice() } catch (_: Throwable) {} }
                try { audio.abandonAudioFocus(null) } catch (_: Throwable) {}
                callAudioActive = false; callOriginalDevice = null
            } catch (t: Throwable) { callAudioActive = false; callOriginalDevice = null }
        }
        try { if (Looper.myLooper() == Looper.getMainLooper()) restore.run() else runOnUiThread(restore) }
        catch (_: Throwable) { callAudioActive = false; callOriginalDevice = null }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            val r = pendingWebPerm; pendingWebPerm = null; if (r == null) return
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else { r.deny(); showBanner("🎤 Mic ki ijazat nahi mili") }
        }
    }

    private fun showBanner(message: String) {
        if (!::banner.isInitialized) return
        banner.removeCallbacks(hideBanner); banner.text = message; banner.visibility = View.VISIBLE
        banner.postDelayed(hideBanner, 2600)
    }
    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private fun pulse(v: View) { v.animate().setDuration(900).alpha(1f).withEndAction { v.animate().setDuration(900).alpha(0.4f).withEndAction { pulse(v) } }.start() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 777) {
            val res = if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null
            fileCallback?.onReceiveValue(res); fileCallback = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (CallForegroundService.running) return
        if (Build.VERSION.SDK_INT >= 26 && customView == null) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(1, 1)).build()) }
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
        if (resumed && !BgNotifyService.noteMuted && BgNotifyService.running) {
            try { BgNotifyService.stop(this) } catch (t: Throwable) {}
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            bgHandler.removeCallbacks(bgStarter); bgHandler.removeCallbacks(stopBgIfForeground)
            if (!BgNotifyService.noteMuted) bgHandler.postDelayed(stopBgIfForeground, 2000L)
        } catch (t: Throwable) {}
    }

    override fun onResume() {
        super.onResume(); resumed = true; web.onResume()
        if (!CallForegroundService.running) { try { resetCallAudioRoute() } catch (_: Throwable) {} }
    }

    override fun onPause() { super.onPause(); resumed = false }

    override fun onStop() {
        super.onStop()
        if (!CallForegroundService.running) resetCallAudioRoute()
        try { bgHandler.removeCallbacks(stopBgIfForeground) } catch (t: Throwable) {}
        web.onResume(); web.resumeTimers()
        if (CallForegroundService.running) return
        if (!isChangingConfigurations) {
            try { bgHandler.removeCallbacks(bgStarter); bgHandler.post(bgStarter) } catch (t: Throwable) {}
        }
    }

    // MPV auto-detect - watches mp4-player and opens MPV, pauses Premium
    private val JS_WATCH = "(function(){try{var v=document.getElementById('mp4-player');if(!v)return '';var u=v.currentSrc||v.src||'';if((v.className||'').indexOf('hidden')>=0)return '';if(u.indexOf('http')!==0&&u.indexOf('blob:')!==0)return '';return u;}catch(e){return '';}})()"
    private val JS_GET = "(function(){try{var v=document.getElementById('mp4-player');var u=v?(v.currentSrc||v.src||''):'';var t=document.getElementById('mini-title');return (u||'')+'\\u0001'+(t?(t.textContent||''):'');}catch(e){return '';}})()"

    private fun jstr(res: String?): String {
        if (res == null || res == "null") return ""
        return try { org.json.JSONObject("{\"v\":$res}").getString("v") } catch (t: Throwable) { "" }
    }

    private fun pausePagePlayer() {
        try {
            web.evaluateJavascript("(function(){try{if(typeof suppressMP4!=='undefined')suppressMP4=true;}catch(e){}try{var v=document.getElementById('mp4-player');if(v)v.pause();}catch(e){}})()", null)
        } catch (t: Throwable) {}
    }

    private fun openNative(url: String, title: String?, alt: String? = null) {
        if (url.isBlank()) return
        pausePagePlayer()
        runOnUiThread {
            try {
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    putExtra("url", url); putExtra("title", title ?: "MPV Video")
                    if (!alt.isNullOrBlank()) putExtra("alt", alt)
                })
            } catch (t: Throwable) { showBanner("⚠️ MPV player nahi khula") }
        }
    }

    private fun tapNative() {
        try {
            web.evaluateJavascript(JS_GET) { res ->
                val parts = jstr(res).split('\u0001')
                val url = parts.getOrNull(0)?.trim() ?: ""
                val title = parts.getOrNull(1)?.trim() ?: "MPV Video"
                if (url.startsWith("http") || url.startsWith("blob:")) openNative(url, title)
                else showBanner("⚠️ Pehle koi direct video link lagao (mp4 / m3u8)")
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

    private fun postNote(title: String?, text: String?, code: String?) {
        if (resumed) return
        NotifHub.post(this, title, text, "fg", code)
    }

    override fun onDestroy() { try { NotifHub.setReplyTarget("fg", null) } catch (t: Throwable) {}; super.onDestroy() }

    @Deprecated("Handled below")
    override fun onBackPressed() {
        if (customView != null) { customViewCallback?.onCustomViewHidden(); return }
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}

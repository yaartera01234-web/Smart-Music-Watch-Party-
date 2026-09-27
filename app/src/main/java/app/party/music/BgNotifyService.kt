package app.party.music

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * v27: App poora band / swipe away hone ke baad bhi DM notification.
 *
 * Bina Firebase yehi ek saaf rasta hai: ek foreground service jo andar ek
 * CHHUPA WebView (?bg=1) zinda rakhti hai. Us page me sirf DM module chalta hai
 * (party room join nahi hota) — wahi page MQTT se judta hai aur naya message
 * aane pe YaarNative.notify() call karta hai.
 *
 * Page (`?bg=1`) 60 second me ek dafa bgPing() bhejta hai. 5 minute tak koi ping
 * na aaye to WebView reload ho jata hai (self-heal).
 */
class BgNotifyService : Service() {

    companion object {
        private const val TAG = "MusicParty"
        private const val NOTE_ID = 4242
        private const val URL = "https://yaartera01234-web.github.io/watch-party/party-final1.html?bg=1"

        @Volatile var running = false

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, BgNotifyService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (t: Throwable) {
                Log.e(TAG, "bg start fail", t)
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, BgNotifyService::class.java)) } catch (t: Throwable) {}
        }
    }

    private var web: WebView? = null
    private var startedAt = 0L
    private var lastPing = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                val now = System.currentTimeMillis()
                if (now - lastPing > 5 * 60 * 1000L) {
                    lastPing = now
                    Log.w(TAG, "bg ping nahi aaya -> reload")
                    web?.reload()
                }
            } catch (t: Throwable) {}
            handler.postDelayed(this, 60000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        startedAt = System.currentTimeMillis()
        lastPing = startedAt
        try {
            startForeground(NOTE_ID, NotifHub.serviceNote(this))
        } catch (t: Throwable) {
            Log.e(TAG, "foreground fail", t)
        }
        try {
            val w = WebView(this)
            val st: WebSettings = w.settings
            st.javaScriptEnabled = true
            st.domStorageEnabled = true
            st.databaseEnabled = true
            st.cacheMode = WebSettings.LOAD_DEFAULT
            st.mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= 21) {
                st.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            w.addJavascriptInterface(BgBridge(), "YaarNative")
            w.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(v: WebView?, u: String?, msg: String?, r: JsResult): Boolean { r.cancel(); return true }
                override fun onJsConfirm(v: WebView?, u: String?, msg: String?, r: JsResult): Boolean { r.cancel(); return true }
                override fun onJsPrompt(v: WebView?, u: String?, msg: String?, d: String?, r: JsPromptResult): Boolean { r.cancel(); return true }
            }
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    lastPing = System.currentTimeMillis()
                    try {
                        view?.evaluateJavascript(
                            "(function(){try{window.YaarNative&&window.YaarNative.bgReady&&window.YaarNative.bgReady();}catch(e){}})()",
                            null
                        )
                    } catch (t: Throwable) {}
                }
            }
            w.loadUrl(URL + "&rc=" + System.currentTimeMillis())
            web = w
        } catch (t: Throwable) {
            Log.e(TAG, "webview fail", t)
        }
        handler.postDelayed(watchdog, 90000)
    }

    inner class BgBridge {
        @android.webkit.JavascriptInterface
        fun notify(title: String?, text: String?) {
            lastPing = System.currentTimeMillis()
            NotifHub.post(this@BgNotifyService, title, text, "bg")
        }

        @android.webkit.JavascriptInterface
        fun bgReady() { lastPing = System.currentTimeMillis() }

        @android.webkit.JavascriptInterface
        fun bgPing() { lastPing = System.currentTimeMillis() }

        @android.webkit.JavascriptInterface
        fun appVersion(): Int = 34
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { startForeground(NOTE_ID, NotifHub.serviceNote(this)) } catch (t: Throwable) {}
        return START_STICKY
    }

    /** Recents se swipe away karne pe bhi chalta rahe. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 2000)
        } catch (t: Throwable) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        try { handler.removeCallbacksAndMessages(null) } catch (t: Throwable) {}
        try {
            web?.stopLoading()
            web?.destroy()
            web = null
        } catch (t: Throwable) {}
        super.onDestroy()
    }
}

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
        const val NOTE_GONE = "app.party.music.NOTE_GONE"
        private const val URL = "https://yaartera01234-web.github.io/watch-party/party-final1.html?bg=1"

        @Volatile var running = false

        /* v39: user ne "Messages on" note swipe kar diya -> usay dobara pareshan na karo */
        @Volatile var noteMuted = false

        fun prefs(ctx: Context) = ctx.getSharedPreferences("ypbg", Context.MODE_PRIVATE)

        fun start(ctx: Context) {
            val i = Intent(ctx, BgNotifyService::class.java)
            /* service pehle se chal rahi hai (note muted) -> startService hi kaafi hai.
               startForegroundService karne pe Android naya note dikhane pe majboor karta hai. */
            if (running) {
                try { ctx.startService(i); return } catch (t: Throwable) {}
            }
            try {
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
    private var notePosted = false          /* v39: ek hi dafa note post karo */
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
        noteMuted = try { prefs(this).getBoolean("noteMuted", false) } catch (t: Throwable) { false }
        try {
            startForeground(NOTE_ID, NotifHub.serviceNote(this))
            notePosted = true
        } catch (t: Throwable) {
            Log.e(TAG, "foreground fail", t)
        }
        /* v39: user ne pehle note hata diya tha -> chup-chaap dobara ghayab kar do */
        if (noteMuted) {
            handler.postDelayed({
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.cancel(NOTE_ID)
                } catch (t: Throwable) {}
            }, 600)
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

        /* v40: notification me "Reply" button -> jo likha jaye wo isi page se jaye */
        @android.webkit.JavascriptInterface
        fun notifyFrom(title: String?, text: String?, code: String?) {
            lastPing = System.currentTimeMillis()
            NotifHub.setReplyTarget { c, t ->
                web?.post {
                    try { web?.evaluateJavascript(NotifHub.quickReplyJs(c, t), null) } catch (e: Throwable) {}
                }
            }
            NotifHub.post(this@BgNotifyService, title, text, "bg", code)
        }

        @android.webkit.JavascriptInterface
        fun bgReady() { lastPing = System.currentTimeMillis() }

        @android.webkit.JavascriptInterface
        fun bgPing() { lastPing = System.currentTimeMillis() }

        @android.webkit.JavascriptInterface
        fun appVersion(): Int = 40
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /* v39: user ne note swipe kar diya -> yaad rakho, dobara note nahi */
        if (intent != null && NOTE_GONE == intent.action) {
            noteMuted = true
            try { prefs(this).edit().putBoolean("noteMuted", true).apply() } catch (t: Throwable) {}
            return START_STICKY
        }
        if (!notePosted) {
            try { startForeground(NOTE_ID, NotifHub.serviceNote(this)); notePosted = true } catch (t: Throwable) {}
        }
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
        try { NotifHub.setReplyTarget(null) } catch (t: Throwable) {}
        try { handler.removeCallbacksAndMessages(null) } catch (t: Throwable) {}
        try {
            web?.stopLoading()
            web?.destroy()
            web = null
        } catch (t: Throwable) {}
        super.onDestroy()
    }
}

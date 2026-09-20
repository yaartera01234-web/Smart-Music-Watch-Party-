package app.party.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Hosts the watch-party web app in a WebView that is deliberately never paused, so the party
 * (YouTube iframe or MP3 audio) keeps sounding while the app is backgrounded or the screen is
 * off. A foreground media service + wake lock keep the process and CPU alive; Android treats us
 * like a music app.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val url = "https://yaartera01234-web.github.io/watch-party/party-final1.html"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0d0716"))

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0d0716"))
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        bar = ProgressBar(this)
        val bp = FrameLayout.LayoutParams(-2, -2)
        bp.gravity = android.view.Gravity.CENTER
        root.addView(bar, bp)

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.text = "Party load ho rahi hai..."
        status.textSize = 16f
        val sp = FrameLayout.LayoutParams(-2, -2)
        sp.gravity = android.view.Gravity.CENTER
        sp.topMargin = 120
        root.addView(status, sp)

        setContentView(root, FrameLayout.LayoutParams(-1, -1))

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                bar.visibility = View.GONE
                status.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    bar.visibility = View.GONE
                    status.text = "Page load nahi hui — internet check karein.\n(${error?.description})"
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                if (p >= 90) bar.visibility = View.GONE
            }

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
        }
        WebView.setWebContentsDebuggingEnabled(true)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        // Crash-safe: even if the service fails on some OEM Android, the app itself must open.
        try {
            MusicService.start(this)
        } catch (t: Throwable) {
            Log.e("MusicParty", "service start failed", t)
        }

        web.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    // NOTE: onPause() intentionally does NOT call web.onPause() — background audio must keep flowing.

    override fun onStop() {
        super.onStop()
        // The moment the activity hides, Chromium starts winding the WebView down (timers
        // throttled, media pipeline idled). Force it back to the live state so the party keeps
        // sounding from the background; the foreground service + wake lock keep the process alive.
        web.onResume()
        web.resumeTimers()
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

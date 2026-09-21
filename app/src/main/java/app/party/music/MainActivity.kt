package app.party.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
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
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var pipCover: TextView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val url = "https://yaartera01234-web.github.io/watch-party/party-final1.html"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0d0716"))
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        bar = ProgressBar(this)
        val bp = FrameLayout.LayoutParams(-2, -2)
        bp.gravity = Gravity.CENTER
        root.addView(bar, bp)

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 14f
        val sp = FrameLayout.LayoutParams(-2, -2)
        sp.gravity = Gravity.CENTER
        sp.topMargin = 120
        root.addView(status, sp)

        // Dark music bubble shown only inside PiP (the page's own UI reads like a video call).
        pipCover = TextView(this)
        pipCover.setBackgroundColor(Color.parseColor("#12081f"))
        pipCover.setTextColor(Color.parseColor("#ff5fa2"))
        pipCover.textSize = 22f
        pipCover.gravity = Gravity.CENTER
        pipCover.text = "♪ Party ON"
        pipCover.visibility = View.GONE
        root.addView(pipCover, FrameLayout.LayoutParams(-1, -1))

        setContentView(root, FrameLayout.LayoutParams(-1, -1))

        val crash = runCatching { getFileStreamPath("crash.txt").takeIf { it.exists() }?.readText() }.getOrNull()
        if (crash != null) {
            status.text = "CRASH REPORT (screenshot le lein):\n${crash.take(500)}"
            getFileStreamPath("crash.txt").delete()
            status.postDelayed({ status.visibility = View.GONE }, 8000)
        } else {
            status.text = "Party load ho rahi hai..."
        }

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
            // Desktop Chrome identity — the exact combo that tested working (v10): YouTube played
            // without the sign-in wall on it.
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                bar.visibility = View.GONE
                status.postDelayed({ status.visibility = View.GONE }, if (crash != null) 8000 else 0)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    bar.visibility = View.GONE
                    status.text = "Page load nahi hui — internet check karein."
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

        try {
            MusicService.start(this)
        } catch (t: Throwable) {
            Log.e("MusicParty", "service start failed", t)
        }

        web.loadUrl(url)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
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
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onStop() {
        super.onStop()
        web.onResume()
        web.resumeTimers()
    }

    // NOTE: onPause() intentionally does NOT call web.onPause() — background audio must keep flowing.

    @Deprecated("Handled below")
    override fun onBackPressed() {
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            return
        }
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}

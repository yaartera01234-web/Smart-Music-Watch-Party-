package app.party.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var splash: LinearLayout
    private lateinit var status: TextView
    private lateinit var pipCover: TextView
    private lateinit var banner: TextView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<android.net.Uri>>? = null

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

        setContentView(root, FrameLayout.LayoutParams(-1, -1))

        val crash = runCatching { getFileStreamPath("crash.txt").takeIf { it.exists() }?.readText() }.getOrNull()
        if (crash != null) {
            status.text = "CRASH REPORT (screenshot le lein):\n${crash.take(500)}"
            status.visibility = View.VISIBLE
            getFileStreamPath("crash.txt").delete()
            status.postDelayed({ status.visibility = View.GONE }, 8000)
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
            // Desktop Chrome identity — the exact combo that tested working (v10/v15): YouTube
            // played without the sign-in wall on it.
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                splash.visibility = View.GONE
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

        try {
            MusicService.start(this)
        } catch (t: Throwable) {
            Log.e("MusicParty", "service start failed", t)
        }

        web.loadUrl(url)
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

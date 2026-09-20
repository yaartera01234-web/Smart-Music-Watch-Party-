package app.party.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
 * Hosts the watch-party web app in a WebView that must keep sounding in the background.
 *
 * Chromium idles a WebView's media pipeline as soon as the hosting activity hides, so this
 * activity re-parents the live WebView into a system overlay window (1px, invisible) whenever
 * the app backgrounds: the WebView then stays "visible" to Chromium forever, and the foreground
 * service + wake lock keep the process and CPU alive. Without the overlay permission it falls
 * back to the force-resume tricks.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var inOverlay = false

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val url = "https://yaartera01234-web.github.io/watch-party/party-final1.html"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        status.text = "Party load ho rahi hai..."
        status.textSize = 16f
        val sp = FrameLayout.LayoutParams(-2, -2)
        sp.gravity = Gravity.CENTER
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

        // The overlay permission is what makes true background playback possible.
        if (!Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        try {
            MusicService.start(this)
        } catch (t: Throwable) {
            Log.e("MusicParty", "service start failed", t)
        }

        web.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        if (inOverlay) {
            runCatching { wm.removeView(web) }
            inOverlay = false
            if (web.parent == null) root.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
        }
        web.onResume()
    }

    override fun onStop() {
        super.onStop()
        // Chromium idles hidden WebViews; an overlay window keeps this one "visible" forever.
        if (Settings.canDrawOverlays(this) && !inOverlay) {
            (web.parent as? ViewGroup)?.removeView(web)
            try {
                wm.addView(web, overlayParams())
                inOverlay = true
            } catch (t: Throwable) {
                Log.e("MusicParty", "overlay failed", t)
                if (web.parent == null) root.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
            }
        }
        web.onResume()
        web.resumeTimers()
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.01f
        }
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

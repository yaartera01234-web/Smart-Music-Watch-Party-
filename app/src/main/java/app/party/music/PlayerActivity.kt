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
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v26: Native Player (Media3) + naye controls.
 *
 *  - Left side pe ungli se UPAR/NEEche -> BRIGHTNESS (chamak)
 *  - Right side pe ungli se UPAR/NEEche -> VOLUME
 *  - Upar-daayen button: Fit / Fill / Zoom (crop) — yaad bhi rehta hai
 *  - HUD indicator beech me dikhta hai (☀ % / 🔊)
 *  - Background me chalta rehta hai + notification me controls (PlaybackService)
 */
@UnstableApi
class PlayerActivity : Activity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var note: TextView? = null
    private var pv: PlayerView? = null
    private var modeBtn: TextView? = null

    private var hud: LinearLayout? = null
    private var hudText: TextView? = null
    private var hudBar: ProgressBar? = null

    private lateinit var am: AudioManager
    private lateinit var prefs: SharedPreferences
    private val hideRunnable = Runnable { hud?.visibility = View.GONE }
    private var maxVol = 15

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        prefs = getSharedPreferences("yp_player", Context.MODE_PRIVATE)
        modeIdx = prefs.getInt("mode", 0).let { if (it < 0 || it > 2) 0 else it }

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())

        val playerView = PlayerView(this)
        playerView.useController = true
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.resizeMode = modes[modeIdx]
        pv = playerView
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        note = TextView(this).apply {
            setTextColor(0xFFFF5FA2.toInt())
            textSize = 13f
            visibility = View.GONE
        }
        root.addView(note, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = 40; topMargin = 60 })

        /* ---- crop button (Fit / Fill / Zoom) ---- */
        modeBtn = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0x99000000.toInt())
            text = modeIcons[modeIdx] + "  " + modeNames[modeIdx]
            setOnClickListener { cycleMode() }
        }
        root.addView(modeBtn, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(16)
            rightMargin = dp(16)
        })

        /* ---- HUD (brightness / volume indicator) ---- */
        hudText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(0, 0, dp(12), 0)
        }
        hudBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 50
        }
        hud = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            addView(hudText, LinearLayout.LayoutParams(-2, -2))
            addView(hudBar, LinearLayout.LayoutParams(dp(220), dp(10)))
        }
        root.addView(hud, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER })

        setContentView(root)

        val url = intent.getStringExtra("url")
        val title = intent.getStringExtra("title") ?: "Video"
        val pos = intent.getLongExtra("pos", 0L)
        if (url.isNullOrBlank()) { finish(); return }

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                playerView.player = c
                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                    .build()
                c.setMediaItem(item, pos)
                c.prepare()
                c.play()
                c.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        showNote("\u26a0\ufe0f Ye link native player me nahi chala \u2014 page wale player se try karo")
                    }
                })
            } catch (t: Throwable) {
                showNote("\u26a0\ufe0f Player start nahi hua")
            }
        }, MoreExecutors.directExecutor())
    }

    /* ---------------- crop mode ---------------- */

    private fun cycleMode() {
        modeIdx = (modeIdx + 1) % modes.size
        pv?.resizeMode = modes[modeIdx]
        modeBtn?.text = modeIcons[modeIdx] + "  " + modeNames[modeIdx]
        try { prefs.edit().putInt("mode", modeIdx).apply() } catch (e: Throwable) {}
        showHud("\u26f6", (modeIdx + 1) * 33, modeNames[modeIdx])
    }

    /* ---------------- brightness + volume gestures ---------------- */

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
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
                        gStartVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
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
                        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0) } catch (e: Throwable) {}
                        val percent = ((nv * 100f) / maxVol).roundToInt()
                        showHud("\U0001f50a", percent, "$nv/$maxVol")
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
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
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

    override fun onStop() {
        super.onStop()
        // Background me chalta rehna hai -> controller chhod do, service player chala rahi hai.
        controllerFuture?.let { runCatching { MediaController.releaseFuture(it) } }
        controllerFuture = null
        controller = null
    }
}

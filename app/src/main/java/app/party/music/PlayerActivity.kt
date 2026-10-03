package app.party.music

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlinx.coroutines.*
import org.schabi.newpipe.extractor.ServiceList
import io.github.yuroyami.libmpvkt.view.MpvView
import io.github.yuroyami.libmpvkt.MpvOptions

class PlayerActivity : Activity() {
    private var mpvView: MpvView? = null
    private var root: FrameLayout? = null
    private var titleView: TextView? = null
    private var timeView: TextView? = null
    private var playBtn: TextView? = null
    private var centerPlay: FrameLayout? = null
    private var audioPanel: LinearLayout? = null
    private var settingsPanel: LinearLayout? = null
    private var curUrl = ""
    private var curTitle = "t3gj68ev41sa"
    private var isPlaying = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        curUrl = intent.getStringExtra("url") ?: ""
        curTitle = intent.getStringExtra("title") ?: "t3gj68ev41sa"
        if (curUrl.isBlank()) { finish(); return }
        buildUi()
        // New API: MpvView initialize with MpvOptions
        try {
            mpvView?.initialize(MpvOptions())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        playUrl(curUrl)
    }

    private fun buildUi() {
        val r = FrameLayout(this)
        r.setBackgroundColor(Color.BLACK)
        root = r
        val mpv = MpvView(this)
        mpvView = mpv
        r.addView(mpv, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val backBtn = TextView(this).apply { text = "⤢⤡"; setTextColor(Color.WHITE); textSize = 18f; setBackgroundColor(Color.parseColor("#2a2a2a")); setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { finish() } }
        top.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply { text = "🎬 $curTitle"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD) }
        titleView = t
        val tm = TextView(this).apply { text = "0:00 / 0:00"; setTextColor(Color.parseColor("#aaaaaa")); textSize = 12f }
        timeView = tm
        titleCol.addView(t); titleCol.addView(tm)
        top.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        val pipBtn = TextView(this).apply { text = "⧉"; setTextColor(Color.WHITE); textSize = 18f; setBackgroundColor(Color.parseColor("#2a2a2a")); setPadding(dp(10), dp(8), dp(10), dp(8)) }
        top.addView(pipBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        r.addView(top, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP })

        val center = FrameLayout(this).apply {
            val inner = TextView(this@PlayerActivity).apply { text = "▶"; setTextColor(Color.WHITE); textSize = 32f; gravity = Gravity.CENTER }
            addView(inner, FrameLayout.LayoutParams(-1, -1))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(1), Color.parseColor("#ffffff1a")) }
        }
        centerPlay = center
        center.setOnClickListener { togglePlay() }
        r.addView(center, FrameLayout.LayoutParams(dp(110), dp(110)).apply { gravity = Gravity.CENTER })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        val prog = SeekBar(this).apply { max = 1000; progress = 0; thumb = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(18), dp(18)) } }
        bottom.addView(prog, LinearLayout.LayoutParams(-1, dp(24)))
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tl = TextView(this).apply { text = "0:00"; setTextColor(Color.parseColor("#aaaaaa")); textSize = 12f }
        val tr = TextView(this).apply { text = "-0:00"; setTextColor(Color.parseColor("#aaaaaa")); textSize = 12f; gravity = Gravity.END }
        timeRow.addView(tl, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(tr, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val ctrlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun makeCtrl(txt: String, purple: Boolean = false): TextView {
            return TextView(this@PlayerActivity).apply {
                text = txt; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (purple) Color.parseColor("#c026d3") else Color.parseColor("#2a2a2a")) }
            }
        }
        val rew = makeCtrl("↻")
        val play = makeCtrl("▶", true).apply { setPadding(dp(18), dp(14), dp(18), dp(14)); textSize = 22f }
        playBtn = play
        play.setOnClickListener { togglePlay() }
        val fwd = makeCtrl("↺")
        val audio = makeCtrl("〰️").apply { setOnClickListener { audioPanel?.visibility = View.VISIBLE; settingsPanel?.visibility = View.GONE } }
        val settings = makeCtrl("⚙️").apply { setOnClickListener { settingsPanel?.visibility = View.VISIBLE; audioPanel?.visibility = View.GONE } }
        val fs = makeCtrl("⤢").apply { setOnClickListener { finish() } }
        ctrlRow.addView(rew, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        ctrlRow.addView(play, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(8) })
        ctrlRow.addView(fwd, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(16) })
        ctrlRow.addView(audio, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        ctrlRow.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        ctrlRow.addView(fs, LinearLayout.LayoutParams(dp(48), dp(48)))
        bottom.addView(ctrlRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); gravity = Gravity.CENTER })
        r.addView(bottom, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM })

        // Audio & Subtitles Panel - exact Watch-Party-Mpv clone 55% width #0a0a0a
        val ap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0a0a0a")); setPadding(dp(16), dp(14), dp(16), dp(14)); visibility = View.GONE; elevation = dp(10).toFloat() }
        val apTitle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val apIcon = TextView(this).apply { text = "〰️"; setTextColor(Color.parseColor("#c026d3")); textSize = 18f }
        val apTxt = TextView(this).apply { text = "Audio & Subtitles"; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(8),0,0,0) }
        val apClose = TextView(this).apply { text = "✕"; setTextColor(Color.WHITE); textSize = 16f; setBackgroundColor(Color.parseColor("#2a2a2a")); setPadding(dp(10), dp(6), dp(10), dp(6)); gravity = Gravity.CENTER; setOnClickListener { ap.visibility = View.GONE } }
        apTitle.addView(apIcon); apTitle.addView(apTxt, LinearLayout.LayoutParams(0, -2, 1f)); apTitle.addView(apClose, LinearLayout.LayoutParams(dp(36), dp(36)))
        ap.addView(apTitle)
        ap.addView(TextView(this).apply { text = "〰️ AUDIO TRACK (DUAL AUDIO)"; setTextColor(Color.parseColor("#888888")); textSize = 11f; setPadding(0, dp(18), 0, dp(8)) })
        ap.addView(TextView(this).apply { text = "Koi audio track list nahi mili"; setTextColor(Color.parseColor("#aaaaaa")); textSize = 13f })
        ap.addView(TextView(this).apply { text = "📄 SUBTITLES"; setTextColor(Color.parseColor("#888888")); textSize = 11f; setPadding(0, dp(18), 0, dp(8)) })
        ap.addView(TextView(this).apply { text = "Off"; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(16), dp(12), dp(16), dp(12)); background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#2a0a2a")); setStroke(dp(1), Color.parseColor("#c026d3")) } }, LinearLayout.LayoutParams(-1, dp(48)))
        fun addSlider(label: String, value: String): LinearLayout {
            val lay = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, 0) }
            val lbl = TextView(this@PlayerActivity).apply { text = "$label · $value"; setTextColor(Color.parseColor("#cccccc")); textSize = 13f }
            val track = FrameLayout(this@PlayerActivity).apply {
                val bg = View(this@PlayerActivity).apply { setBackgroundColor(Color.parseColor("#333333")) }
                addView(bg, FrameLayout.LayoutParams(-1, dp(6)).apply { gravity = Gravity.CENTER_VERTICAL })
                val fill = View(this@PlayerActivity).apply { setBackgroundColor(Color.parseColor("#c026d3")) }
                addView(fill, FrameLayout.LayoutParams(dp(120), dp(6)).apply { gravity = Gravity.CENTER_VERTICAL })
                val dot = View(this@PlayerActivity).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor("#c026d3")); setSize(dp(18), dp(18)) } }
                addView(dot, FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER_VERTICAL; leftMargin = dp(120) })
            }
            lay.addView(lbl); lay.addView(track, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(8) })
            return lay
        }
        ap.addView(addSlider("Sub size", "1.0x"))
        ap.addView(addSlider("Sub delay", "0.0s"))
        ap.addView(addSlider("Audio delay", "0.0s"))
        audioPanel = ap
        r.addView(ap, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.55).toInt(), -1).apply { gravity = Gravity.END })

        // Player Settings Panel - VOLUME 100% + BRIGHTNESS 100% purple #c026d3 + SPEED + ASPECT
        val sp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0a0a0a")); setPadding(dp(16), dp(14), dp(16), dp(14)); visibility = View.GONE; elevation = dp(10).toFloat() }
        val spTitle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val spIcon = TextView(this).apply { text = "⚙️"; setTextColor(Color.parseColor("#c026d3")); textSize = 18f }
        val spTxt = TextView(this).apply { text = "Player Settings"; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(8),0,0,0) }
        val spClose = TextView(this).apply { text = "✕"; setTextColor(Color.WHITE); textSize = 16f; setBackgroundColor(Color.parseColor("#2a2a2a")); setPadding(dp(10), dp(6), dp(10), dp(6)); gravity = Gravity.CENTER; setOnClickListener { sp.visibility = View.GONE } }
        spTitle.addView(spIcon); spTitle.addView(spTxt, LinearLayout.LayoutParams(0, -2, 1f)); spTitle.addView(spClose, LinearLayout.LayoutParams(dp(36), dp(36)))
        sp.addView(spTitle)
        sp.addView(addSlider("🔊 VOLUME", "100%"))
        sp.addView(View(this).apply { setBackgroundColor(Color.parseColor("#222222")) }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(10) })
        sp.addView(addSlider("☀️ BRIGHTNESS", "100%"))
        val speedLabel = TextView(this).apply { text = "⏱ SPEED"; setTextColor(Color.parseColor("#888888")); textSize = 11f; setPadding(0, dp(18), 0, dp(8)) }
        sp.addView(speedLabel)
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun speedBtn(txt: String, active: Boolean): TextView {
            return TextView(this@PlayerActivity).apply { text = txt; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8)); background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (active) Color.parseColor("#c026d3") else Color.parseColor("#2a2a2a")) }; setOnClickListener { setSpeed(txt) } }
        }
        speedRow.addView(speedBtn("0.5x", false), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        speedRow.addView(speedBtn("0.75x", false), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        speedRow.addView(speedBtn("1x", true), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        speedRow.addView(speedBtn("1.25x", false), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        speedRow.addView(speedBtn("1.5x", false), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        speedRow.addView(speedBtn("2x", false), LinearLayout.LayoutParams(-2, -2))
        sp.addView(speedRow)
        val aspectLabel = TextView(this).apply { text = "⧉ ASPECT"; setTextColor(Color.parseColor("#888888")); textSize = 11f; setPadding(0, dp(18), 0, dp(8)) }
        sp.addView(aspectLabel)
        val aspectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun aspectBtn(txt: String, active: Boolean): TextView {
            return TextView(this@PlayerActivity).apply { text = txt; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (active) Color.parseColor("#c026d3") else Color.parseColor("#2a2a2a")) }; setOnClickListener { setAspect(txt); sp.visibility = View.GONE } }
        }
        aspectRow.addView(aspectBtn("contain", true), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        aspectRow.addView(aspectBtn("cover", false), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        aspectRow.addView(aspectBtn("16/9", false), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        aspectRow.addView(aspectBtn("4/3", false), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        aspectRow.addView(aspectBtn("Pan/Scan", false), LinearLayout.LayoutParams(-2, -2))
        sp.addView(aspectRow)
        settingsPanel = sp
        r.addView(sp, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.55).toInt(), -1).apply { gravity = Gravity.END })

        setContentView(r)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { it.hide(android.view.WindowInsets.Type.systemBars()); it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun playUrl(url: String) {
        scope.launch {
            var finalUrl = url
            if (url.contains("youtube") || url.contains("youtu.be")) {
                finalUrl = extractYoutube360p(url) ?: url
            }
            withContext(Dispatchers.Main) {
                mpvView?.playFile(finalUrl)
                isPlaying = true
                playBtn?.text = "⏸"
                centerPlay?.visibility = View.GONE
            }
        }
    }

    private suspend fun extractYoutube360p(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val service = ServiceList.YouTube
            val linkHandler = service.streamLHFactory.fromUrl(url)
            val extractor = service.getStreamExtractor(linkHandler)
            extractor.fetchPage()
            val videoStreams = extractor.videoStreams
            val best360 = videoStreams?.filter { !it.url.isNullOrEmpty() && !it.isVideoOnly }?.sortedBy { kotlin.math.abs((it.height ?: 360) - 360) }?.firstOrNull { it.height in 300..400 } ?: videoStreams?.firstOrNull { !it.url.isNullOrEmpty() && it.height <= 360 }
            return@withContext best360?.url
        } catch (e: Exception) { null }
    }

    private fun togglePlay() {
        val mpv = mpvView?.mpv
        if (isPlaying) {
            mpv?.setString("pause", "yes")
            isPlaying = false; playBtn?.text = "▶"; centerPlay?.visibility = View.VISIBLE
        } else {
            mpv?.setString("pause", "no")
            isPlaying = true; playBtn?.text = "⏸"; centerPlay?.visibility = View.GONE
        }
    }

    private fun setAspect(mode: String) {
        try {
            val mpv = mpvView?.mpv
            when (mode) {
                "contain" -> { mpv?.setString("video-aspect-override", "no"); mpv?.setString("panscan", "0.0") }
                "cover" -> { mpv?.setString("panscan", "1.0") }
                "16/9" -> mpv?.setString("video-aspect-override", "16:9")
                "4/3" -> mpv?.setString("video-aspect-override", "4:3")
                "Pan/Scan" -> mpv?.setString("panscan", "1.0")
            }
        } catch (e: Exception) {}
    }

    private fun setSpeed(txt: String) {
        try {
            val s = txt.replace("x","").toDoubleOrNull() ?: 1.0
            mpvView?.mpv?.setString("speed", s.toString())
        } catch (e: Exception) {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    override fun onDestroy() { try { mpvView?.destroy() } catch (e: Exception) {}; scope.cancel(); super.onDestroy() }
}

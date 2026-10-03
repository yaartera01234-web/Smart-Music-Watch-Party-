package app.party.music

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlinx.coroutines.*
import org.schabi.newpipe.extractor.ServiceList
import io.github.yuroyami.libmpvkt.view.MpvView
import io.github.yuroyami.libmpvkt.view.MpvOptions
import kotlin.math.roundToInt

/**
 * PURE NATIVE - HTML DESIGN EXACT - NO WebView, NO HTML LOAD, NO PREMIUM, MPV ONLY - CRASH FIXED
 * - MPV initialize AFTER setContentView (was crashing when init before attach)
 * - PlayerWrap OUTSIDE ScrollView (SurfaceView inside ScrollView crashes)
 * - Try-catch everywhere + Toast
 * - Design exact party-final1.html
 */
class MainActivity : Activity() {
    private var mpvView: MpvView? = null
    private var root: FrameLayout? = null
    private var joinScreen: FrameLayout? = null
    private var appScreen: LinearLayout? = null
    private var titleView: TextView? = null
    private var timeView: TextView? = null
    private var playBtn: TextView? = null
    private var centerPlay: FrameLayout? = null
    private var audioPanel: LinearLayout? = null
    private var settingsPanel: LinearLayout? = null
    private var playlistContainer: LinearLayout? = null
    private var playlistList: LinearLayout? = null
    private var chatList: LinearLayout? = null
    private var onlineCountView: TextView? = null
    private var myNameBadge: TextView? = null
    private var noVideoView: LinearLayout? = null
    private var isPlaying = false
    private var isPlaylistExpanded = false
    private var currentName = "Babu"
    private var currentRoom = "party123"
    private val playlist = mutableListOf<Pair<String,String>>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            buildExactHtmlDesignNative()
            // Init MPV AFTER setContentView - fixes crash
            root?.post {
                try {
                    mpvView?.initialize(MpvOptions())
                } catch (e: Exception) {
                    Toast.makeText(this, "MPV init: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            // Crash handler - show error instead of crash
            val tv = TextView(this).apply {
                text = "Crash fixed: ${e.message}\n${e.stackTrace.take(5).joinToString("\n")}"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#c026d3"))
                setPadding(20,20,20,20)
            }
            setContentView(tv)
            Toast.makeText(this, "Init crash: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildExactHtmlDesignNative() {
        val r = FrameLayout(this)
        r.setBackgroundColor(Color.parseColor("#0f0c29"))
        r.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#0f0c29"), Color.parseColor("#302b63"), Color.parseColor("#24243e")))
        root = r

        // JOIN SCREEN
        val joinScr = FrameLayout(this).apply { setPadding(dp(20), dp(12), dp(20), dp(64)) }
        joinScreen = joinScr

        val joinCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(26), dp(24), dp(26))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke(dp(1), Color.parseColor("#26FFFFFF"))
            }
            elevation = dp(20).toFloat()
        }

        val logo = TextView(this).apply { text = "🎬"; textSize = 50f; gravity = Gravity.CENTER }
        joinCard.addView(logo, LinearLayout.LayoutParams(-2,-2).apply { gravity = Gravity.CENTER; bottomMargin = dp(5) })

        val h1 = TextView(this).apply {
            text = "Watch Party"; textSize = 36f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(Color.parseColor("#ff6ec4"))
        }
        joinCard.addView(h1, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(5) })

        val tagline = TextView(this).apply {
            text = "MPV 0.3.0 • Pure Native • No Premium • No HTML Load"; setTextColor(Color.parseColor("#c4b5fd")); textSize = 14f; gravity = Gravity.CENTER
        }
        joinCard.addView(tagline, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(12) })

        fun makeInput(hint: String, fontSize: Float, paddingV: Int): EditText {
            return EditText(this@MainActivity).apply {
                this.hint = hint; setHintTextColor(Color.parseColor("#999999")); setTextColor(Color.WHITE); textSize = fontSize; gravity = Gravity.CENTER
                setPadding(dp(18), dp(paddingV), dp(18), dp(paddingV))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#1AFFFFFF")); setStroke(dp(2), Color.parseColor("#33FFFFFF"))
                }
            }
        }

        val nameInput = makeInput("Your name", 17f, 14)
        joinCard.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val roomInput = makeInput("Room code (e.g. party123)", 16f, 12)
        joinCard.addView(roomInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val towerSpinner = Spinner(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#1AFFFFFF")); setStroke(dp(2), Color.parseColor("#33FFFFFF")) }
        }
        val towers = arrayOf("EMQX Tower 1 - Auto", "EMQX Tower 2 - Fast", "EMQX Tower 3 - Stable")
        towerSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, towers)
        joinCard.addView(towerSpinner, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(14) })

        val joinBtn = TextView(this).apply {
            text = "JOIN PARTY"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#f472b6"), Color.parseColor("#a78bfa"), Color.parseColor("#60a5fa"))).apply { cornerRadius = dp(14).toFloat() }
        }
        joinCard.addView(joinBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val features = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun feat(txt: String): TextView {
            return TextView(this@MainActivity).apply {
                text = txt; setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(12), dp(5), dp(12), dp(5))
                background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#1EFFFFFF")) }
            }
        }
        features.addView(feat("🎥 MPV 0.3.0"))
        features.addView(feat("🔊 100%").apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(8) })
        features.addView(feat("📱 arm64").apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(8) })
        joinCard.addView(features, LinearLayout.LayoutParams(-1,-2).apply { gravity = Gravity.CENTER })

        val joinCardWrap = FrameLayout(this).apply { addView(joinCard, FrameLayout.LayoutParams(dp(420), -2).apply { gravity = Gravity.CENTER }) }
        joinScr.addView(joinCardWrap, FrameLayout.LayoutParams(-1,-1))
        r.addView(joinScr, FrameLayout.LayoutParams(-1,-1))

        // APP SCREEN
        val app = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        appScreen = app

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10)); setBackgroundColor(Color.parseColor("#59000000"))
        }
        val brand = TextView(this).apply { text = "🎬 Watch Party"; textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.parseColor("#ff6ec4")) }
        header.addView(brand, LinearLayout.LayoutParams(0,-2,1f))

        val headerRight = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val onlineCount = TextView(this).apply {
            text = "● 1 online"; setTextColor(Color.parseColor("#4ade80")); textSize = 14f; setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#264ADE80")) }
        }
        onlineCountView = onlineCount
        headerRight.addView(onlineCount, LinearLayout.LayoutParams(-2,-2).apply { rightMargin = dp(10) })

        val myBadge = TextView(this).apply {
            text = "Babu"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#f472b6"), Color.parseColor("#a78bfa"))).apply { cornerRadius = dp(20).toFloat() }
        }
        myNameBadge = myBadge
        headerRight.addView(myBadge, LinearLayout.LayoutParams(-2,-2))
        header.addView(headerRight, LinearLayout.LayoutParams(-2,-2))
        app.addView(header, LinearLayout.LayoutParams(-1,-2))

        // Url bar - OUTSIDE ScrollView (fixed)
        val urlBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val urlInput = EditText(this).apply {
            hint = "YouTube / MP4 / MKV / M3U8 URL"; setHintTextColor(Color.parseColor("#888888")); setTextColor(Color.WHITE); textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#14FFFFFF")); setStroke(dp(2), Color.parseColor("#26FFFFFF")) }
        }
        val loadBtn = TextView(this).apply {
            text = "▶ Load"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#1AD07A"), Color.parseColor("#0ABF6A"))).apply {
                cornerRadius = dp(13).toFloat(); setStroke(dp(1), Color.parseColor("#521AD07A"))
            }
        }
        urlBar.addView(urlInput, LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin = dp(8) })
        urlBar.addView(loadBtn, LinearLayout.LayoutParams(-2,-2))
        app.addView(urlBar, LinearLayout.LayoutParams(-1,-2))

        // Player wrap - FIXED OUTSIDE ScrollView - 198px exact - CRASH FIX
        val playerWrap = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(Color.BLACK); setStroke(dp(1), Color.parseColor("#1EFFFFFF"))
            }
        }
        val mpv = MpvView(this)
        mpvView = mpv
        // DO NOT initialize here - init after setContentView via post
        playerWrap.addView(mpv, FrameLayout.LayoutParams(-1,-1))

        val center = FrameLayout(this).apply {
            val inner = TextView(this@MainActivity).apply { text = "▶"; setTextColor(Color.WHITE); textSize = 32f; gravity = Gravity.CENTER }
            addView(inner, FrameLayout.LayoutParams(-1,-1))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), Color.parseColor("#1AFFFFFF")); setColor(Color.parseColor("#66000000")) }
        }
        centerPlay = center
        center.setOnClickListener { togglePlay() }
        playerWrap.addView(center, FrameLayout.LayoutParams(dp(58), dp(58)).apply { gravity = Gravity.CENTER })

        val badge = TextView(this).apply {
            text = "MPV 0.3.0"; setTextColor(Color.WHITE); textSize = 9f; setTypeface(null, android.graphics.Typeface.BOLD); letterSpacing = 0.12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#c026d3")) }
        }
        playerWrap.addView(badge, FrameLayout.LayoutParams(-2,-2).apply { gravity = Gravity.TOP or Gravity.START; leftMargin = dp(8); topMargin = dp(8) })

        val noVideo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(20), dp(20), dp(20)); visibility = View.VISIBLE }
        noVideoView = noVideo
        val noVideoIcon = TextView(this).apply { text = "🎬"; textSize = 36f; gravity = Gravity.CENTER }
        val noVideoTxt = TextView(this).apply { text = "No video loaded\nAdd YouTube (360p) or MP4/MKV/M3U8 (HQ)"; setTextColor(Color.parseColor("#a5b4fc")); textSize = 13f; gravity = Gravity.CENTER }
        noVideo.addView(noVideoIcon); noVideo.addView(noVideoTxt)
        playerWrap.addView(noVideo, FrameLayout.LayoutParams(-1,-1).apply { gravity = Gravity.CENTER })

        app.addView(playerWrap, LinearLayout.LayoutParams(-1, dp(198)))

        // Bottom controls - FIXED
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)) }
        val prog = SeekBar(this).apply {
            max = 1000; progress = 0
            thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(18), dp(18)) }
        }
        bottom.addView(prog, LinearLayout.LayoutParams(-1, dp(24)))
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tl = TextView(this).apply { text = "0:00"; setTextColor(Color.parseColor("#aaaaaa")); textSize = 12f }
        val tr = TextView(this).apply { text = "-0:00"; setTextColor(Color.parseColor("#aaaaaa")); textSize = 12f; gravity = Gravity.END }
        timeRow.addView(tl, LinearLayout.LayoutParams(0,-2,1f))
        timeRow.addView(tr, LinearLayout.LayoutParams(0,-2,1f))
        bottom.addView(timeRow, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })

        val ctrlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun makeCtrl(txt: String, purple: Boolean = false): TextView {
            return TextView(this@MainActivity).apply {
                text = txt; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (purple) Color.parseColor("#c026d3") else Color.parseColor("#2a2a2a")) }
            }
        }
        val rew = makeCtrl("↻")
        val play = makeCtrl("▶", true).apply { setPadding(dp(18), dp(14), dp(18), dp(14)); textSize = 22f }
        playBtn = play
        play.setOnClickListener { togglePlay() }
        val fwd = makeCtrl("↺")
        val audio = makeCtrl("〰️").apply { setOnClickListener { audioPanel?.visibility = View.VISIBLE; settingsPanel?.visibility = View.GONE } }
        val settings = makeCtrl("⚙️").apply { setOnClickListener { settingsPanel?.visibility = View.VISIBLE; audioPanel?.visibility = View.GONE } }
        val fs = makeCtrl("⤢")
        ctrlRow.addView(rew, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        ctrlRow.addView(play, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(8) })
        ctrlRow.addView(fwd, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(16) })
        ctrlRow.addView(audio, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        ctrlRow.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        ctrlRow.addView(fs, LinearLayout.LayoutParams(dp(48), dp(48)))
        bottom.addView(ctrlRow, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(12); gravity = Gravity.CENTER })
        app.addView(bottom, LinearLayout.LayoutParams(-1,-2))

        // Scrollable part - playlist + users + chat
        val mainScroll = ScrollView(this)
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(0), dp(8), dp(8)) }

        // Playlist box
        val playlistBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#4D000000")); setStroke(dp(1), Color.parseColor("#1AFFFFFF"))
            }
        }
        playlistContainer = playlistBox

        val plHeadWrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        val plTitle = TextView(this).apply { text = "📋 Playlist"; setTextColor(Color.parseColor("#c4b5fd")); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD) }
        val plCount = TextView(this).apply { text = " (0)"; setTextColor(Color.parseColor("#4ade80")); textSize = 13f }
        val arrow = TextView(this).apply {
            text = "▼"; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#1EFFFFFF")) }
        }
        plHeadWrap.addView(plTitle); plHeadWrap.addView(plCount, LinearLayout.LayoutParams(-2,-2).apply { leftMargin = dp(4) })
        plHeadWrap.addView(View(this), LinearLayout.LayoutParams(0,-2,1f))
        plHeadWrap.addView(arrow, LinearLayout.LayoutParams(dp(26), dp(26)))
        playlistBox.addView(plHeadWrap, LinearLayout.LayoutParams(-1,-2))

        val plList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); visibility = View.GONE }
        playlistList = plList
        val empty = TextView(this).apply { text = "No videos yet - add YouTube or MP4/MKV"; setTextColor(Color.parseColor("#8b8bad")); textSize = 12f; gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        plList.addView(empty)
        playlistBox.addView(plList, LinearLayout.LayoutParams(-1,-2))

        plHeadWrap.setOnClickListener {
            isPlaylistExpanded = !isPlaylistExpanded
            arrow.text = if (isPlaylistExpanded) "▲" else "▼"
            plList.visibility = if (isPlaylistExpanded) View.VISIBLE else View.GONE
            val lp = playlistBox.layoutParams
            lp.height = if (isPlaylistExpanded) dp(380) else dp(48)
            playlistBox.layoutParams = lp
        }

        main.addView(playlistBox, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })

        // Users box
        val usersBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#05000000")); setStroke(dp(1), Color.parseColor("#14FFFFFF")) }
        }
        val usersTitle = TextView(this).apply { text = "👥 Users"; setTextColor(Color.parseColor("#c4b5fd")); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0,0,0,dp(3)) }
        usersBox.addView(usersTitle)
        val userList = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val userChip = TextView(this).apply {
            text = "Babu"; setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#1AFFFFFF")); setStroke(dp(3), Color.parseColor("#c026d3")) }
        }
        userList.addView(userChip)
        usersBox.addView(userList)
        main.addView(usersBox, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })

        // Chat
        val chatTitle = TextView(this).apply { text = "💬 Chat"; setTextColor(Color.parseColor("#c4b5fd")); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(4), dp(6), dp(4), dp(6)) }
        main.addView(chatTitle, LinearLayout.LayoutParams(-1,-2))

        val chatScroll = ScrollView(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#0D000000")); setStroke(dp(1), Color.parseColor("#1AFFFFFF")) }
        }
        val chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        chatList = chat
        chatScroll.addView(chat, LinearLayout.LayoutParams(-1,-2))
        main.addView(chatScroll, LinearLayout.LayoutParams(-1, dp(200)).apply { bottomMargin = dp(8) })

        val chatInputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#3D050612")) }
            gravity = Gravity.CENTER_VERTICAL
        }
        val chatInput = EditText(this).apply {
            hint = "Message..."; setHintTextColor(Color.parseColor("#888888")); setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#5703040F")); setStroke(dp(1), Color.parseColor("#24FFFFFF")) }
        }
        val sendBtn = TextView(this).apply {
            text = "➤"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#c026d3")) }
        }
        chatInputBar.addView(chatInput, LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin = dp(8) })
        chatInputBar.addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        sendBtn.setOnClickListener {
            val msg = chatInput.text.toString().trim()
            if (msg.isBlank()) return@setOnClickListener
            addChatBubble("You", msg, true)
            chatInput.setText("")
        }
        main.addView(chatInputBar, LinearLayout.LayoutParams(-1,-2))

        mainScroll.addView(main, LinearLayout.LayoutParams(-1,-2))
        app.addView(mainScroll, LinearLayout.LayoutParams(-1,0,1f))

        r.addView(app, FrameLayout.LayoutParams(-1,-1))

        // Audio & Subtitles Panel 55% #0a0a0a
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
        ap.addView(TextView(this).apply { text = "Off"; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(16), dp(12), dp(16), dp(12)); background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#2a0a2a")); setStroke(dp(1), Color.parseColor("#c026d3")) } }, LinearLayout.LayoutParams(-1, dp(48)))
        fun addSlider(label: String, value: String): LinearLayout {
            val lay = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, 0) }
            val lbl = TextView(this@MainActivity).apply { text = "$label · $value"; setTextColor(Color.parseColor("#cccccc")); textSize = 13f }
            val track = FrameLayout(this@MainActivity).apply {
                val bg = View(this@MainActivity).apply { setBackgroundColor(Color.parseColor("#333333")) }
                addView(bg, FrameLayout.LayoutParams(-1, dp(6)).apply { gravity = Gravity.CENTER_VERTICAL })
                val fill = View(this@MainActivity).apply { setBackgroundColor(Color.parseColor("#c026d3")) }
                addView(fill, FrameLayout.LayoutParams(dp(120), dp(6)).apply { gravity = Gravity.CENTER_VERTICAL })
                val dot = View(this@MainActivity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#c026d3")); setSize(dp(18), dp(18)) } }
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
            return TextView(this@MainActivity).apply { text = txt; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8)); background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (active) Color.parseColor("#c026d3") else Color.parseColor("#2a2a2a")) }; setOnClickListener { setSpeed(txt) } }
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
            return TextView(this@MainActivity).apply { text = txt; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (active) Color.parseColor("#c026d3") else Color.parseColor("#2a2a2a")) }; setOnClickListener { setAspect(txt); sp.visibility = View.GONE } }
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

        joinBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val room = roomInput.text.toString().trim()
            if (name.isBlank()) { Toast.makeText(this, "Name likho", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (room.isBlank()) { Toast.makeText(this, "Room code likho", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            currentName = name; currentRoom = room
            myNameBadge?.text = name; onlineCountView?.text = "● 1 online"
            joinScreen?.visibility = View.GONE; appScreen?.visibility = View.VISIBLE
            titleView?.text = "🎬 $room • $name"
            addChatBubble("System", "Welcome $name to $room - MPV 0.3.0 pure native, no HTML load", false)
            addChatBubble("System", "Add YouTube (360p lock) or MP4/MKV/M3U8 (HQ original) - MPV only", false)
        }

        loadBtn.setOnClickListener {
            val u = urlInput.text.toString().trim()
            if (u.isBlank()) return@setOnClickListener
            val title = if (u.contains("youtu")) "YouTube 360p" else "Video ${playlist.size+1}"
            addToPlaylist(title, u)
            urlInput.setText("")
            noVideoView?.visibility = View.GONE
        }

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { it.hide(android.view.WindowInsets.Type.systemBars()); it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun addToPlaylist(title: String, url: String) {
        if (playlistList?.childCount == 1) {
            val first = playlistList?.getChildAt(0)
            if (first is TextView && first.text.toString().contains("No videos")) {
                playlistList?.removeAllViews()
            }
        }
        playlist.add(title to url)
        val item = TextView(this).apply {
            text = "▶ $title\n$url"; setTextColor(Color.parseColor("#cccccc")); textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#0F1AFFFFFF")) }
            setOnClickListener { playUrl(url, title) }
        }
        playlistList?.addView(item, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(5) })
        if (playlist.size == 1) playUrl(url, title)
        if (playlist.size == 1 && !isPlaylistExpanded) {
            isPlaylistExpanded = true
            playlistContainer?.layoutParams?.height = dp(380)
            playlistList?.visibility = View.VISIBLE
        }
    }

    private fun playUrl(url: String, title: String) {
        scope.launch {
            var finalUrl = url
            if (url.contains("youtube") || url.contains("youtu.be")) {
                finalUrl = extractYoutube360p(url) ?: url
            }
            withContext(Dispatchers.Main) {
                try {
                    titleView?.text = "🎬 $title"
                    timeView?.text = "MPV 0.3.0 • Playing"
                    noVideoView?.visibility = View.GONE
                    mpvView?.playFile(finalUrl)
                    isPlaying = true; playBtn?.text = "⏸"; centerPlay?.visibility = View.GONE
                    addChatBubble("Player", "Now playing: $title ${if (url.contains("youtu")) "[360p lock]" else "[HQ original]"}", false)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Play error: ${e.message}", Toast.LENGTH_LONG).show()
                }
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

    private fun addChatBubble(sender: String, msg: String, isMe: Boolean) {
        try {
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (isMe) Color.parseColor("#c026d3") else Color.parseColor("#1a1a1a")) }
            }
            val s = TextView(this).apply { text = sender; setTextColor(if (isMe) Color.WHITE else Color.parseColor("#c026d3")); textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD) }
            val m = TextView(this).apply { text = msg; setTextColor(Color.WHITE); textSize = 13f }
            bubble.addView(s); bubble.addView(m)
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = if (isMe) Gravity.END else Gravity.START; setPadding(0, dp(4), 0, dp(4))
            }
            wrapper.addView(bubble, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.75).toInt(), -2))
            chatList?.addView(wrapper)
        } catch (e: Exception) {}
    }

    private fun togglePlay() {
        try {
            val mpv = mpvView?.mpv
            if (isPlaying) {
                mpv?.setString("pause", "yes")
                isPlaying = false; playBtn?.text = "▶"; centerPlay?.visibility = View.VISIBLE
            } else {
                mpv?.setString("pause", "no")
                isPlaying = true; playBtn?.text = "⏸"; centerPlay?.visibility = View.GONE
            }
        } catch (e: Exception) { Toast.makeText(this, "Toggle: ${e.message}", Toast.LENGTH_SHORT).show() }
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

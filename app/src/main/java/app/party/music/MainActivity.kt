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
 * PURE NATIVE - HTML DESIGN EXACT - NO WebView, NO HTML LOAD, NO PREMIUM, MPV ONLY
 * Boss bola: Exact Sub Kuch Html wala Chahye, Lkn Html load wala koi scene na ho sub kuch pure native ho Just Mpv Player k sath Sub Kuch Native bus Design Html Wala
 * 
 * HTML: https://yaartera01234-web.github.io/watch-party/party-final1.html
 * CSS exact:
 * - body background linear 135deg #0f0c29, #302b63, #24243e
 * - #join-screen height 100vh flex center padding 12px 20px 64px
 * - .join-card background rgba(255,255,255,0.08) blur 15px border 1px solid rgba(255,255,255,0.15) radius 24px padding 26px 24px max-width 420px shadow 0 20px 60px rgba(0,0,0,0.5)
 * - .logo 50px bounce, h1 36px gradient #ff6ec4 #7873f5 #4ade80, tagline #c4b5fd 14px
 * - #name-input 14px 18px radius 14px border 2px rgba(255,255,255,0.2) bg rgba(255,255,255,0.1) 17px center
 * - #room-input 12px 18px radius 14px 16px, #tower-input same
 * - #join-btn gradient 90deg #f472b6 #a78bfa #60a5fa radius 14px 18px bold
 * - .features span bg rgba(255,255,255,0.12) padding 5px 12px radius 20px 12px
 * - header bg rgba(0,0,0,0.35) border-bottom 1px rgba(255,255,255,0.1) padding 10px 18px
 * - .brand 22px bold gradient, #online-count bg rgba(74,222,128,0.15) padding 5px 12px radius 20px, #my-name-badge gradient #f472b6 #a78bfa
 * - main flex 1 column gap 8px padding 8px max-width 640px
 * - .url-bar gap 8px, #url-input padding 12px 16px radius 12px border 2px rgba(255,255,255,0.15) bg rgba(255,255,255,0.08) 14px, #load-btn gradient #1AD07A #0ABF6A radius 13px 800
 * - .player-wrap height 198px bg #000 radius 16px border 1px rgba(255,255,255,0.12)
 * - .playlist-box bg rgba(0,0,0,0.3) border 1px rgba(255,255,255,0.1) radius 12px max-height 48px collapsed 380px open, pl-head 10px 12px 13px bold #c4b5fd, arrow 26x26 bg rgba(255,255,255,0.12) radius 8px
 * - .pl-item bg rgba(255,255,255,0.06) padding 6px 10px radius 8px 12px
 * - #chat-messages flex 1 padding 8px gap 6px, .msg max-width 85%, .msg.own gradient #f472b6 #a78bfa, .msg.other gradient #51c9c2 #7182e9 #aa7ced
 * - Player MPV exact Watch-Party-Mpv: purple #c026d3 64dp, contain/cover/16:9/4:3/Pan-Scan, Volume/Brightness purple sliders, Speed, Koi audio track nahi mili Off
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
    private var isPlaying = false
    private var isPlaylistExpanded = false
    private var currentName = "Babu"
    private var currentRoom = "party123"
    private val playlist = mutableListOf<Pair<String,String>>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildExactHtmlDesignNative()
    }

    private fun buildExactHtmlDesignNative() {
        val r = FrameLayout(this)
        r.setBackgroundColor(Color.parseColor("#0f0c29"))
        // Gradient background 135deg #0f0c29, #302b63, #24243e
        r.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#0f0c29"), Color.parseColor("#302b63"), Color.parseColor("#24243e")))
        root = r

        // ========== JOIN SCREEN - #join-screen height 100vh flex center padding 12px 20px 64px ==========
        val joinScr = FrameLayout(this).apply {
            setPadding(dp(20), dp(12), dp(20), dp(64))
        }
        joinScreen = joinScr

        val joinCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(26), dp(24), dp(26))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#14FFFFFF")) // rgba 0.08
                setStroke(dp(1), Color.parseColor("#26FFFFFF")) // rgba 0.15
            }
            elevation = dp(20).toFloat()
        }

        val logo = TextView(this).apply {
            text = "🎬"
            textSize = 50f
            gravity = Gravity.CENTER
        }
        joinCard.addView(logo, LinearLayout.LayoutParams(-2,-2).apply { gravity = Gravity.CENTER; bottomMargin = dp(5) })

        val h1 = TextView(this).apply {
            text = "Watch Party"
            textSize = 36f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            // Gradient text approximated with #ff6ec4
            setTextColor(Color.parseColor("#ff6ec4"))
        }
        joinCard.addView(h1, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(5) })

        val tagline = TextView(this).apply {
            text = "MPV 0.3.0 • Pure Native • No Premium"
            setTextColor(Color.parseColor("#c4b5fd"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        joinCard.addView(tagline, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(12) })

        fun makeInput(hint: String, fontSize: Float, paddingV: Int): EditText {
            return EditText(this@MainActivity).apply {
                this.hint = hint
                setHintTextColor(Color.parseColor("#999999"))
                setTextColor(Color.WHITE)
                textSize = fontSize
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(paddingV), dp(18), dp(paddingV))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor("#1AFFFFFF")) // rgba 0.1
                    setStroke(dp(2), Color.parseColor("#33FFFFFF")) // rgba 0.2
                }
            }
        }

        val nameInput = makeInput("Your name", 17f, 14)
        joinCard.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val roomInput = makeInput("Room code (e.g. party123)", 16f, 12)
        joinCard.addView(roomInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Tower input - EMQX
        val towerSpinner = Spinner(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(dp(2), Color.parseColor("#33FFFFFF"))
            }
        }
        val towers = arrayOf("EMQX Tower 1 - Auto", "EMQX Tower 2 - Fast", "EMQX Tower 3 - Stable")
        towerSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, towers)
        joinCard.addView(towerSpinner, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(14) })

        val joinBtn = TextView(this).apply {
            text = "JOIN PARTY"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#f472b6"), Color.parseColor("#a78bfa"), Color.parseColor("#60a5fa"))).apply {
                cornerRadius = dp(14).toFloat()
            }
        }
        joinCard.addView(joinBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val features = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // flex wrap approximation with horizontal
        }
        fun feat(txt: String): TextView {
            return TextView(this@MainActivity).apply {
                text = txt
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(Color.parseColor("#1EFFFFFF")) // 0.12
                }
            }
        }
        features.addView(feat("🎥 MPV 0.3.0"))
        features.addView(feat("🔊 100%").apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(8) })
        features.addView(feat("📱 arm64").apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(8) })
        joinCard.addView(features, LinearLayout.LayoutParams(-1,-2).apply { gravity = Gravity.CENTER })

        val joinCardWrap = FrameLayout(this).apply {
            addView(joinCard, FrameLayout.LayoutParams(dp(420), -2).apply { gravity = Gravity.CENTER })
        }
        joinScr.addView(joinCardWrap, FrameLayout.LayoutParams(-1,-1))
        r.addView(joinScr, FrameLayout.LayoutParams(-1,-1))

        // ========== APP SCREEN - #app height 100vh flex column ==========
        val app = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        appScreen = app

        // Header - display flex justify-between padding 10px 18px bg rgba(0,0,0,0.35) border-bottom 1px rgba(255,255,255,0.1)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(Color.parseColor("#59000000")) // 0.35
        }
        val brand = TextView(this).apply {
            text = "🎬 Watch Party"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#ff6ec4"))
        }
        header.addView(brand, LinearLayout.LayoutParams(0,-2,1f))

        val headerRight = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val onlineCount = TextView(this).apply {
            text = "● 1 online"
            setTextColor(Color.parseColor("#4ade80"))
            textSize = 14f
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#264ADE80")) // 0.15
            }
        }
        onlineCountView = onlineCount
        headerRight.addView(onlineCount, LinearLayout.LayoutParams(-2,-2).apply { rightMargin = dp(10) })

        val myBadge = TextView(this).apply {
            text = "Babu"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#f472b6"), Color.parseColor("#a78bfa"))).apply {
                cornerRadius = dp(20).toFloat()
            }
        }
        myNameBadge = myBadge
        headerRight.addView(myBadge, LinearLayout.LayoutParams(-2,-2))

        header.addView(headerRight, LinearLayout.LayoutParams(-2,-2))
        app.addView(header, LinearLayout.LayoutParams(-1,-2))

        // Main - flex 1 column gap 8px padding 8px max-width 640px margin 0 auto
        val mainScroll = ScrollView(this)
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Url bar - gap 8px
        val urlBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val urlInput = EditText(this).apply {
            hint = "YouTube / MP4 / MKV / M3U8 URL"
            setHintTextColor(Color.parseColor("#888888"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#14FFFFFF")) // 0.08
                setStroke(dp(2), Color.parseColor("#26FFFFFF")) // 0.15
            }
        }
        val loadBtn = TextView(this).apply {
            text = "▶ Load"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#1AD07A"), Color.parseColor("#0ABF6A"))).apply {
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), Color.parseColor("#521AD07A")) // 0.32
            }
        }
        urlBar.addView(urlInput, LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin = dp(8) })
        urlBar.addView(loadBtn, LinearLayout.LayoutParams(-2,-2))
        main.addView(urlBar, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })

        // Player wrap - height 198px bg #000 radius 16px border 1px rgba(255,255,255,0.12)
        val playerWrap = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.BLACK)
                setStroke(dp(1), Color.parseColor("#1EFFFFFF")) // 0.12
            }
        }
        val mpv = MpvView(this)
        mpvView = mpv
        try { mpv.initialize(MpvOptions()) } catch (e: Exception) {}
        playerWrap.addView(mpv, FrameLayout.LayoutParams(-1,-1))

        val center = FrameLayout(this).apply {
            val inner = TextView(this@MainActivity).apply { text = "▶"; setTextColor(Color.WHITE); textSize = 32f; gravity = Gravity.CENTER }
            addView(inner, FrameLayout.LayoutParams(-1,-1))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), Color.parseColor("#1AFFFFFF")); setColor(Color.parseColor("#66000000")) }
        }
        centerPlay = center
        center.setOnClickListener { togglePlay() }
        playerWrap.addView(center, FrameLayout.LayoutParams(dp(58), dp(58)).apply { gravity = Gravity.CENTER })

        // MPV badge 9px 900 letter-spacing 1.2px
        val badge = TextView(this).apply {
            text = "MPV 0.3.0"
            setTextColor(Color.WHITE)
            textSize = 9f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#c026d3")) }
        }
        playerWrap.addView(badge, FrameLayout.LayoutParams(-2,-2).apply { gravity = Gravity.TOP or Gravity.START; leftMargin = dp(8); topMargin = dp(8) })

        // No video placeholder
        val noVideo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            visibility = View.VISIBLE
        }
        val noVideoIcon = TextView(this).apply { text = "🎬"; textSize = 36f; gravity = Gravity.CENTER }
        val noVideoTxt = TextView(this).apply { text = "No video loaded\nAdd YouTube (360p) or MP4/MKV/M3U8 (HQ)"; setTextColor(Color.parseColor("#a5b4fc")); textSize = 13f; gravity = Gravity.CENTER }
        noVideo.addView(noVideoIcon); noVideo.addView(noVideoTxt)
        playerWrap.addView(noVideo, FrameLayout.LayoutParams(-1,-1).apply { gravity = Gravity.CENTER })

        main.addView(playerWrap, LinearLayout.LayoutParams(-1, dp(198)).apply { bottomMargin = dp(6) })

        // Bottom controls - Watch-Party-Mpv exact
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
        main.addView(bottom, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })

        // Playlist box - bg rgba(0,0,0,0.3) border 1px rgba(255,255,255,0.1) radius 12px max-height 48px collapsed 380px open
        val playlistBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#4D000000")) // 0.3
                setStroke(dp(1), Color.parseColor("#1AFFFFFF")) // 0.1
            }
            setPadding(dp(0), dp(0), dp(0), dp(0))
        }
        playlistContainer = playlistBox

        val plHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val plTitle = TextView(this).apply { text = "📋 Playlist"; setTextColor(Color.parseColor("#c4b5fd")); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD) }
        val plCount = TextView(this).apply { text = " (0)"; setTextColor(Color.parseColor("#4ade80")); textSize = 13f }
        val arrow = TextView(this).apply {
            text = "▼"; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#1EFFFFFF")) }
        }
        plHead.addView(plTitle); plHead.addView(plCount); plHead.addView(arrow, LinearLayout.LayoutParams(dp(26), dp(26)).apply { leftMargin = dp(8) })
        val arrowWrap = FrameLayout(this).apply { addView(arrow, FrameLayout.LayoutParams(-1,-1)) }
        val plHeadWrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
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

        // Users box - padding 6px 10px border-bottom 1px rgba(255,255,255,0.08) max-height 56px
        val usersBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#05000000"))
                setStroke(dp(1), Color.parseColor("#14FFFFFF"))
            }
        }
        val usersTitle = TextView(this).apply { text = "👥 Users"; setTextColor(Color.parseColor("#c4b5fd")); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0,0,0,dp(3)) }
        usersBox.addView(usersTitle)
        val userList = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val userChip = TextView(this).apply {
            text = "Babu"; setTextColor(Color.WHITE); textSize = 12f
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(dp(3), Color.parseColor("#c026d3"))
            }
        }
        userList.addView(userChip)
        usersBox.addView(userList)
        main.addView(usersBox, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })

        // Chat messages - flex 1 padding 8px gap 6px
        val chatTitle = TextView(this).apply { text = "💬 Chat"; setTextColor(Color.parseColor("#c4b5fd")); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(4), dp(6), dp(4), dp(6)) }
        main.addView(chatTitle, LinearLayout.LayoutParams(-1,-2))

        val chatScroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#0D000000"))
                setStroke(dp(1), Color.parseColor("#1AFFFFFF"))
            }
        }
        val chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        chatList = chat
        chatScroll.addView(chat, LinearLayout.LayoutParams(-1,-2))
        main.addView(chatScroll, LinearLayout.LayoutParams(-1, dp(200)).apply { bottomMargin = dp(8) })

        // Chat input bar - bg rgba(5,6,18,0.24)
        val chatInputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#3D050612")) // 0.24
            }
            gravity = Gravity.CENTER_VERTICAL
        }
        val chatInput = EditText(this).apply {
            hint = "Message..."
            setHintTextColor(Color.parseColor("#888888"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#5703040F")) // 0.34
                setStroke(dp(1), Color.parseColor("#24FFFFFF")) // 0.14
            }
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

        // Load button logic
        loadBtn.setOnClickListener {
            val u = urlInput.text.toString().trim()
            if (u.isBlank()) return@setOnClickListener
            val title = if (u.contains("youtu")) "YouTube 360p" else "Video ${playlist.size+1}"
            addToPlaylist(title, u)
            urlInput.setText("")
            noVideo.visibility = View.GONE
        }

        mainScroll.addView(main, LinearLayout.LayoutParams(-1,-2))
        app.addView(mainScroll, LinearLayout.LayoutParams(-1,0,1f))

        r.addView(app, FrameLayout.LayoutParams(-1,-1))

        // Audio & Subtitles Panel 55% #0a0a0a - Watch-Party-Mpv exact
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
            currentName = name
            currentRoom = room
            myNameBadge?.text = name
            onlineCountView?.text = "● 1 online"
            joinScreen?.visibility = View.GONE
            appScreen?.visibility = View.VISIBLE
            titleView?.text = "🎬 $room • $name"
            addChatBubble("System", "Welcome $name to $room - MPV 0.3.0 pure native, no HTML load", false)
            addChatBubble("System", "Add YouTube (360p lock) or MP4/MKV/M3U8 (HQ original) - MPV only", false)
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
        // Remove empty placeholder
        if (playlistList?.childCount == 1) {
            val first = playlistList?.getChildAt(0)
            if (first is TextView && first.text.toString().contains("No videos")) {
                playlistList?.removeAllViews()
            }
        }
        playlist.add(title to url)
        val item = TextView(this).apply {
            text = "▶ $title\n$url"
            setTextColor(Color.parseColor("#cccccc"))
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#0F1AFFFFFF")) // rgba 0.06
            }
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
                titleView?.text = "🎬 $title"
                timeView?.text = "MPV 0.3.0 • Playing"
                mpvView?.playFile(finalUrl)
                isPlaying = true
                playBtn?.text = "⏸"
                centerPlay?.visibility = View.GONE
                addChatBubble("Player", "Now playing: $title ${if (url.contains("youtu")) "[360p lock]" else "[HQ original]"}", false)
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
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (isMe) Color.parseColor("#c026d3") else Color.parseColor("#1a1a1a"))
            }
        }
        val s = TextView(this).apply { text = sender; setTextColor(if (isMe) Color.WHITE else Color.parseColor("#c026d3")); textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD) }
        val m = TextView(this).apply { text = msg; setTextColor(Color.WHITE); textSize = 13f }
        bubble.addView(s); bubble.addView(m)
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isMe) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        wrapper.addView(bubble, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.75).toInt(), -2))
        chatList?.addView(wrapper)
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

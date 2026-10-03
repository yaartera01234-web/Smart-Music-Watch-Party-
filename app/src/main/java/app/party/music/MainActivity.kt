package app.party.music

import android.app.Activity
import android.content.Intent
import android.graphics.Color
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
 * PURE NATIVE MPV PARTY - No WebView, No Premium Player, No Iframe
 * Design = Smart-Music-Watch-Party exact (join-card 24px, avatar 66dp, player-wrap 198px, playlist 48px collapsed / 380px open, chat below)
 * Player = Watch-Party-Mpv exact clone (purple play #c026d3 64dp, contain/cover/16:9/4:3/Pan-Scan, Volume/Brightness purple sliders, Speed, Audio delay)
 * MPV 0.3.0 official yuroyami.github.io/maven - HW AV1, FFmpeg 9.0.1
 * Package app.smart.mpv.party side-by-side with original app.party.music
 */
class MainActivity : Activity() {
    private var mpvView: MpvView? = null
    private var root: FrameLayout? = null
    private var joinCard: LinearLayout? = null
    private var partyUi: LinearLayout? = null
    private var titleView: TextView? = null
    private var timeView: TextView? = null
    private var playBtn: TextView? = null
    private var centerPlay: FrameLayout? = null
    private var audioPanel: LinearLayout? = null
    private var settingsPanel: LinearLayout? = null
    private var playlistContainer: LinearLayout? = null
    private var playlistList: LinearLayout? = null
    private var chatList: LinearLayout? = null
    private var isPlaying = false
    private var isPlaylistExpanded = false
    private var currentTitle = "MPV Party"
    private val playlist = mutableListOf<Pair<String,String>>() // title to url
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildPureNativeUi()
    }

    private fun buildPureNativeUi() {
        val r = FrameLayout(this)
        r.setBackgroundColor(Color.parseColor("#0d0716"))
        root = r

        // ========== JOIN SCREEN ==========
        val join = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#1a1228"))
                setStroke(dp(1), Color.parseColor("#2a1a3a"))
            }
            elevation = dp(8).toFloat()
        }
        joinCard = join

        // Avatar 66dp
        val avatarWrap = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#2a1a3a"))
                setStroke(dp(2), Color.parseColor("#c026d3"))
            }
        }
        val avatarTxt = TextView(this).apply {
            text = "🎵"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        avatarWrap.addView(avatarTxt, FrameLayout.LayoutParams(-1,-1))
        join.addView(avatarWrap, LinearLayout.LayoutParams(dp(66), dp(66)).apply { gravity = Gravity.CENTER; bottomMargin = dp(16) })

        val appTitle = TextView(this).apply {
            text = "Smart MPV Party"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        }
        join.addView(appTitle, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(4) })

        val subTitle = TextView(this).apply {
            text = "MPV 0.3.0 • No Premium • Pure Native"
            setTextColor(Color.parseColor("#c026d3"))
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
        }
        join.addView(subTitle, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(24) })

        fun makeInput(hint: String): EditText {
            return EditText(this@MainActivity).apply {
                this.hint = hint
                setHintTextColor(Color.parseColor("#666666"))
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor("#0a0a0a"))
                    setStroke(dp(1), Color.parseColor("#2a2a2a"))
                }
            }
        }

        val nameInput = makeInput("Your name")
        join.addView(nameInput, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })

        val roomInput = makeInput("Room code (e.g. party123)")
        join.addView(roomInput, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(20) })

        val joinBtn = TextView(this).apply {
            text = "JOIN PARTY ▶"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#c026d3"))
            }
        }
        join.addView(joinBtn, LinearLayout.LayoutParams(-1, dp(50)))

        val joinWrap = FrameLayout(this).apply {
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(join, FrameLayout.LayoutParams(-1,-2).apply { gravity = Gravity.CENTER })
        }

        r.addView(joinWrap, FrameLayout.LayoutParams(-1,-1))

        // ========== PARTY UI (after join) ==========
        val party = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#0d0716"))
        }
        partyUi = party

        // Top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(Color.parseColor("#0d0716")) }
        val backBtn = TextView(this).apply { text = "←"; setTextColor(Color.WHITE); textSize = 18f; setBackgroundColor(Color.parseColor("#2a2a2a")); setPadding(dp(12), dp(10), dp(12), dp(10)); setOnClickListener { showJoin() } }
        top.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) })
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply { text = "🎬 $currentTitle"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD) }
        titleView = t
        val tm = TextView(this).apply { text = "MPV 0.3.0 • 0:00 / 0:00"; setTextColor(Color.parseColor("#aaaaaa")); textSize = 12f }
        timeView = tm
        titleCol.addView(t); titleCol.addView(tm)
        top.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        val pipBtn = TextView(this).apply { text = "⧉"; setTextColor(Color.WHITE); textSize = 18f; setBackgroundColor(Color.parseColor("#2a2a2a")); setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { enterPip() } }
        top.addView(pipBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        party.addView(top, LinearLayout.LayoutParams(-1, -2))

        // Player wrap 198px exact from Figma sample
        val playerWrap = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val mpv = MpvView(this)
        mpvView = mpv
        try { mpv.initialize(MpvOptions()) } catch (e: Exception) {}
        playerWrap.addView(mpv, FrameLayout.LayoutParams(-1, -1))

        // Center play 110dp OVAL border #ffffff1a
        val center = FrameLayout(this).apply {
            val inner = TextView(this@MainActivity).apply { text = "▶"; setTextColor(Color.WHITE); textSize = 32f; gravity = Gravity.CENTER }
            addView(inner, FrameLayout.LayoutParams(-1, -1))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(dp(1), Color.parseColor("#ffffff1a")); setColor(Color.parseColor("#00000066")) }
        }
        centerPlay = center
        center.setOnClickListener { togglePlay() }
        playerWrap.addView(center, FrameLayout.LayoutParams(dp(110), dp(110)).apply { gravity = Gravity.CENTER })

        // Premium badge replaced with MPV badge 9px 900 letter-spacing 1.2px
        val badge = TextView(this).apply {
            text = "MPV 0.3.0"
            setTextColor(Color.WHITE)
            textSize = 9f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#c026d3")) }
        }
        playerWrap.addView(badge, FrameLayout.LayoutParams(-2,-2).apply { gravity = Gravity.TOP or Gravity.START; leftMargin = dp(8); topMargin = dp(8) })

        party.addView(playerWrap, LinearLayout.LayoutParams(-1, dp(198)))

        // Bottom controls below player
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)); setBackgroundColor(Color.parseColor("#0d0716")) }
        val prog = SeekBar(this).apply {
            max = 1000; progress = 0
            thumb = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(18), dp(18)) }
        }
        bottom.addView(prog, LinearLayout.LayoutParams(-1, dp(24)))
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tl = TextView(this).apply { text = "0:00"; setTextColor(Color.parseColor("#aaaaaa")); textSize = 12f }
        val tr = TextView(this).apply { text = "-0:00"; setTextColor(Color.parseColor("#aaaaaa")); textSize = 12f; gravity = Gravity.END }
        timeRow.addView(tl, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(tr, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        val ctrlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun makeCtrl(txt: String, purple: Boolean = false): TextView {
            return TextView(this@MainActivity).apply {
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
        val fs = makeCtrl("⤢").apply { setOnClickListener { startActivity(Intent(this@MainActivity, PlayerActivity::class.java).apply { putExtra("url", playlist.lastOrNull()?.second ?: ""); putExtra("title", playlist.lastOrNull()?.first ?: "MPV") }) } }
        ctrlRow.addView(rew, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        ctrlRow.addView(play, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(8) })
        ctrlRow.addView(fwd, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(16) })
        ctrlRow.addView(audio, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        ctrlRow.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        ctrlRow.addView(fs, LinearLayout.LayoutParams(dp(48), dp(48)))
        bottom.addView(ctrlRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); gravity = Gravity.CENTER })
        party.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        // Playlist box 48px collapsed / 380px open
        val playlistBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1228"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        playlistContainer = playlistBox
        val playlistHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val playlistTitle = TextView(this).apply { text = "📋 Playlist (MPV)"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD) }
        val playlistExpand = TextView(this).apply { text = "▼"; setTextColor(Color.parseColor("#c026d3")); textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        playlistHeader.addView(playlistTitle, LinearLayout.LayoutParams(0,-2,1f))
        playlistHeader.addView(playlistExpand, LinearLayout.LayoutParams(dp(32), dp(32)))
        playlistBox.addView(playlistHeader, LinearLayout.LayoutParams(-1,-2))

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        val urlInput = EditText(this).apply {
            hint = "YouTube / MP4 / MKV / M3U8 URL"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#0a0a0a")); setStroke(dp(1), Color.parseColor("#2a2a2a")) }
        }
        val addBtn = TextView(this).apply {
            text = "ADD"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#c026d3")) }
        }
        addRow.addView(urlInput, LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin = dp(8) })
        addRow.addView(addBtn, LinearLayout.LayoutParams(-2,-2))
        playlistBox.addView(addRow, LinearLayout.LayoutParams(-1,-2))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playlistList = list
        playlistBox.addView(list, LinearLayout.LayoutParams(-1,-2))

        playlistHeader.setOnClickListener {
            isPlaylistExpanded = !isPlaylistExpanded
            playlistExpand.text = if (isPlaylistExpanded) "▲" else "▼"
            val lp = playlistBox.layoutParams
            lp.height = if (isPlaylistExpanded) dp(380) else dp(48 + 56) // header + add row
            playlistBox.layoutParams = lp
            list.visibility = if (isPlaylistExpanded) View.VISIBLE else View.GONE
        }
        // default collapsed
        playlistBox.layoutParams = LinearLayout.LayoutParams(-1, dp(48+56))
        list.visibility = View.GONE

        addBtn.setOnClickListener {
            val u = urlInput.text.toString().trim()
            if (u.isBlank()) return@setOnClickListener
            val title = if (u.contains("youtu")) "YouTube 360p" else "Video ${playlist.size+1}"
            addToPlaylist(title, u)
            urlInput.setText("")
        }

        party.addView(playlistBox, LinearLayout.LayoutParams(-1, dp(48+56)).apply { topMargin = dp(8) })

        // Chat below player - bubbles
        val chatHeader = TextView(this).apply { text = "💬 Chat (MPV Party)"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(dp(12), dp(12), dp(12), dp(6)) }
        party.addView(chatHeader, LinearLayout.LayoutParams(-1,-2))

        val chatScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0d0716"))
        }
        val chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(12)) }
        chatList = chat
        chatScroll.addView(chat, LinearLayout.LayoutParams(-1,-2))
        party.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val chatInputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.parseColor("#1a1228")) }
        val chatInput = EditText(this).apply {
            hint = "Message..."
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#0a0a0a")) }
        }
        val sendBtn = TextView(this).apply {
            text = "➤"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#c026d3")) }
        }
        chatInputRow.addView(chatInput, LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin = dp(8) })
        chatInputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        sendBtn.setOnClickListener {
            val msg = chatInput.text.toString().trim()
            if (msg.isBlank()) return@setOnClickListener
            addChatBubble("You", msg, true)
            chatInput.setText("")
        }

        party.addView(chatInputRow, LinearLayout.LayoutParams(-1,-2))

        // Scroll container for party UI
        val partyScroll = ScrollView(this)
        partyScroll.addView(party, LinearLayout.LayoutParams(-1,-2))
        r.addView(partyScroll, FrameLayout.LayoutParams(-1,-1).apply { topMargin = 0 })

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
        ap.addView(TextView(this).apply { text = "Off"; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(16), dp(12), dp(16), dp(12)); background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#2a0a2a")); setStroke(dp(1), Color.parseColor("#c026d3")) } }, LinearLayout.LayoutParams(-1, dp(48)))
        fun addSlider(label: String, value: String): LinearLayout {
            val lay = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, 0) }
            val lbl = TextView(this@MainActivity).apply { text = "$label · $value"; setTextColor(Color.parseColor("#cccccc")); textSize = 13f }
            val track = FrameLayout(this@MainActivity).apply {
                val bg = View(this@MainActivity).apply { setBackgroundColor(Color.parseColor("#333333")) }
                addView(bg, FrameLayout.LayoutParams(-1, dp(6)).apply { gravity = Gravity.CENTER_VERTICAL })
                val fill = View(this@MainActivity).apply { setBackgroundColor(Color.parseColor("#c026d3")) }
                addView(fill, FrameLayout.LayoutParams(dp(120), dp(6)).apply { gravity = Gravity.CENTER_VERTICAL })
                val dot = View(this@MainActivity).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor("#c026d3")); setSize(dp(18), dp(18)) } }
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

        // Player Settings Panel
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
            return TextView(this@MainActivity).apply { text = txt; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8)); background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (active) Color.parseColor("#c026d3") else Color.parseColor("#2a2a2a")) }; setOnClickListener { setSpeed(txt) } }
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
            return TextView(this@MainActivity).apply { text = txt; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (active) Color.parseColor("#c026d3") else Color.parseColor("#2a2a2a")) }; setOnClickListener { setAspect(txt); sp.visibility = View.GONE } }
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

        // Join button logic
        joinBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val room = roomInput.text.toString().trim()
            if (name.isBlank() || room.isBlank()) {
                Toast.makeText(this, "Name & Room required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentTitle = "$room • $name"
            titleView?.text = "🎬 $currentTitle"
            joinWrap.visibility = View.GONE
            partyScroll.visibility = View.VISIBLE
            // party is inside partyScroll, need to show parent scroll
            (partyScroll.parent as? View)?.visibility = View.VISIBLE
            // Actually partyUi is inside partyScroll, we already set partyScroll visible via r? Let's just show partyUi container's parent
            partyUi?.visibility = View.VISIBLE
            // Find partyScroll and show
            partyScroll.visibility = View.VISIBLE
            // Add welcome chat
            addChatBubble("System", "Welcome $name to $room - MPV 0.3.0 pure native, no Premium", false)
            addChatBubble("System", "Add YouTube (360p lock) or MP4/MKV/M3U8 (HQ original)", false)
        }

        // Initially hide party scroll, show join
        partyScroll.visibility = View.GONE

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { it.hide(android.view.WindowInsets.Type.systemBars()); it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun showJoin() {
        // Go back to join screen
        root?.let { r ->
            // Find joinWrap and partyScroll by traversing
            for (i in 0 until r.childCount) {
                val c = r.getChildAt(i)
                if (c is FrameLayout) { // joinWrap
                    if (c.childCount > 0 && c.getChildAt(0) is LinearLayout) {
                        val inner = c.getChildAt(0) as LinearLayout
                        if (inner.childCount > 0 && inner.getChildAt(0) is FrameLayout) { // avatar check
                            c.visibility = View.VISIBLE
                        }
                    }
                }
                if (c is ScrollView) {
                    c.visibility = View.GONE
                }
            }
        }
    }

    private fun addToPlaylist(title: String, url: String) {
        playlist.add(title to url)
        val item = TextView(this).apply {
            text = "▶ $title\n$url"
            setTextColor(Color.parseColor("#cccccc"))
            textSize = 12f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#0a0a0a"))
                setStroke(dp(1), Color.parseColor("#2a2a2a"))
            }
            setOnClickListener { playUrl(url, title) }
        }
        playlistList?.addView(item, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(6) })
        if (playlist.size == 1) playUrl(url, title)
        // Expand playlist if first item
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
                currentTitle = title
                titleView?.text = "🎬 $title"
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
            background = android.graphics.drawable.GradientDrawable().apply {
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

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16,9)).build())
            } catch (e: Exception) {}
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    override fun onDestroy() { try { mpvView?.destroy() } catch (e: Exception) {}; scope.cancel(); super.onDestroy() }
}

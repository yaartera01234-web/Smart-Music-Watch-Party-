package app.party.music

import android.app.Activity
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * v25: Native Player (Media3/ExoPlayer).
 *
 * Watch-Party page se "native" button dabane par ye screen khulti hai — page ke
 * `window.YaarNative.openPlayer(url, title)` bridge se aati hai.
 *
 *  - Video/Audio native decoder se chalta hai (WebView ke bajaye)
 *  - Background me chalta rehta hai + notification me controls (PlaybackService)
 *  - Player band karne ke liye back dabao: playback notification me chalta rehta hai,
 *    notification se "X" daba kar poora band kar sakte ho.
 */
@UnstableApi
class PlayerActivity : Activity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var note: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())

        val pv = PlayerView(this)
        pv.useController = true
        pv.setShowNextButton(false)
        pv.setShowPreviousButton(false)
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))

        note = TextView(this).apply {
            setTextColor(0xFFFF5FA2.toInt())
            textSize = 13f
            visibility = View.GONE
        }
        root.addView(note, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = 40; topMargin = 60 })
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
                pv.player = c
                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                    .build()
                c.setMediaItem(item, pos)
                c.prepare()
                c.play()
                c.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        showNote("⚠️ Ye link native player me nahi chala — page wale player se try karo")
                    }
                })
            } catch (t: Throwable) {
                showNote("⚠️ Player start nahi hua")
            }
        }, MoreExecutors.directExecutor())
    }

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

package app.party.music

import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * v25: Native player ka playback engine.
 *
 * MediaSessionService is liye ke isse background playback + notification khud aa jati hai:
 * app se bahar aa jao ya screen band kar do, video/gaana chalta rehta hai aur notification
 * me play/pause + seek controls hote hain (YouTube app wala behaviour).
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        /* v28: browser jaisa UA + WebView ke cookies + referer —
           warna bohat se streams (jo referer/login check karte hain) native me 403 dete hain */
        val ua = try { WebSettings.getDefaultUserAgent(this) } catch (t: Throwable) { "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36" }
        val base = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)
        val withCookies = ResolvingDataSource.Factory(base) { spec ->
            val u = spec.uri.toString()
            val hdrs = HashMap<String, String>()
            try {
                val ck = CookieManager.getInstance().getCookie(u)
                if (!ck.isNullOrBlank()) hdrs["Cookie"] = ck
            } catch (t: Throwable) {}
            hdrs["Referer"] = "https://yaartera01234-web.github.io/"
            spec.withRequestHeaders(hdrs)
        }
        val dsFactory = DefaultDataSource.Factory(this, withCookies)
        val msFactory = DefaultMediaSourceFactory(dsFactory)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(msFactory).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            setHandleAudioBecomingNoisy(true)
        }
        player = p
        session = MediaSession.Builder(this, p).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** App ko recent se hata dein aur kuch chal nahi raha -> service band kar do. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            player?.release()
            it.release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}

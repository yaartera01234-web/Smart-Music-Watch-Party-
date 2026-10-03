package app.party.music

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * PURE NATIVE MPV - ExoPlayer kept for compatibility but no WebView cookies, no github.io referer
 * Main playback is MPV 0.3.0 MpvView, not ExoPlayer
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val base = DefaultHttpDataSource.Factory()
            .setUserAgent("Smart-MPV-Party/54 MPV 0.3.0")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
            .setAllowCrossProtocolRedirects(true)
        val dsFactory = DefaultDataSource.Factory(this, base)
        val msFactory = DefaultMediaSourceFactory(dsFactory)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(msFactory).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
        }
        player = p
        session = MediaSession.Builder(this, p).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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

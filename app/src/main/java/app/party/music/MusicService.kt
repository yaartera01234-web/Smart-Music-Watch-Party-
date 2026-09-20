package app.party.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Foreground media-playback service. Three layers keep the party alive in background:
 *  1. Foreground service + notification  -> process is never killed
 *  2. Partial wake lock                  -> CPU (and WebView audio) runs with screen off
 *  3. Audio focus + MediaSession         -> Android treats us as a real music app, so its
 *     media pipeline (and OEM battery savers) leave the audio alone
 */
class MusicService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var session: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "musicparty:audio")
                .apply { acquire() }
        }

        takeAudioFocus()
        startMediaSession()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, buildNotification())
        }
        return START_STICKY
    }

    private fun takeAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun startMediaSession() {
        if (session != null) return
        val s = MediaSessionCompat(this, "musicparty")
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, -1, 1f)
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
                .build()
        )
        s.isActive = true
        session = s
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("music", "Music Watch Party", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "music")
        else Notification.Builder(this)
        return builder
            .setContentTitle("Music Watch Party")
            .setContentText("Party chal rahi hai — background playback active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        session?.release()
        session = null
        val am = audioManager
        if (am != null) {
            if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") am.abandonAudioFocus(null)
        }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val i = Intent(context, MusicService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}

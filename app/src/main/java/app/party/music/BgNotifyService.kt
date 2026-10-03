package app.party.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * PURE NATIVE - No WebView, No HTML, No YaarNative - MPV ONLY
 */
class BgNotifyService : Service() {
    companion object {
        private const val NOTE_ID = 4242
        const val NOTE_GONE = "app.party.music.NOTE_GONE"
        @Volatile var running = false
        @Volatile var foregroundReady = false
        @Volatile var noteMuted = false
        fun prefs(ctx: Context) = ctx.getSharedPreferences("ypbg", Context.MODE_PRIVATE)
        fun start(ctx: Context) {
            val i = Intent(ctx, BgNotifyService::class.java)
            if (running) { try { ctx.startService(i); return } catch (t: Throwable) {} }
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (t: Throwable) {}
        }
        fun stop(ctx: Context) {
            if (!running || !foregroundReady) return
            try { ctx.stopService(Intent(ctx, BgNotifyService::class.java)) } catch (t: Throwable) {}
        }
    }
    override fun onCreate() {
        super.onCreate()
        running = true; foregroundReady = false
        noteMuted = try { prefs(this).getBoolean("noteMuted", false) } catch (t: Throwable) { false }
        try { startForeground(NOTE_ID, buildNote()); foregroundReady = true } catch (t: Throwable) { running = false; stopSelf(); return }
    }
    private fun buildNote(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("mpvparty", "MPV Party", NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "mpvparty") else Notification.Builder(this)
        return builder.setContentTitle("Smart MPV Party").setContentText("MPV 0.3.0 pure native - no HTML").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && NOTE_GONE == intent.action) {
            noteMuted = true
            try { prefs(this).edit().putBoolean("noteMuted", true).apply() } catch (t: Throwable) {}
            return START_STICKY
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { running = false; foregroundReady = false; super.onDestroy() }
}

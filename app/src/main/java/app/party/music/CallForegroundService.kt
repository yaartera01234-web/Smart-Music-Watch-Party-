package app.party.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Keeps an already answered WebRTC call alive while the UI Activity is backgrounded.
 * This service starts only after a user accepts/starts a call; it never rings for incoming calls.
 */
class CallForegroundService : Service() {

    private var callWakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "ongoing_voice_call"
        private const val NOTIFICATION_ID = 9044
        private const val ACTION_START = "app.party.music.action.START_ONGOING_CALL"
        private const val ACTION_STOP = "app.party.music.action.STOP_ONGOING_CALL"
        @Volatile var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra("started_at", System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, CallForegroundService::class.java).setAction(ACTION_STOP)) }
            catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val startedAt = intent?.getLongExtra("started_at", System.currentTimeMillis()) ?: System.currentTimeMillis()
        val notification = buildNotification(startedAt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireCallWakeLock()
        running = true
        return START_NOT_STICKY
    }

    private fun acquireCallWakeLock() {
        try {
            if (callWakeLock?.isHeld == true) return
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            callWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicParty:VoiceCall").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {}
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Ongoing voice call", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps an active private voice call connected"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(startedAt: Long): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPending = PendingIntent.getActivity(
            this, NOTIFICATION_ID, open,
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        builder.setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Private voice call")
            .setContentText("Tap to return to your call")
            .setContentIntent(openPending)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        builder.addAction(R.drawable.app_icon, "Return to call", openPending)
        if (Build.VERSION.SDK_INT >= 21) builder.setColor(0xFF8B72FF.toInt())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) builder.setPriority(Notification.PRIORITY_LOW)
        return builder.build()
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else stopForeground(true)
        } catch (_: Throwable) {}
        try { if (callWakeLock?.isHeld == true) callWakeLock?.release() } catch (_: Throwable) {}
        callWakeLock = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

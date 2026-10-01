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
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Rings for an incoming call while the Activity is minimized, then keeps an answered call alive. */
class CallForegroundService : Service() {

    private var callWakeLock: PowerManager.WakeLock? = null
    private var ringPlayer: MediaPlayer? = null
    private var ringing = false
    private val handler = Handler(Looper.getMainLooper())
    private val ringTimeout = Runnable { if (ringing) stopSelf() }

    companion object {
        private const val ACTIVE_CHANNEL_ID = "ongoing_voice_call"
        private const val INCOMING_CHANNEL_ID = "incoming_voice_call_v1"
        private const val ACTIVE_NOTIFICATION_ID = 9044
        private const val INCOMING_NOTIFICATION_ID = 9043
        private const val ACTION_START = "app.party.music.action.START_ONGOING_CALL"
        private const val ACTION_STOP = "app.party.music.action.STOP_ONGOING_CALL"
        private const val ACTION_RING = "app.party.music.action.RING_INCOMING_CALL"
        const val ACTION_ANSWER_INCOMING = "app.party.music.action.ANSWER_INCOMING_CALL"
        const val ACTION_DECLINE_INCOMING = "app.party.music.action.DECLINE_INCOMING_CALL"
        const val EXTRA_CALL_ID = "incoming_call_id"

        @Volatile var running: Boolean = false
            private set

        fun showIncoming(context: Context, caller: String, callId: String) {
            val intent = Intent(context, CallForegroundService::class.java)
                .setAction(ACTION_RING)
                .putExtra("caller", caller)
                .putExtra(EXTRA_CALL_ID, callId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra("started_at", System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(ACTIVE_NOTIFICATION_ID)
                nm.cancel(INCOMING_NOTIFICATION_ID)
            } catch (_: Throwable) {}
            try {
                // Try graceful stop via ACTION_STOP first
                val i = Intent(context, CallForegroundService::class.java).setAction(ACTION_STOP)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try { context.startForegroundService(i) } catch (_: Throwable) { context.startService(i) }
                } else {
                    try { context.startService(i) } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            try { 
                // Then ensure stopped
                Handler(Looper.getMainLooper()).postDelayed({
                    try { context.stopService(Intent(context, CallForegroundService::class.java)) } catch (_: Throwable) {}
                }, 300)
                context.stopService(Intent(context, CallForegroundService::class.java)) 
            } catch (_: Throwable) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                try { handler.removeCallbacksAndMessages(null) } catch (_: Throwable) {}
                ringing = false
                try { stopRingtone() } catch (_: Throwable) {}
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                    else stopForeground(true)
                } catch (_: Throwable) {}
                try { if (callWakeLock?.isHeld == true) callWakeLock?.release() } catch (_: Throwable) {}
                callWakeLock = null
                running = false
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.cancel(ACTIVE_NOTIFICATION_ID)
                    nm.cancel(INCOMING_NOTIFICATION_ID)
                } catch (_: Throwable) {}
                try { stopSelf() } catch (_: Throwable) {}
                return START_NOT_STICKY
            }
            ACTION_RING -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
                val caller = intent.getStringExtra("caller").orEmpty().ifBlank { "Private contact" }
                val notification = buildIncomingNotification(caller, callId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(INCOMING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(INCOMING_NOTIFICATION_ID, notification)
                }
                ringing = true
                running = true
                startRingtone()
                handler.removeCallbacks(ringTimeout)
                handler.postDelayed(ringTimeout, 35_000L)
            }
            else -> {
                handler.removeCallbacks(ringTimeout)
                ringing = false
                stopRingtone()
                try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(INCOMING_NOTIFICATION_ID) } catch (_: Throwable) {}
                val startedAt = intent?.getLongExtra("started_at", System.currentTimeMillis()) ?: System.currentTimeMillis()
                val notification = buildActiveNotification(startedAt)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(ACTIVE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(ACTIVE_NOTIFICATION_ID, notification)
                }
                acquireCallWakeLock()
                running = true
            }
        }
        return START_NOT_STICKY
    }

    private fun startRingtone() {
        stopRingtone()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            val player = MediaPlayer.create(this, uri) ?: return
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            player.isLooping = true
            player.start()
            ringPlayer = player
        } catch (_: Throwable) { stopRingtone() }
    }

    private fun stopRingtone() {
        try { ringPlayer?.stop() } catch (_: Throwable) {}
        try { ringPlayer?.release() } catch (_: Throwable) {}
        ringPlayer = null
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

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = NotificationChannel(ACTIVE_CHANNEL_ID, "Ongoing voice call", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps an active private voice call connected"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(active)
        val incoming = NotificationChannel(INCOMING_CHANNEL_ID, "Incoming voice calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings for incoming private voice calls"
            setShowBadge(false)
            enableVibration(true)
            setSound(null, null) // ringtone is looped by this media-playback foreground service
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(incoming)
    }

    private fun callActionIntent(action: String, callId: String): PendingIntent {
        val requestCode = if (action == ACTION_ANSWER_INCOMING) INCOMING_NOTIFICATION_ID + 1 else INCOMING_NOTIFICATION_ID + 2
        val open = Intent(this, MainActivity::class.java).apply {
            this.action = action
            putExtra(EXTRA_CALL_ID, callId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val flags = (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, requestCode, open, flags)
    }

    @Suppress("DEPRECATION")
    private fun buildIncomingNotification(caller: String, callId: String): Notification {
        val answer = callActionIntent(ACTION_ANSWER_INCOMING, callId)
        val decline = callActionIntent(ACTION_DECLINE_INCOMING, callId)
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPending = PendingIntent.getActivity(
            this, INCOMING_NOTIFICATION_ID, open,
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, INCOMING_CHANNEL_ID)
        else Notification.Builder(this)
        builder.setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Incoming private call")
            .setContentText("$caller is calling · Tap to answer")
            .setContentIntent(openPending)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= 21) builder.setColor(0xFF8B72FF.toInt())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) builder.setPriority(Notification.PRIORITY_MAX)
        builder.addAction(R.drawable.app_icon, "Decline", decline)
        builder.addAction(R.drawable.app_icon, "Answer", answer)
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun buildActiveNotification(startedAt: Long): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPending = PendingIntent.getActivity(
            this, ACTIVE_NOTIFICATION_ID, open,
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, ACTIVE_CHANNEL_ID)
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
        handler.removeCallbacksAndMessages(null)
        ringing = false
        stopRingtone()
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

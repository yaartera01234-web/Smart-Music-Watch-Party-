package app.party.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log

/**
 * PURE NATIVE - No WebView, No JS, No YaarNative - MPV ONLY
 */
object NotifHub {
    private const val CH_DM = "dm"
    private const val CH_BG = "mpvparty_bg"
    const val REPLY_KEY = "yp_reply_text"
    const val ACTION_REPLY = "app.smart.mpv.party.REPLY"
    private var lastKey = ""
    private var lastTs = 0L
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_DM, "MPV Party Messages", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_BG, "MPV Party Background", NotificationManager.IMPORTANCE_LOW))
        } catch (t: Throwable) { Log.e("MPVParty", "channel fail", t) }
    }
    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK }
        return PendingIntent.getActivity(ctx, 0, i, if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    }
    fun post(ctx: Context, title: String?, text: String?) {
        synchronized(this) {
            val key = (title ?: "") + "|" + (text ?: "")
            val now = System.currentTimeMillis()
            if (key == lastKey && now - lastTs < 8000) return
            lastKey = key; lastTs = now
        }
        try {
            ensureChannels(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val nid = (System.currentTimeMillis() % 100000).toInt()
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_DM) else Notification.Builder(ctx)
            b.setSmallIcon(R.drawable.app_icon).setContentTitle(title ?: "💬 MPV Party").setContentText(text ?: "New message").setAutoCancel(true).setContentIntent(openApp(ctx))
            if (Build.VERSION.SDK_INT >= 21) b.setColor(Color.parseColor("#c026d3"))
            nm.notify(nid, b.build())
        } catch (t: Throwable) { Log.e("MPVParty", "notify fail", t) }
    }
    fun post(ctx: Context, title: String?, text: String?, src: String, peer: String? = null) { post(ctx, title, text) }
    fun serviceNote(ctx: Context): Notification {
        ensureChannels(ctx)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_BG) else Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.app_icon).setContentTitle("Smart MPV Party").setContentText("MPV 0.3.0 pure native - no HTML").setOngoing(true).setContentIntent(openApp(ctx))
        if (Build.VERSION.SDK_INT >= 21) b.setColor(Color.parseColor("#c026d3"))
        return b.build()
    }
    fun setReplyTarget(source: String, fn: ((String, String, String) -> Unit)?) {}
    fun deliverReply(source: String, code: String, text: String, requestId: String, completion: (Boolean) -> Unit): Boolean { return false }
    fun completeReply(requestId: String, sent: Boolean) {}
    fun quickReplyJs(requestId: String, code: String, text: String): String { return "" }
    fun cancel(ctx: Context, id: Int) { try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id) } catch (t: Throwable) {} }
}

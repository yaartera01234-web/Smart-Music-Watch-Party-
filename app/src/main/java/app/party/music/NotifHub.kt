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
 * v27: saari notifications ek jagah se — do raste hain
 *   1) app peeche hai  -> MainActivity ka WebView (document.hidden)
 *   2) app poora band  -> BgNotifyService ka chhupa WebView
 * Dono jagah se ek hi message aa sakta hai, is liye 8 second ke andar
 * same title+text dobara post nahi hota (duplicate notification se bachne ke liye).
 */
object NotifHub {

    private const val CH_DM = "dm"
    private const val CH_BG = "bg"

    private var lastKey = ""
    private var lastTs = 0L

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val dm = NotificationChannel(CH_DM, "Messages", NotificationManager.IMPORTANCE_HIGH)
            dm.enableVibration(true)
            dm.description = "Dost ke DM messages"
            nm.createNotificationChannel(dm)
            val bg = NotificationChannel(CH_BG, "Background (messages on)", NotificationManager.IMPORTANCE_LOW)
            bg.setShowBadge(false)
            bg.description = "App band hone pe bhi messages aate rahen"
            nm.createNotificationChannel(bg)
        } catch (t: Throwable) {
            Log.e("MusicParty", "channel fail", t)
        }
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            ctx, 0, i,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
    }

    /** DM message ki notification (app saamne na ho to). */
    fun post(ctx: Context, title: String?, text: String?, src: String) {
        synchronized(this) {
            val key = (title ?: "") + "|" + (text ?: "")
            val now = System.currentTimeMillis()
            if (key == lastKey && now - lastTs < 8000) return
            lastKey = key
            lastTs = now
        }
        try {
            ensureChannels(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_DM) else Notification.Builder(ctx)
            b.setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title ?: "\uD83D\uDCAC Messages")
                .setContentText(text ?: "Naya message aaya hai")
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
            if (Build.VERSION.SDK_INT >= 21) b.setColor(Color.parseColor("#FF5EBC"))
            nm.notify((System.currentTimeMillis() % 100000).toInt(), b.build())
        } catch (t: Throwable) {
            Log.e("MusicParty", "notify fail", t)
        }
    }

    /** Background service ka chalta-hua (silent) notification. */
    fun serviceNote(ctx: Context): Notification {
        ensureChannels(ctx)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_BG) else Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.app_icon)
            .setContentTitle("\uD83D\uDCAC Messages on")
            .setContentText("Naya message aane pe notification aayegi")
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
        if (Build.VERSION.SDK_INT >= 21) b.setColor(Color.parseColor("#FF5EBC"))
        return b.build()
    }
}

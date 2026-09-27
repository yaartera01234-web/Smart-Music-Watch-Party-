package app.party.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
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
 *
 * v40: DM notification me WhatsApp jaisa "Reply" button (inline remote input).
 *      Likha hua text -> ReplyReceiver -> NotifHub.deliverReply() -> chalta hua
 *      WebView (page) -> wahan E2E encrypt hoke MQTT pe chala jata hai.
 *      Is liye app band hone pe bhi notification se seedha jawab diya ja sakta hai.
 */
object NotifHub {

    private const val CH_DM = "dm"
    private const val CH_BG = "bg2"
    private const val CH_BG_OLD = "bg"

    const val REPLY_KEY = "yp_reply_text"
    const val ACTION_REPLY = "app.party.music.REPLY"

    private var lastKey = ""
    private var lastTs = 0L

    /** v40: jo host abhi notification post kar raha hai, uska reply-injector. */
    @Volatile
    private var replyFn: ((String, String) -> Unit)? = null

    fun setReplyTarget(fn: ((String, String) -> Unit)?) { replyFn = fn }

    /** ReplyReceiver se aaya text page ko do. true = page ne accep kar liya. */
    fun deliverReply(code: String, text: String): Boolean {
        val f = replyFn ?: return false
        return try {
            f(code, text)
            true
        } catch (t: Throwable) {
            Log.e("MusicParty", "reply deliver fail", t)
            false
        }
    }

    /** Page ke quick-reply function ko call karne wali safe JS. */
    fun quickReplyJs(code: String, text: String): String {
        val c = org.json.JSONObject.quote(code)
        val t = org.json.JSONObject.quote(text)
        return "(function(){try{return !!(window.yaarQuickReply&&window.yaarQuickReply(" + c + "," + t +
                "));}catch(e){return false;}})()"
    }

    fun cancel(ctx: Context, id: Int) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        } catch (t: Throwable) {}
    }

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val dm = NotificationChannel(CH_DM, "Messages", NotificationManager.IMPORTANCE_HIGH)
            dm.enableVibration(true)
            dm.description = "Dost ke DM messages"
            nm.createNotificationChannel(dm)
            /* v29: purana "bg" channel hata do (uski importance badli nahi ja sakti) */
            try { nm.deleteNotificationChannel(CH_BG_OLD) } catch (t: Throwable) {}
            val bg = NotificationChannel(CH_BG, "Background (messages on)", NotificationManager.IMPORTANCE_MIN)
            bg.setShowBadge(false)
            bg.enableLights(false)
            bg.enableVibration(false)
            bg.setSound(null, null)
            bg.lockscreenVisibility = Notification.VISIBILITY_SECRET
            bg.description = "App band hone pe bhi messages aate rahen (chup-chaap)"
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

    /**
     * v40: Reply button ka PendingIntent. RemoteInput ko kaam karne ke liye
     * Android 12+ pe FLAG_MUTABLE laazmi hai (IMMUTABLE se system text nahi likh paata).
     * Har peer ka apna request code (=notification id) hai, warna ek peer ka reply
     * doosre ke paas chala jata.
     */
    private fun replyIntent(ctx: Context, peer: String, nid: Int): PendingIntent {
        val i = Intent(ctx, ReplyReceiver::class.java)
            .setAction(ACTION_REPLY)
            .putExtra("code", peer)
            .putExtra("nid", nid)
        val flags = if (Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(ctx, nid, i, flags)
    }

    /** DM message ki notification (app saamne na ho to). peer = bhejne wale ka code. */
    fun post(ctx: Context, title: String?, text: String?, src: String, peer: String? = null) {
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
            val hasPeer = !peer.isNullOrEmpty()
            /* peer ka apna id -> usi chat ki purani notification update hoti hai */
            val nid = if (hasPeer) 6000 + (Math.abs(peer!!.hashCode()) % 900)
                      else (System.currentTimeMillis() % 100000).toInt()
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_DM) else Notification.Builder(ctx)
            b.setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title ?: "\uD83D\uDCAC Messages")
                .setContentText(text ?: "Naya message aaya hai")
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
            if (Build.VERSION.SDK_INT >= 21) b.setColor(Color.parseColor("#FF5EBC"))
            if (hasPeer) {
                try {
                    val ri = RemoteInput.Builder(REPLY_KEY).setLabel("Reply likho…").build()
                    @Suppress("DEPRECATION")
                    val act = Notification.Action.Builder(R.drawable.app_icon, "Reply", replyIntent(ctx, peer!!, nid))
                        .addRemoteInput(ri)
                        .setAllowGeneratedReplies(true)
                        .build()
                    b.addAction(act)
                } catch (t: Throwable) {
                    Log.e("MusicParty", "reply action fail", t)
                }
            }
            nm.notify(nid, b.build())
        } catch (t: Throwable) {
            Log.e("MusicParty", "notify fail", t)
        }
    }

    /** v39: user ne note swipe kar diya -> chhupi service ko batao (dobara note na aaye). */
    private fun dismissIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, BgNotifyService::class.java).setAction(BgNotifyService.NOTE_GONE)
        return PendingIntent.getService(
            ctx, 7, i,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Background service ka chalta-hua (silent) notification. */
    fun serviceNote(ctx: Context): Notification {
        ensureChannels(ctx)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH_BG) else Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.app_icon)
            .setContentTitle("\uD83D\uDCAC Messages on")
            .setContentText("Naya message aane pe notification aayegi")
            /* v39: chipka hua note user ko tang kar raha tha -> ab swipe karke hata sakte hain.
               Swipe hone pe service ko pata chal jata hai aur dobara note nahi aata. */
            .setOngoing(false)
            .setAutoCancel(false)
            .setDeleteIntent(dismissIntent(ctx))
            .setShowWhen(false)
            .setContentIntent(openApp(ctx))
        if (Build.VERSION.SDK_INT >= 21) b.setColor(Color.parseColor("#FF5EBC"))
        return b.build()
    }
}

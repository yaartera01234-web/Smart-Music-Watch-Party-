package app.party.music

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * v40: notification ke "Reply" button ka natija.
 *
 * WhatsApp jaisa inline reply: user notification me hi likh kar bhejta hai,
 * Android woh text is receiver ko deta hai. Text page (WebView) ko bhejna parta hai
 * kyunke E2E encryption sirf page ke JS me hai — page encrypt karke MQTT pe chhod deta hai.
 *
 * Agar page zinda na mile (service band / force-stop) to chhoti notification:
 * "App khol ke dobara bhejein".
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context?, intent: Intent?) {
        if (ctx == null || intent == null) return
        try {
            if (NotifHub.ACTION_REPLY != intent.action) return
            val code = intent.getStringExtra("code") ?: ""
            val nid = intent.getIntExtra("nid", 0)
            val text = readText(intent) ?: ""
            val ok = code.isNotEmpty() && text.isNotEmpty() && NotifHub.deliverReply(code, text)
            if (ok) {
                try {
                    (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(nid)
                } catch (t: Throwable) {}
            } else {
                NotifHub.post(ctx, "\u26A0\uFE0F Reply nahi gaya", "App khol ke dobara bhejein", "reply-fail")
            }
        } catch (t: Throwable) {
            Log.e("MusicParty", "reply receiver fail", t)
        }
    }

    private fun readText(intent: Intent): String? {
        return try {
            val b: Bundle = RemoteInput.getResultsFromIntent(intent) ?: return null
            b.getCharSequence(NotifHub.REPLY_KEY)?.toString()
        } catch (t: Throwable) {
            null
        }
    }
}

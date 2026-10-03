package app.party.music

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * v44: notification reply tabhi delivered maano jab page E2E encrypt karke
 * MQTT broker ka PUBACK le aaye. Is se notification ko false success pe dismiss nahi karte.
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context?, intent: Intent?) {
        if (ctx == null || intent == null) return
        var async: BroadcastReceiver.PendingResult? = null
        try {
            if (NotifHub.ACTION_REPLY != intent.action) return
            val code = intent.getStringExtra("code") ?: ""
            val source = intent.getStringExtra("source") ?: ""
            val nid = intent.getIntExtra("nid", 0)
            val text = readText(intent)?.trim() ?: ""
            if (code.isEmpty() || text.isEmpty()) {
                NotifHub.post(ctx, "⚠️ Reply nahi gaya", "App khol ke dobara bhejein", "reply-fail")
                return
            }

            val requestId = UUID.randomUUID().toString()
            async = goAsync()
            val pending = async
            val dispatched = NotifHub.deliverReply(source, code, text, requestId) { sent ->
                try {
                    if (sent) {
                        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(nid)
                    } else {
                        NotifHub.post(ctx, "⚠️ Reply nahi gaya", "App khol ke dobara bhejein", "reply-fail")
                    }
                } catch (t: Throwable) {
                    Log.e("MusicParty", "reply result handling fail", t)
                } finally {
                    try { pending?.finish() } catch (t: Throwable) {}
                }
            }
            if (!dispatched) {
                NotifHub.post(ctx, "⚠️ Reply nahi gaya", "App khol ke dobara bhejein", "reply-fail")
                pending?.finish()
                async = null
                return
            }
            Handler(Looper.getMainLooper()).postDelayed({ NotifHub.completeReply(requestId, false) }, 9000L)
            async = null // completion owns the PendingResult from here
        } catch (t: Throwable) {
            Log.e("MusicParty", "reply receiver fail", t)
            try { async?.finish() } catch (e: Throwable) {}
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

package app.party.music

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * PURE NATIVE - No WebView, No MQTT
 * Simple reply receiver for MPV party chat
 */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context?, intent: Intent?) {
        if (ctx == null || intent == null) return
        if (NotifHub.ACTION_REPLY != intent.action) return
        val nid = intent.getIntExtra("nid", 0)
        val text = readText(intent)?.trim() ?: ""
        if (text.isEmpty()) return
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(nid)
            NotifHub.post(ctx, "💬 Reply sent", text)
        } catch (t: Throwable) {}
    }

    private fun readText(intent: Intent): String? {
        return try {
            val b: Bundle = RemoteInput.getResultsFromIntent(intent) ?: return null
            b.getCharSequence(NotifHub.REPLY_KEY)?.toString()
        } catch (t: Throwable) { null }
    }
}

package app.party.music

import android.util.Log
import org.json.JSONObject

/**
 * ACT7 — NATIVE HAATH (lock screen par room ka hukum seedha player par).
 *
 * ACT6 ka kaan (NativePresence ka cmd/state/queue subscribe) message page ko deta tha —
 * aur lock par page ke haath jam jate hain (evaluateJavascript queue me reh jata hai).
 * Ab MainActivity ka faisla: page jaag raha ho (resumed=true) -> purana tuned rasta
 * (forward, bilkul ACT6 jaisa — zero regression); page soya/band ho -> YAHAN apply,
 * wahi rasta jo lock-screen notification ke pause/play buttons use karte hain
 * (MpvVideoPlayer.pause/resume/seekTo/play — lock par sabit hai).
 *
 * YouTube/unknown-URL wale "load" ko ye jhuklata hai (false) — wo unlock par page
 * sambhalega; presence ACT5 ki wajah se tab tak member zinda rahega.
 */
object NativeControl {
    private const val TAG = "WPNativeControl"

    /** MainActivity inject karta hai. Input: cmd JSON (action=play/pause/seek/sync/load).
     *  Jawab: true = native ne apply kar diya. */
    @Volatile var applyCmd: ((JSONObject) -> Boolean)? = null

    /** Sirf ROOM/cmd wale topics; baqi (state/queue/members) page ke raste par hi theek hain. */
    fun apply(topic: String, payload: String) {
        if (!topic.endsWith("/cmd")) return
        if (payload.isBlank()) return
        val c = try { JSONObject(payload) } catch (_: Throwable) { return }
        val h = applyCmd ?: return
        val ok = try { h(c) } catch (t: Throwable) {
            Log.w(TAG, "apply fail", t); false
        }
        if (!ok) Log.i(TAG, "native haath se nahi hua (${'$'}{c.optString(\"action\")}) — page unlock par sambhalega")
    }
}

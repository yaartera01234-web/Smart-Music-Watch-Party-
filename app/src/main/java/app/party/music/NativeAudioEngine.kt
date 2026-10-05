package app.party.music

import android.content.Context
import android.util.Log
import io.github.yuroyami.libmpvkt.Mpv
import io.github.yuroyami.libmpvkt.getOrThrow
import java.util.Locale

/**
 * v111-FIX — BACKGROUND (LOCK SCREEN) AUDIO ENGINE
 *
 * Kyun MPV: is app ka gaana pehle WebView ke andar bajta tha. Android background me WebView ko
 * freeze kar deta hai (aur YouTube iframe khud pause ho jata hai) — is liye lock screen par gaana
 * ruk jata tha. MPV ek native engine hai: CPU ke andar chalta hai, koi "visible window" nahi
 * chahiye. Yahan hum "vid=no" (audio-only) mode me chalate hain, is liye SURFACE ki zarurat hi
 * nahi rehti — yani lock screen pe bhi 100% chalta hai.
 *
 * Network hardening (lock screen par stream na toote):
 *  - cache=yes + 180s readahead        -> screen off par bhi kaafi buffer mojood
 *  - stream-lavf-o reconnect=1 ...     -> connection toota to khud reconnect
 *  - network-timeout=90                -> slow network par foran surrender na kare
 */
class NativeAudioEngine(private val appContext: Context) {

    @Volatile private var core: Mpv? = null
    @Volatile private var pendingSeek: Double? = null
    @Volatile private var pendingSeekTries: Int = 0

    @Volatile var currentUrl: String? = null
        private set
    @Volatile var currentTitle: String? = null
        private set
    @Volatile var playing: Boolean = false
        private set
    @Volatile var isYoutubeSource: Boolean = false
        private set

    fun isAlive(): Boolean = try { core?.isClosed == false } catch (t: Throwable) { false }

    /** MPV core ek dafa banta hai; dobara call karne par wahi wapas milta hai. */
    fun ensure(): Mpv? {
        val existing = core
        if (existing != null && !existing.isClosed) return existing
        return try {
            val mpv = Mpv.create(appContext)

            // ---- sab se ahem: audio-only. Surface nahi chahiye => lock screen par bhi chalta hai
            mpv.setOption("vid", "no")
            mpv.setOption("ao", "audiotrack,opensles")
            mpv.setOption("audio-client-name", "Music Watch Party")

            // ---- playback behaviour
            mpv.setOption("idle", "yes")
            // build 9: keep-open=yes -> gaana khatam hone par file unload NAHI hoti, is liye
            // eof-reached flag true rehta hai aur auto-next pakar sakta hai
            mpv.setOption("keep-open", "yes")
            mpv.setOption("ytdl", "no")          // hum khud NewPipe se stream nikalte hain
            mpv.setOption("gapless-audio", "yes")
            mpv.setOption("volume-max", "200")
            // build 9: fast start — audio-buffer 1.0s se 0.2s (shuru hone me 1s bachta hai)
            mpv.setOption("audio-buffer", "0.2")

            // ---- network / cache hardening
            mpv.setOption("cache", "yes")
            mpv.setOption("cache-secs", "60")
            // build 9: cache bharne ka intezar kam -> gaana jaldi shuru
            mpv.setOption("cache-pause-wait", "0.2")
            mpv.setOption("demuxer-readahead-secs", "60")
            mpv.setOption("demuxer-max-bytes", "67108864")      // 64 MB
            mpv.setOption("demuxer-max-back-bytes", "16777216") // 16 MB
            mpv.setOption("network-timeout", "90")
            mpv.setOption(
                "stream-lavf-o",
                "reconnect=1,reconnect_streamed=1,reconnect_on_http_error=403,404,500,502,503,reconnect_delay_max=5"
            )
            // YouTube ke googlevideo URLs ke liye referer/UA zaroori hota hai
            mpv.setOption("user-agent", UA)
            mpv.setOption("http-header-fields", "Referer: https://www.youtube.com/,Origin: https://www.youtube.com")

            mpv.initialize().getOrThrow()
            core = mpv
            mpv
        } catch (t: Throwable) {
            Log.e(TAG, "MPV init failed", t)
            null
        }
    }

    /**
     * url: direct audio/video URL ya (youtubeMode=true ho to) already-extracted stream URL.
     *
     * ⚠️ Build 6 tak seek "loadfile se pehle" ho rahi thi — mpv usay IGNORE kar deta hai (file load
     * hui hi nahi hoti), is liye song hamesha 0 se shuru hota tha. Ab mpv ka per-file option
     * `start=<seconds>` loadfile ke sath bheja jata hai, aur verifySeek() usay check karta hai.
     */
    fun play(url: String, startPosition: Double, titleHint: String?, fromYoutube: Boolean): Boolean {
        val mpv = ensure() ?: return false
        return try {
            currentUrl = url
            currentTitle = titleHint
            isYoutubeSource = fromYoutube

            mpv.setString("pause", "yes")
            val options=mutableListOf(String.format(Locale.US, "start=%.3f", startPosition.coerceAtLeast(0.0)))
            AudioTrackMemory.get(url)?.let { options += "aid=$it" }
            mpv.command("loadfile",url,"replace","-1",options.joinToString(",")).getOrThrow()
            pendingSeek = startPosition.takeIf { it > 1.0 }
            pendingSeekTries = 0
            mpv.setString("pause", "no")
            playing = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "play failed", t)
            false
        }
    }

    /** Position set karni thi? to check karo ke lagi bhi ya nahi (mpv kabhi ignore kar deta hai). */
    fun verifySeek() {
        val target = pendingSeek ?: return
        if (target < 5.0) { pendingSeek = null; return }
        val now = position()
        if (now >= target - 5.0) {
            pendingSeek = null          // lag gayi
            return
        }
        if (now <= 0.5 && pendingSeekTries == 0) return   // file abhi load ho rahi hai
        if (pendingSeekTries >= 3) { pendingSeek = null; return }
        pendingSeekTries++
        Log.w(TAG, "seek verify: pos=$now target=$target -> dobara seek ($pendingSeekTries)")
        seekTo(target)
    }

    fun pause() {
        try { core?.setString("pause", "yes") } catch (t: Throwable) {}
        playing = false
    }

    fun resume() {
        try { core?.setString("pause", "no") } catch (t: Throwable) {}
        playing = true
    }

    fun isPaused(): Boolean = str("pause") == "yes"

    fun seekTo(seconds: Double) {
        try { core?.command("seek", String.format(Locale.US, "%.3f", seconds), "absolute+exact") } catch (t: Throwable) {}
    }

    fun position(): Double = str("time-pos")?.toDoubleOrNull() ?: 0.0

    fun duration(): Double? = str("duration")?.toDoubleOrNull()

    /** Sirf core tayyar karo (file load nahi) — lock se pehle "warm up" ke liye. */
    fun warmUp(): Boolean = ensure() != null

    /** Sirf tab true jab koi file load ho chuki ho aur khatam ho gayi ho.
        Build 9: keep-open=yes ki wajah se eof-reached true rehta hai; backup ke taur par
        duration ke qareeb pohanchne par bhi "ended" maan lete hain. */
    fun ended(): Boolean {
        if (currentUrl == null) return false
        if (str("eof-reached") == "yes") return true
        val d = duration() ?: return false
        val t = position()
        return d > 5.0 && t > 0 && t >= d - 1.2
    }

    fun stopPlayback() {
        try {
            core?.setString("pause", "yes")
            core?.command("stop")
            core?.command("playlist-clear")
        } catch (t: Throwable) {}
        currentUrl = null
        playing = false
    }

    fun release() {
        val c = core ?: return
        core = null
        currentUrl = null
        playing = false
        try { c.close() } catch (t: Throwable) { Log.e(TAG, "close failed", t) }
    }

    private fun str(name: String): String? = try { core?.getString(name) } catch (t: Throwable) { null }

    companion object {
        private const val TAG = "WPAudio"
        const val UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

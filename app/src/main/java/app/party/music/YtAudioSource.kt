package app.party.music

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated v117 144p test: on-device yt-dlp URLs, unchanged MPV/Party protocol. */
object YtAudioSource {
    private const val TAG = "WPYouTube"
    private const val PIN = "2026.08.19"
    const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    @Volatile private var app: Context? = null
    private var ready = false
    private val cache = LinkedHashMap<String, Pair<Long, Result>>()
    private val deadline = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "yt-dlp-timeout").apply { isDaemon = true } }
    data class Result(val url: String, val title: String?, val audioUrl: String? = null,
                      val height: Int = 0, val audioCodec: String = "", val userAgent: String = UA, val referer: String? = null, val qualities: List<Int> = emptyList())

    // Called by Activity and service: never unzip Python on the UI thread.
    fun ensureInit(context: Context) { app = context.applicationContext }

    private fun initRuntime() {
        if (ready) return
        val ctx = app ?: error("Extractor context not initialized")
        YoutubeDL.getInstance().init(ctx)
        val pref = ctx.getSharedPreferences("yt-dlp-pinned", Context.MODE_PRIVATE)
        if (pref.getString("version", "") != PIN) {
            val dir = File(ctx.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}")
            dir.mkdirs()
            val dest = File(dir, YoutubeDL.ytdlpBin)
            val tmp = File(dir, "pinned.tmp")
            ctx.resources.openRawResource(R.raw.ytdlp).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(dest)) { "Could not install pinned extractor" }
            pref.edit().putString("version", PIN).apply()
        }
        ready = true
        Log.i(TAG, "yt-dlp $PIN ready (Python + QuickJS)")
    }

    fun videoIdOf(input: String): String? {
        val s = input.trim()
        if (s.length == 11 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return s
        for (p in listOf(Regex("[?&]v=([A-Za-z0-9_-]{11})"), Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("/shorts/([A-Za-z0-9_-]{11})"), Regex("/embed/([A-Za-z0-9_-]{11})"), Regex("/live/([A-Za-z0-9_-]{11})"))) {
            p.find(s)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** Exact requested height AND audio required. Never silently relabel 360p as 144p. */
    @Synchronized
    fun resolve(videoId: String, validate: Boolean = false, preferHeight: Int = 0): Result? {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return null
        val key = "$videoId:$preferHeight"
        if (!validate) cache[key]?.let { if (SystemClock.elapsedRealtime() - it.first < 120_000L) return it.second }
        return try {
            initRuntime()
            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
            request.addOption("--dump-single-json")
            request.addOption("--skip-download")
            request.addOption("--no-playlist")
            request.addOption("--no-progress")
            request.addOption("--socket-timeout", "20")
            request.addOption("--retries", "1")
            request.addOption("--extractor-retries", "1")
            request.addOption("-f", YtStreamSelection.format(preferHeight))
            val processId = "wp-stream-${UUID.randomUUID()}"
            val expired = AtomicBoolean(false)
            val timeout = deadline.schedule({ expired.set(true); YoutubeDL.getInstance().destroyProcessById(processId) }, 70, TimeUnit.SECONDS)
            val response = try {
                YoutubeDL.getInstance().execute(request, processId, false, null)
            } finally { timeout.cancel(false) }
            check(!expired.get()) { "Extractor timeout" }
            val stream = YtStreamSelection.parse(response.out, preferHeight, UA)
            val result = Result(stream.url, stream.title, stream.audioUrl, stream.height, stream.audioCodec, stream.userAgent, stream.referer, stream.qualities)
            cache[key] = SystemClock.elapsedRealtime() to result
            while (cache.size > 8) cache.remove(cache.keys.first())
            Log.i(TAG, "yt-dlp selected ${result.height}p + ${result.audioCodec}; separateAudio=${result.audioUrl != null}")
            result
        } catch (t: Throwable) {
            // Do not log signed URLs/cookies or show a false successful quality label.
            Log.w(TAG, "yt-dlp ${preferHeight}p + audio unavailable (${t.javaClass.simpleName})")
            null
        }
    }
}

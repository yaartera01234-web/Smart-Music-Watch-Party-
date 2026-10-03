package app.party.music

import android.content.Context
import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * v111-FIX (build 3) — YouTube se ASLI playable stream URL.
 *
 * ══════════════════ ASLI WAJAH (lab me test kar ke pakri) ══════════════════
 * YouTube ne ab **audio-only streams (DASH) band kar diye hain** — NewPipe se test:
 *
 *     getAudioStreams() -> 0 streams        <-- khali!
 *     getVideoStreams() -> 1 stream: 360p MPEG_4, videoOnly = false  (muxed: audio+video)
 *
 * Isi liye purane har version me "YouTube 360p lock" likha tha — wo majboori thi, choice nahi.
 * Aur isi wajah se "MPV YouTube se play nahi karta" lagta tha: code sirf audioStreams par tha,
 * jo khali aati hain -> null -> MPV ko page URL milta tha -> kuch nahi chalta tha.
 *
 * Ab: audioStreams (agar mile) -> muxed videoStreams (360p) -> URL ko pehle TEST karo -> MPV.
 * MPV "vid=no" mode me chalta hai, is liye muxed stream se sirf AUDIO decode hota hai.
 *
 * ⚠️ Note: YouTube ki streams premium/paid content ke liye available nahi hoti.
 */
object YtAudioSource {

    private const val TAG = "WPYouTube"
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Mobile Safari/537.36"

    @Volatile private var inited = false

    data class Result(val url: String, val title: String?)

    fun ensureInit(context: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            try {
                NewPipe.init(PartyDownloader())
                inited = true
            } catch (t: Throwable) {
                Log.e(TAG, "NewPipe init failed", t)
            }
        }
    }

    /** watch?v=, youtu.be/, /shorts/, /embed/, /live/ — sab se video id nikalta hai. */
    fun videoIdOf(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.length == 11 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return s
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("/shorts/([A-Za-z0-9_-]{11})"),
            Regex("/embed/([A-Za-z0-9_-]{11})"),
            Regex("/live/([A-Za-z0-9_-]{11})")
        )
        for (p in patterns) {
            val m = p.find(s)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /**
     * Playable stream URL + asli title.
     *
     * validate=false (default): sirf extraction — tez (lock screen handoff ke liye).
     * validate=true: har candidate ko chhote Range request se test karta hai (dhema, magar yaqeeni).
     */
    fun resolve(videoId: String, validate: Boolean = false): Result? = try {
        val service = ServiceList.YouTube
        val handler = service.streamLHFactory.fromUrl("https://www.youtube.com/watch?v=$videoId")
        val extractor = service.getStreamExtractor(handler)
        extractor.fetchPage()

        val candidates = ArrayList<Pair<String, String>>()   // url to label

        // 1) audio-only — DATA BACHANE ke liye sab se KAM bitrate wali pehle (build 16)
        //    (YouTube filhal ye streams nahi deta, magar jab de to sab se halki chuni jaye)
        run {
            val audio = extractor.audioStreams
                ?.filter { !it.url.isNullOrBlank() }
                ?.sortedBy { it.averageBitrate }
            val pick = audio?.firstOrNull { it.averageBitrate >= 60 } ?: audio?.firstOrNull()
            if (pick != null) {
                candidates.add(pick.url to "audio ${pick.averageBitrate}kbps ${pick.format}")
                audio.filter { it !== pick }.forEach { s2 ->
                    candidates.add(s2.url to "audio ${s2.averageBitrate}kbps ${s2.format}")
                }
            }
        }

        // 2) muxed (audio+video) — sab se KAM resolution pehle (data bachao), MPV sirf audio decode karega
        extractor.videoStreams
            ?.filter { !it.isVideoOnly }
            ?.sortedBy { it.height ?: 360 }
            ?.forEach { s ->
                val u = s.url
                if (!u.isNullOrBlank()) candidates.add(u to "muxed ${s.height}p ${s.format}")
            }

        if (candidates.isEmpty()) {
            Log.w(TAG, "koi stream nahi mili (live/premium/region/bot-check?)")
            return null
        }

        val title = try { extractor.name } catch (t: Throwable) { null }
        val first = candidates.first()
        if (!validate) {
            Log.i(TAG, "chuna gaya (bina validate): ${first.second} (candidates=${candidates.size})")
            return Result(first.first, title)
        }

        for ((url, label) in candidates) {
            if (validateUrl(url)) {
                Log.i(TAG, "chuna gaya: $label (candidates=${candidates.size})")
                return Result(url, title)
            } else {
                Log.w(TAG, "URL fail hua: $label")
            }
        }
        Log.w(TAG, "sab validation fail — pehla candidate phir bhi bhej rahe hain: ${first.second}")
        Result(first.first, title)
    } catch (t: Throwable) {
        Log.e(TAG, "resolve failed", t)
        null
    }

    /** Chhota Range request: URL zinda hai? (200/206 = haan) */
    private fun validateUrl(url: String): Boolean = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Range", "bytes=0-1023")
            connectTimeout = 10000
            readTimeout = 12000
        }
        val code = c.responseCode
        try { c.disconnect() } catch (t: Throwable) {}
        code in 200..299
    } catch (t: Throwable) {
        false
    }

    /** NewPipeExtractor ka minimal HTTP downloader (koi extra library nahi). */
    class PartyDownloader : Downloader() {
        override fun execute(request: Request): Response {
            var conn: HttpURLConnection? = null
            return try {
                conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                    requestMethod = request.httpMethod() ?: "GET"
                    connectTimeout = 20000
                    readTimeout = 25000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    setRequestProperty("Accept-Encoding", "gzip")
                }
                val headers = request.headers()
                if (headers != null) {
                    for ((k, v) in headers) {
                        if (k == null || v == null) continue
                        conn.setRequestProperty(k, v.joinToString(","))
                    }
                }
                val body = request.dataToSend()
                if (body != null && body.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body) }
                }

                val code = conn.responseCode
                val message = conn.responseMessage ?: ""
                val respHeaders: Map<String, List<String>> =
                    conn.headerFields.entries
                        .mapNotNull { e -> e.key?.let { k -> k to (e.value ?: emptyList<String>()) } }
                        .toMap()

                val raw = if (code >= 400) conn.errorStream else conn.inputStream
                val text = if (raw == null) "" else {
                    val stream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) GZIPInputStream(raw) else raw
                    stream.bufferedReader().use { it.readText() }
                }
                Response(code, message, respHeaders, text, request.url())
            } catch (t: Throwable) {
                Log.e(TAG, "http failed: ${request.url()}", t)
                Response(500, t.message ?: "error", emptyMap(), "", request.url())
            } finally {
                try { conn?.disconnect() } catch (t: Throwable) {}
            }
        }
    }
}

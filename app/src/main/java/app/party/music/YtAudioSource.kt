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

    data class Result(val url: String, val title: String?, val audioUrl: String? = null)

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
    fun resolve(videoId: String, validate: Boolean = false, preferHeight: Int = 0): Result? = try {
        val ck = "$videoId:$preferHeight"
        if (preferHeight > 0) cachedVideo(ck)?.let { return it }   // page load hotay hi tayyar hui thi
        val service = ServiceList.YouTube
        val handler = service.streamLHFactory.fromUrl("https://www.youtube.com/watch?v=$videoId")
        val extractor = service.getStreamExtractor(handler)
        extractor.fetchPage()

        val candidates = ArrayList<Pair<String, String>>()   // url to label
        var extraAudio: String? = null                       // video-only case: alag audio (MPV: audio-add)

        val audioList = extractor.audioStreams
            ?.filter { !it.url.isNullOrBlank() }
            ?.sortedBy { it.averageBitrate }
        val muxedList = extractor.videoStreams
            ?.filter { !it.isVideoOnly && !it.url.isNullOrBlank() }

        if (preferHeight > 0 && preferHeight <= 240) {
            /* 🎚️ SLOW-NET / DATA SAVER (144p / 240p):
               pehle BILKUL wahi height (video-only) + halki alag audio.
               Wajah: YouTube ki 360p muxed stream "sab se qareeb" hone ki wajah se pehle jeet jati thi —
               144p maangne par bhi 360p ka poora data kharch hota tha. Ab muxed sirf MAJBOORI me. */
            val vo = extractor.videoStreams?.filter { it.isVideoOnly && !it.url.isNullOrBlank() }
            val v = (vo?.filter { (it.height ?: 0) in 1..preferHeight }?.maxByOrNull { it.height ?: 0 })
                ?: (vo?.filter { (it.height ?: 0) > preferHeight }?.minByOrNull { it.height ?: Int.MAX_VALUE })
            val vu = v?.url
            if (!vu.isNullOrBlank()) {
                extraAudio = (audioList?.firstOrNull { it.averageBitrate >= 60 } ?: audioList?.firstOrNull())?.url
                candidates.add(vu to "video-only ${v?.height}p + alag audio (maangi ${preferHeight}p)")
            }
        }
        if (preferHeight > 0) {
            // 360p (default) ya upar wala rasta na chal saka: 360p ke sab se qareeb muxed (audio+video) stream
            muxedList
                ?.sortedBy { Math.abs((it.height ?: 360) - preferHeight) }
                ?.forEach { st ->
                    val u = st.url
                    if (!u.isNullOrBlank()) candidates.add(u to "muxed ${st.height}p ${st.format} (~${preferHeight}p)")
                }
            if (candidates.isEmpty()) {
                // muxed bilkul nahi mila -> video-only (~360p) + alag audio stream
                val v = extractor.videoStreams
                    ?.filter { it.isVideoOnly && !it.url.isNullOrBlank() }
                    ?.sortedBy { Math.abs((it.height ?: 360) - preferHeight) }
                    ?.firstOrNull()
                val vu = v?.url
                if (!vu.isNullOrBlank()) {
                    extraAudio = (audioList?.firstOrNull { it.averageBitrate >= 60 } ?: audioList?.firstOrNull())?.url
                    candidates.add(vu to "video-only ${v?.height}p + alag audio")
                }
            }
        }

        // AWAZ wala rasta (lockscreen/service/sirf-audio): sab se halki audio-only pehle (data bachao)
        if (preferHeight <= 0 && audioList != null && audioList.isNotEmpty()) {
            val pick = audioList.firstOrNull { it.averageBitrate >= 60 } ?: audioList[0]
            val pickUrl = pick.url
            if (!pickUrl.isNullOrBlank()) {
                candidates.add(pickUrl to "audio ${pick.averageBitrate}kbps ${pick.format}")
            }
            for (s2 in audioList) {
                if (s2 === pick) continue
                val u2 = s2.url
                if (!u2.isNullOrBlank()) candidates.add(u2 to "audio ${s2.averageBitrate}kbps ${s2.format}")
            }
        }

        // baaki muxed (fallback) — chhoti se bari
        muxedList
            ?.sortedBy { it.height ?: 360 }
            ?.forEach { st ->
                val u = st.url
                if (!u.isNullOrBlank() && candidates.none { it.first == u }) candidates.add(u to "muxed ${st.height}p ${st.format}")
            }

        if (candidates.isEmpty()) {
            Log.w(TAG, "koi stream nahi mili (live/premium/region/bot-check?)")
            return null
        }

        val title = try { extractor.name } catch (t: Throwable) { null }
        val first = candidates.first()
        if (!validate) {
            Log.i(TAG, "chuna gaya (bina validate): ${first.second} (candidates=${candidates.size})")
            val outNv = Result(first.first, title, extraAudio)
            if (preferHeight > 0) putCachedVideo(ck, outNv)
            return outNv
        }

        for ((url, label) in candidates) {
            if (validateUrl(url)) {
                Log.i(TAG, "chuna gaya: $label (candidates=${candidates.size})")
                val outV = Result(url, title, extraAudio)
                if (preferHeight > 0) putCachedVideo(ck, outV)
                return outV
            } else {
                Log.w(TAG, "URL fail hua: $label")
            }
        }
        Log.w(TAG, "sab validation fail — pehla candidate phir bhi bhej rahe hain: ${first.second}")
        val outT = Result(first.first, title, extraAudio)
        if (preferHeight > 0) putCachedVideo(ck, outT)
        outT
    } catch (t: Throwable) {
        Log.e(TAG, "resolve failed", t)
        null
    }

    /* TEST: page item load karte hi stream tayyar ho jaye -> MPV foran shuru (der nahi) */
    private val videoCache = HashMap<String, Pair<Long, Result>>()

    private fun cachedVideo(key: String): Result? = synchronized(videoCache) {
        val e = videoCache[key] ?: return null
        if (System.currentTimeMillis() - e.first > 120_000L) { videoCache.remove(key); return null }
        e.second
    }

    private fun putCachedVideo(key: String, r: Result) {
        synchronized(videoCache) {
            videoCache[key] = System.currentTimeMillis() to r
            if (videoCache.size > 8) videoCache.keys.firstOrNull()?.let { videoCache.remove(it) }
        }
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

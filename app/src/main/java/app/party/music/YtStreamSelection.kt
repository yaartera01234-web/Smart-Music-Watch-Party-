package app.party.music

import org.json.JSONObject

/** Strict metadata parsing shared by the Android resolver and unit tests. */
internal object YtStreamSelection {
    data class Streams(val url: String, val audioUrl: String?, val height: Int,
                       val audioCodec: String, val title: String?, val userAgent: String, val referer: String?)

    fun format(height: Int): String {
        if (height <= 0) return "bestaudio[ext=m4a]/bestaudio"
        require(height in listOf(144, 240, 360))
        return "bestvideo[height=$height][vcodec^=avc1]+bestaudio[ext=m4a]/" +
            "bestvideo[height=$height]+bestaudio/best[height=$height]"
    }
    private fun text(o: JSONObject, key: String) = o.optString(key, "").takeUnless { it == "null" }.orEmpty()
    private fun codec(o: JSONObject, key: String) = text(o, key).takeUnless { it == "none" }.orEmpty()
    private fun url(o: JSONObject): String = text(o, "url").also {
        require(it.startsWith("https://") && !it.contains('\n') && !it.contains('\r')) { "Missing HTTPS stream" }
    }
    fun parse(raw: String, height: Int, fallbackUA: String): Streams {
        val info = JSONObject(raw)
        val requested = info.optJSONArray("requested_formats")
        val entries = if (requested != null) (0 until requested.length()).map { requested.getJSONObject(it) } else listOf(info)
        val audio = entries.firstOrNull { codec(it, "acodec").isNotBlank() }
            ?: throw IllegalArgumentException("Audio stream missing")
        val video = if (height > 0) entries.firstOrNull { codec(it, "vcodec").isNotBlank() }
            ?: throw IllegalArgumentException("Video stream missing") else null
        if (video != null) require(video.optInt("height", 0) == height) { "Requested height unavailable" }
        val primary = video ?: audio
        val headers = primary.optJSONObject("http_headers") ?: info.optJSONObject("http_headers") ?: JSONObject()
        fun header(name: String) = text(headers, name).takeIf { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }
        return Streams(url(primary), if (video != null && audio !== video) url(audio) else null,
            video?.optInt("height", 0) ?: 0, codec(audio, "acodec"),
            text(info, "title").takeIf { it.isNotBlank() }, header("User-Agent") ?: fallbackUA, header("Referer"))
    }
}

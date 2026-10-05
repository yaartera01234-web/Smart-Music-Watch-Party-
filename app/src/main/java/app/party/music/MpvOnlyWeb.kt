package app.party.music

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI

/** Applies only to WebView. Native yt-dlp/MPV HTTP is intentionally untouched. */
internal object MpvOnlyWeb {
    fun policy(url: String): String {
        val uri = try { URI(url) } catch (_: Exception) { return "allow" }
        val host = uri.host?.lowercase() ?: return "allow"
        val path = uri.path.orEmpty()
        fun hostIs(domain: String) = host == domain || host.endsWith(".$domain")
        if (hostIs("youtube.com") && (path == "/iframe_api" || path == "/player_api")) return "adapter"
        // Metadata and thumbnails are permitted, never a player or media response.
        if (host == "img.youtube.com" || host == "i.ytimg.com" || host == "i9.ytimg.com") return "allow"
        if (hostIs("youtube.com") && path == "/oembed") return "allow"
        if (hostIs("youtube.com") || hostIs("youtube-nocookie.com") || hostIs("googlevideo.com") ||
            hostIs("youtubei.googleapis.com") || (host == "www.gstatic.com" && path.startsWith("/youtube/"))) return "block"
        return "allow"
    }
    fun response(context: Context, url: String): WebResourceResponse? = when (policy(url)) {
        "adapter" -> WebResourceResponse("application/javascript", "UTF-8", ByteArrayInputStream(listOf("sync-policy.js", "sync-room.js", "mpv-only.js").joinToString("\n") { context.assets.open(it).bufferedReader().use { reader -> reader.readText() } }.toByteArray(Charsets.UTF_8)))
        "block" -> WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        else -> null
    }
}

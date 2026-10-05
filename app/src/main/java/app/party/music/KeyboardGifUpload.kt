package app.party.music

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import java.util.UUID

/** Keyboard media only. No transcoding, disk copies, URL logging, or player changes. */
internal object KeyboardGifUpload {
    const val MAX_BYTES = 12 * 1024 * 1024
    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    data class Format(val mime: String, val extension: String)
    class Failure(val detail: String) : IOException(detail)

    fun readBounded(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (n == 0) continue
            if (output.size() > limit - n) throw Failure(if(limit == MAX_BYTES) "Image 12 MiB se bari hai" else "Upload server response bohat bara hai")
            output.write(chunk, 0, n)
        }
        if (output.size() == 0) throw Failure("Khali file / response mila")
        return output.toByteArray()
    }

    fun format(bytes: ByteArray): Format {
        fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 255) == values[it] }
        if (bytes.size >= 6 && (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a" || String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a")) return Format("image/gif", "gif")
        if (starts(137,80,78,71,13,10,26,10)) return Format("image/png", "png")
        if (starts(255,216,255)) return Format("image/jpeg", "jpg")
        if (bytes.size >= 12 && String(bytes,0,4,Charsets.US_ASCII)=="RIFF" && String(bytes,8,4,Charsets.US_ASCII)=="WEBP") return Format("image/webp", "webp")
        throw Failure("Keyboard file supported GIF/PNG/JPEG/WebP image nahi hai")
    }

    fun multipart(bytes: ByteArray, format: Format, boundary: String, backup: Boolean): ByteArray {
        val out=ByteArrayOutputStream()
        writeMultipart(out,bytes,format,boundary,backup)
        return out.toByteArray()
    }
    private fun writeMultipart(out: OutputStream, bytes: ByteArray, format: Format, boundary: String, backup: Boolean) {
        require(boundary.matches(Regex("[A-Za-z0-9-]{1,80}")))
        fun text(s: String) { out.write(s.toByteArray(Charsets.UTF_8)) }
        fun field(name: String, value: String) { text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n") }
        if (!backup) { field("reqtype", "fileupload");field("time", "72h") }
        val field = if (backup) "file" else "fileToUpload"
        text("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"wp-keyboard.${format.extension}\"\r\nContent-Type: ${format.mime}\r\n\r\n")
        out.write(bytes);text("\r\n--$boundary--\r\n")
    }

    fun allowedUrl(value: String, host: String, prefix: String = "/"): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { throw Failure("Upload server ne invalid link diya") }
        if (uri.scheme != "https" || uri.host != host || uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) || uri.rawFragment != null || !uri.path.startsWith(prefix)) throw Failure("Upload server ka link trusted host ka nahi")
        return uri.toASCIIString()
    }

    fun backupDownloadLink(page: String): String {
        // The backup host now supplies a signed /dl/<token>/<id>/<filename> URL.
        // Do NOT invent /dl/ by modifying its API URL: that gives HTTP403.
        val match = Regex("https://tmpfiles\\.org/dl/[^\\s\"'<>]+").find(page)
            ?: throw Failure("Backup server ka download link nahi mila")
        return allowedUrl(match.value.replace("&amp;", "&"), "tmpfiles.org", "/dl/")
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout=10000;readTimeout=25000;instanceFollowRedirects=false;useCaches=false
        setRequestProperty("User-Agent", UA)
    }
    private fun response(conn: HttpURLConnection, limit: Int): ByteArray {
        val status=conn.responseCode
        if (status !in 200..299) throw Failure("HTTP $status")
        return conn.inputStream.use { readBounded(it,limit) }
    }
    private fun post(url: String, bytes: ByteArray, format: Format, backup: Boolean): String {
        val boundary="----wp"+UUID.randomUUID().toString().replace("-", "")
        val framingSize=multipart(byteArrayOf(),format,boundary,backup).size
        val conn=open(url)
        try {
            conn.requestMethod="POST";conn.doOutput=true
            conn.setRequestProperty("Content-Type","multipart/form-data; boundary=$boundary")
            conn.setFixedLengthStreamingMode(framingSize+bytes.size)
            conn.outputStream.use { writeMultipart(it,bytes,format,boundary,backup) }
            return String(response(conn,65536),Charsets.UTF_8).trim()
        } finally { conn.disconnect() }
    }
    private fun get(url: String, limit: Int): ByteArray {
        val conn=open(url)
        try { return response(conn,limit) } finally { conn.disconnect() }
    }
    private fun verifyImage(url: String, original: ByteArray): String {
        // Stream the comparison: no second full image or multipart copy in Android RAM.
        val conn=open(url)
        try {
            val status=conn.responseCode
            if(status !in 200..299) throw Failure("Image verify HTTP $status")
            conn.inputStream.use { input ->
                val chunk=ByteArray(8192)
                var offset=0
                while(true) {
                    val n=input.read(chunk)
                    if(n<0) break
                    if(n==0) continue
                    if(n>original.size-offset) throw Failure("Uploaded image size match nahi hui")
                    for(i in 0 until n) if(chunk[i]!=original[offset+i]) throw Failure("Uploaded image verify nahi hui; GIF send nahi ki")
                    offset+=n
                }
                if(offset!=original.size) throw Failure("Uploaded image adhoori mili; GIF send nahi ki")
            }
            return url
        } finally { conn.disconnect() }
    }

    fun primary(bytes: ByteArray, format: Format): String {
        val link=post("https://litterbox.catbox.moe/resources/internals/api.php",bytes,format,false)
        return verifyImage(allowedUrl(link,"litter.catbox.moe"),bytes)
    }
    /** Short-lived backup, automatically attempted once after primary failure (user-authorized). */
    fun backup(bytes: ByteArray, format: Format): String {
        val json=post("https://tmpfiles.org/api/v1/upload",bytes,format,true)
        // API only needs a URL string; narrowly accept a plain trusted HTTPS URL, no escaped strings.
        val match=Regex("\"url\"\\s*:\\s*\"(https://tmpfiles\\.org/[^\"\\\\\\s]+)\"").find(json)
            ?: throw Failure("Backup upload ka response invalid tha")
        val page=allowedUrl(match.groupValues[1],"tmpfiles.org")
        val html=String(get(page,512*1024),Charsets.UTF_8)
        return verifyImage(backupDownloadLink(html),bytes)
    }
    fun reason(error: Exception): String = when(error) {
        is Failure -> error.detail
        is SocketTimeoutException -> "Upload server timeout"
        is javax.net.ssl.SSLException -> "Upload server TLS connection fail"
        is java.net.UnknownHostException -> "Upload host DNS resolve nahi hua"
        is IOException -> "Upload connection fail (${error.javaClass.simpleName})"
        else -> "Upload fail (${error.javaClass.simpleName})"
    }
}

package app.party.music

import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

/**
 * WebView jo Gboard ke GIF/sticker bhejne ko support karta hai.
 *
 * Wajah: Android me keyboard kisi bhi field me GIF/sticker sirf tab bhejta hai jab wo field
 * khud ko "main image/gif le sakta hoon" declare kare (contentMimeTypes) aur commitContent
 * sambhale. Plain WebView ye declare nahi karta, is liye Gboard "GIF not supported" dikhata hai.
 * Yahan hum apna InputConnection wrapper laga kar wo support dete hain, phir GIF ko upload kar
 * ke chat me bhej dete hain (page ka window.wpSendGif hook).
 */
class GifWebView(context: Context) : WebView(context) {

    var onGif: ((String) -> Unit)? = null          // upload hokar URL mil gaya
    var onGifError: ((String) -> Unit)? = null     // upload / read fail

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        EditorInfoCompat.setContentMimeTypes(
            outAttrs,
            arrayOf("image/gif", "image/png", "image/jpeg", "image/webp")
        )
        return InputConnectionCompat.createWrapper(ic, outAttrs) { info, flags, _ ->
            if (Build.VERSION.SDK_INT >= 25 &&
                (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
            ) {
                try {
                    info.requestPermission()
                } catch (t: Throwable) {
                    return@createWrapper false
                }
            }
            val uri: Uri = info.contentUri
            val mime = info.description?.mimeType ?: "image/gif"
            thread { handle(uri, mime) }
            true
        }
    }

    private fun handle(uri: Uri, mime: String) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                post { onGifError?.invoke("GIF khali tha") }
                return
            }
            val link = upload(bytes, mime)
            if (link != null) post { onGif?.invoke(link) }
            else post { onGifError?.invoke("GIF upload nahi hua — net check karein") }
        } catch (t: Throwable) {
            post { onGifError?.invoke("GIF padha nahi ja saka") }
        }
    }

    /** litterbox (catbox ka temporary server): 72 ghante ka link, CORS khula, mobile/datacenter dono se chalta hai. */
    private fun upload(bytes: ByteArray, mime: String): String? {
        val boundary = "----wp" + UUID.randomUUID().toString().replace("-", "")
        val conn = (URL("https://litterbox.catbox.moe/resources/internals/api.php")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 90000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out ->
                fun partHeader(name: String, value: String) {
                    out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray())
                }
                partHeader("reqtype", "fileupload")
                partHeader("time", "72h")
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"fileToUpload\"; " +
                        "filename=\"wp_${System.currentTimeMillis()}.gif\"\r\nContent-Type: $mime\r\n\r\n").toByteArray())
                out.write(bytes)
                out.write("\r\n".toByteArray())
                out.write(("--$boundary--\r\n").toByteArray())
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().readText().trim()
            return if (body.startsWith("http")) body else null
        } catch (t: Throwable) {
            return null
        } finally {
            conn.disconnect()
        }
    }
}

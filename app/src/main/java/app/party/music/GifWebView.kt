package app.party.music

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Rich keyboard images only. Built-in GIF links and playback are unaffected. */
class GifWebView(context: Context) : WebView(context) {
    var onGif: ((String, String) -> Unit)? = null
    var onGifError: ((String) -> Unit)? = null
    private val busy = AtomicBoolean(false)

    companion object {
        // Mirrors wpSendGif routing, but requires an active conversation. Include a page nonce
        // so a reload cannot accidentally send an old upload into a newly opened conversation.
        const val DESTINATION_JS = """(function(){try{
          if(!window.__wpKeyboardGifPage)window.__wpKeyboardGifPage=Date.now()+':'+Math.random();
          var sheet=document.getElementById('dm-sheet'),cv=document.getElementById('dm-view-chat');
          if(sheet&&sheet.classList.contains('on')){
            if(cv&&cv.classList.contains('on')&&window.DM&&typeof DM._curChat==='function'&&DM._curChat())
              return JSON.stringify([window.__wpKeyboardGifPage,'dm',DM._curChat()]);
            return '';
          }
          if(window.wpRoomJoined&&window.wpRoomJoined()&&typeof TP!=='undefined'&&TP.chat)
            return JSON.stringify([window.__wpKeyboardGifPage,'room',TP.chat]);
          return '';
        }catch(e){return '';}})()"""
    }

    private fun alive(): Boolean {
        val activity = context as? Activity
        return isAttachedToWindow && (activity == null || (!activity.isFinishing && !activity.isDestroyed))
    }
    private fun finish(message: String? = null) {
        busy.set(false)
        post { if (alive() && message != null) onGifError?.invoke(message) }
    }
    private fun destination(callback: (String) -> Unit) {
        if (!alive()) { callback("");return }
        val pending=AtomicBoolean(true)
        val timeout=Runnable { if(pending.compareAndSet(true,false)) callback("") }
        postDelayed(timeout,5000)
        try {
            evaluateJavascript(DESTINATION_JS) { result ->
                if(pending.compareAndSet(true,false)) {
                    removeCallbacks(timeout)
                    val value=try { JSONTokener(result ?: "null").nextValue() as? String ?: "" } catch (_: Exception) { "" }
                    callback(value)
                }
            }
        } catch (_: Exception) {
            removeCallbacks(timeout)
            if(pending.compareAndSet(true,false)) callback("")
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        EditorInfoCompat.setContentMimeTypes(outAttrs,arrayOf("image/gif","image/png","image/jpeg","image/webp"))
        return InputConnectionCompat.createWrapper(ic,outAttrs) { info,flags,_ ->
            if (!busy.compareAndSet(false,true)) {
                post { if(alive()) onGifError?.invoke("Pehli keyboard GIF abhi process ho rahi hai") }
                return@createWrapper true
            }
            val permission = AtomicBoolean(false)
            fun release() { if (permission.compareAndSet(true,false)) try { info.releasePermission() } catch (_: Exception) {} }
            if (Build.VERSION.SDK_INT >= 25 && (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                try { info.requestPermission();permission.set(true) }
                catch (_: Exception) { finish("Keyboard image ki read permission nahi mili");return@createWrapper true }
            }
            // Capture routing on the UI thread before reading/uploading. destination() has a
            // timeout, so even an unresponsive renderer cannot retain the provider grant forever.
            post {
                destination { target ->
                    if (target.isEmpty()) { release();finish("Pehle DM ya joined room chat kholein") }
                    else thread(name="wp-keyboard-image") {
                        val bytes = try {
                            context.contentResolver.openInputStream(info.contentUri)?.use { KeyboardGifUpload.readBounded(it) }
                                ?: throw KeyboardGifUpload.Failure("Keyboard image open nahi hui")
                        } catch (e: Exception) {
                            finish("Keyboard image read fail: ${KeyboardGifUpload.reason(e)}");null
                        } finally { release() }
                        if (bytes != null) {
                            val format = try { KeyboardGifUpload.format(bytes) } catch(e: Exception) { finish(KeyboardGifUpload.reason(e));null }
                            if (format != null) upload(bytes,format,target,false)
                        }
                    }
                }
            }
            true
        }
    }

    /** Original host once, then backup once. No popup or intermediate status. */
    private fun upload(bytes: ByteArray, format: KeyboardGifUpload.Format, target: String, backup: Boolean) {
        try {
            val link=if(backup) KeyboardGifUpload.backup(bytes,format) else KeyboardGifUpload.primary(bytes,format)
            post {
                if (alive()) onGif?.invoke(link,target)
                finish()
            }
        } catch(e: Exception) {
            if(backup) finish("GIF send nahi hui — dobara try karein")
            else post { startBackup(bytes,format,target) }
        }
    }

    // User-authorized silent fallback; re-check the destination before uploading elsewhere.
    private fun startBackup(bytes: ByteArray, format: KeyboardGifUpload.Format, target: String) {
        if (!alive()) { finish();return }
        destination { current ->
            if(current!=target || !alive()) { finish("Chat badal gayi — GIF send nahi hui");return@destination }
            thread(name="wp-keyboard-backup") { upload(bytes,format,target,true) }
        }
    }
}

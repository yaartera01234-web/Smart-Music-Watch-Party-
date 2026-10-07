package app.party.music

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException

class KeyboardGifUploadTest {
    private fun rejects(block: () -> Unit) {
        try { block();fail("Expected rejection") } catch (_: KeyboardGifUpload.Failure) {}
    }
    @Test fun gifSignatures() {
        for(header in listOf("GIF87a","GIF89a")) assertEquals("gif",KeyboardGifUpload.format(header.toByteArray()).extension)
    }
    @Test fun otherKeyboardFormatsKeepCorrectMimeAndExtension() {
        val png=byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
        assertEquals(KeyboardGifUpload.Format("image/png","png"),KeyboardGifUpload.format(png))
        assertEquals(KeyboardGifUpload.Format("image/jpeg","jpg"),KeyboardGifUpload.format(byteArrayOf(255.toByte(),216.toByte(),255.toByte())))
        assertEquals(KeyboardGifUpload.Format("image/webp","webp"),KeyboardGifUpload.format("RIFF0000WEBPdata".toByteArray()))
    }
    @Test fun unsupportedAndShortFilesRejected() {
        for(s in listOf("", "GIF", "GIF89z", "<html>", "RIFF0000WAVEdata")) rejects { KeyboardGifUpload.format(s.toByteArray()) }
    }
    @Test fun boundedReadAcceptsExactLimitAndPreservesBytes() {
        val b=ByteArray(16384){(it%251).toByte()}
        assertArrayEquals(b,KeyboardGifUpload.readBounded(ByteArrayInputStream(b),b.size))
    }
    @Test fun emptyReadRejected() { rejects { KeyboardGifUpload.readBounded(ByteArrayInputStream(byteArrayOf())) } }
    @Test fun overLimitReadRejected() { rejects { KeyboardGifUpload.readBounded(ByteArrayInputStream(ByteArray(8193)),8192) } }
    @Test fun primaryMultipartFieldsAndFileBytes() {
        val bytes="GIF89a\u0000\u0001\r\nabc".toByteArray()
        val body=String(KeyboardGifUpload.multipart(bytes,KeyboardGifUpload.format(bytes),"test-boundary",false),Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"reqtype\"\r\n\r\nfileupload\r\n--test-boundary\r\n"))
        assertTrue(body.contains("name=\"time\"\r\n\r\n72h\r\n--test-boundary\r\n"))
        assertTrue(body.contains("name=\"fileToUpload\"; filename=\"wp-keyboard.gif\"\r\nContent-Type: image/gif\r\n\r\n"))
        assertTrue(body.endsWith(String(bytes,Charsets.ISO_8859_1)+"\r\n--test-boundary--\r\n"))
    }
    @Test fun backupMultipartHasSingleFileAndCorrectWebpType() {
        val bytes="RIFF0000WEBPdata".toByteArray()
        val body=String(KeyboardGifUpload.multipart(bytes,KeyboardGifUpload.format(bytes),"wp-test",true))
        assertTrue(body.contains("name=\"file\"; filename=\"wp-keyboard.webp\""))
        assertTrue(body.contains("Content-Type: image/webp\r\n"))
        assertFalse(body.contains("fileToUpload"));assertFalse(body.contains("reqtype"));assertFalse(body.contains("name=\"time\""))
    }
    @Test fun uploadUrlHostAllowlist() {
        assertEquals("https://litter.catbox.moe/a.gif",KeyboardGifUpload.allowedUrl("https://litter.catbox.moe/a.gif","litter.catbox.moe"))
        for(url in listOf("http://litter.catbox.moe/a.gif","https://litter.catbox.moe.evil.test/a.gif","https://user@litter.catbox.moe/a.gif","https://litter.catbox.moe:444/a.gif","javascript:alert(1)","https://litter.catbox.moe/a.gif#x","not a URL")) rejects { KeyboardGifUpload.allowedUrl(url,"litter.catbox.moe") }
    }
    @Test fun signedBackupDownloadLinkPreserved() {
        val url="https://tmpfiles.org/dl/1791224416.007f5eb07263f983/wYA4fiNikdKu/wp-keyboard.gif"
        assertEquals(url,KeyboardGifUpload.backupDownloadLink("<img src=\"$url\"><a href=\"$url\">Download</a>"))
    }
    @Test fun backupParserDoesNotInventDownloadUrl() {
        rejects { KeyboardGifUpload.backupDownloadLink("https://tmpfiles.org/123/wp-keyboard.gif") }
        rejects { KeyboardGifUpload.backupDownloadLink("https://tmpfiles.org.evil.test/dl/123/a.gif") }
    }
    @Test fun downloadUrlQueryEntitiesDecoded() {
        assertEquals("https://tmpfiles.org/dl/a.gif?x=1&y=2",KeyboardGifUpload.backupDownloadLink("<img src='https://tmpfiles.org/dl/a.gif?x=1&amp;y=2'>"))
    }
    @Test fun errorReasonsDistinguishServerNetworkAndTimeout() {
        assertEquals("HTTP 412",KeyboardGifUpload.reason(KeyboardGifUpload.Failure("HTTP 412")))
        assertEquals("Upload server timeout",KeyboardGifUpload.reason(SocketTimeoutException()))
        assertTrue(KeyboardGifUpload.reason(java.net.UnknownHostException()).contains("DNS"))
        assertTrue(KeyboardGifUpload.reason(javax.net.ssl.SSLException("x")).contains("TLS"))
    }
}

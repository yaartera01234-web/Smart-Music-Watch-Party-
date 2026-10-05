package app.party.music

import org.junit.Assert.*
import org.junit.Test

class YtStreamSelectionTest {
    private val video = """{"url":"https://video.example/play?a=1,b=2","vcodec":"avc1","acodec":"none","height":144}"""
    private val audio = """{"url":"https://audio.example/play","vcodec":"none","acodec":"mp4a.40.2"}"""
    private fun parse(raw: String, height: Int = 144) = YtStreamSelection.parse(raw, height, "QA")
    @Test fun exact144WithAudio() {
        val r = parse("""{"title":"Sample","requested_formats":[$video,$audio]}""")
        assertEquals(144, r.height)
        assertEquals("https://audio.example/play", r.audioUrl)
        assertEquals("mp4a.40.2", r.audioCodec)
        assertEquals("Sample", r.title)
        assertEquals("QA", r.userAgent)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsSilentVideo() { parse(video) }
    @Test(expected = IllegalArgumentException::class) fun rejectsFalse144Label() {
        parse("""{"requested_formats":[${video.replace("144", "360")},$audio]}""")
    }
    @Test fun acceptsMuxedExact144() {
        val r = parse(video.replace("\"acodec\":\"none\"", "\"acodec\":\"aac\""))
        assertNull(r.audioUrl)
        assertEquals("aac", r.audioCodec)
    }
    @Test fun audioOnlyHandoff() { assertEquals(0, parse(audio, 0).height) }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonHttps() {
        parse("""{"requested_formats":[$video,${audio.replace("https://", "file://")}]}""")
    }
    @Test fun selectorsCannotFallBackTo360() {
        val f = YtStreamSelection.format(144)
        assertTrue(f.contains("[height=144]"))
        assertFalse(f.contains("360"))
        assertFalse(f.endsWith("/best"))
    }
    @Test fun headersAndReversedFormatOrder() {
        val raw = """{"http_headers":{"User-Agent":"Custom UA","Referer":"https://www.youtube.com/"},"requested_formats":[$audio,$video]}"""
        val r = parse(raw)
        assertEquals("Custom UA", r.userAgent)
        assertEquals("https://www.youtube.com/", r.referer)
        assertEquals(144, r.height)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingCodec() {
        parse("""{"url":"https://video.example/play","height":144}""")
    }
    @Test fun unsupportedQualityIsNotSilentlyAccepted() {
        assertThrows(IllegalArgumentException::class.java) { YtStreamSelection.format(720) }
    }
}

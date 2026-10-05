package app.party.music
import org.junit.Assert.*
import org.junit.Test
class MpvOnlyWebTest {
    @Test fun substitutesApiWithoutNetwork() {
        assertEquals("adapter",MpvOnlyWeb.policy("https://www.youtube.com/iframe_api"))
    }
    @Test fun blocksPlayersAndMedia() {
        for(url in listOf("https://www.youtube.com/embed/baYbQ4OOGM4", "https://www.youtube-nocookie.com/embed/baYbQ4OOGM4", "https://rr1.googlevideo.com/videoplayback?x=1", "https://youtubei.googleapis.com/youtubei/v1/player", "https://www.youtube.com/s/player/base.js"))assertEquals(url,"block",MpvOnlyWeb.policy(url))
    }
    @Test fun permitsMetadataAndChat() {
        for(url in listOf("https://img.youtube.com/vi/baYbQ4OOGM4/mqdefault.jpg", "https://www.youtube.com/oembed?url=test", "https://yaartera01234-web.github.io/watch-party/party-final1.html", "https://i.ytimg.com/vi/test/default.jpg"))assertEquals(url,"allow",MpvOnlyWeb.policy(url))
    }
    @Test fun exactDomainMatching() {
        assertEquals("allow",MpvOnlyWeb.policy("https://notgooglevideo.com/test"))
        assertEquals("allow",MpvOnlyWeb.policy("https://youtube.com.example.org/test"))
        assertEquals("block",MpvOnlyWeb.policy("https://youtube.com/watch?v=test"))
    }
}

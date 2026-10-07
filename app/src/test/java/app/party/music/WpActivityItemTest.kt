package app.party.music
import org.junit.Assert.*
import org.junit.Test
class WpActivityItemTest {
    @Test fun parsesPageJson() {
        val item = WpActivityItem.parse("""{"name":"Ahmed","kind":"pause","t1":"Paused","t2":"00:15:00","ms":5000}""")
        assertNotNull(item)
        assertEquals("Ahmed", item!!.name); assertEquals("pause", item.kind); assertEquals("Paused", item.word)
        assertEquals("00:15:00", item.detail); assertEquals(5000L, item.durationMs)
    }
    @Test fun seekAndMessage() {
        val seek = WpActivityItem.parse("""{"name":"Ahmed","kind":"seek","t1":"Seek","t2":"00:15:10 → 00:15:20"}""")!!
        assertEquals("00:15:10 → 00:15:20", seek.detail); assertEquals(5000L, seek.durationMs)
        val msg = WpActivityItem.parse("""{"name":"Sara","kind":"msg","t2":"Bhai   yeh scene\n dekho 🔥"}""")!!
        assertEquals("", msg.word); assertEquals("Bhai yeh scene dekho 🔥", msg.detail)
    }
    @Test fun fallbackWordsAndLimits() {
        val join = WpActivityItem.parse("""{"name":"  ","kind":"JOIN"}""")!!
        assertEquals("Someone", join.name); assertEquals("Joined", join.word); assertEquals("", join.detail)
        val long = WpActivityItem.parse("""{"name":"${"n".repeat(40)}","kind":"msg","t2":"${"m".repeat(300)}","ms":99999}""")!!
        assertEquals(20, long.name.length); assertEquals(120, long.detail.length); assertEquals(15000L, long.durationMs)
    }
    @Test fun rejectsGarbage() {
        assertNull(WpActivityItem.parse(null))
        assertNull(WpActivityItem.parse("not json"))
        assertNull(WpActivityItem.parse("""{"name":"X","kind":"dance"}"""))
        assertNull(WpActivityItem.parse("""{"name":"X"}"""))
    }
}

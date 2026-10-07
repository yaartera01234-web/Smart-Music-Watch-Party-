package app.party.music
import java.util.concurrent.ConcurrentHashMap
/** Session-only language preference, keyed to the exact direct source; never shared to peers. */
internal object AudioTrackMemory {
    private val ids = ConcurrentHashMap<String, Int>()
    fun get(url: String): Int? = ids[url]
    fun put(url: String, id: Int) { if (ids.size > 24) ids.clear(); ids[url] = id }
}

package mihon.feature.weblibrary

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebLibraryRelayCacheTest {
    private val relay = WebLibraryRelay(listOf("turn:turn.cloudflare.com:3478?transport=udp"), "u", "c")
    private var clock = 0L
    private var calls = 0
    private var reply: () -> List<WebLibraryRelay> = { listOf(relay) }
    private val cache = WebLibraryRelayCache(keepMs = 4 * HOUR, retryMs = 5 * MINUTE, now = { clock }) {
        calls++
        reply()
    }

    @Test
    fun `credentials are reused for hours, then fetched again`() = runTest {
        assertEquals(listOf(relay), cache.get("alice"))
        clock += 3 * HOUR
        assertEquals(listOf(relay), cache.get("alice"))
        assertEquals(1, calls)
        clock += 2 * HOUR
        cache.get("alice")
        assertEquals(2, calls)
    }

    @Test
    fun `a failure or an empty list waits a few minutes before asking again`() = runTest {
        reply = { error("Daily limit reached") }
        assertEquals(emptyList<WebLibraryRelay>(), cache.get("alice"))
        clock += MINUTE
        cache.get("alice")
        assertEquals(1, calls)
        clock += 5 * MINUTE
        reply = { emptyList() }
        cache.get("alice")
        cache.get("alice")
        assertEquals(2, calls)
    }

    @Test
    fun `another account fetches its own`() = runTest {
        cache.get("alice")
        cache.get("bob")
        assertEquals(2, calls)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}

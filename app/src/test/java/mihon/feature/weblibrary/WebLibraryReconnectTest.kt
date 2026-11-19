package mihon.feature.weblibrary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebLibraryReconnectTest {
    private val now = 1_000_000L

    @Test
    fun `a dropped connection keeps the phone available long enough for the browser to reconnect`() {
        assertEquals(now + WebLibraryHost.RECONNECT_GRACE_MS, WebLibraryHost.availableAfterEnd(0, now, failed = true))
    }

    @Test
    fun `a longer share window is kept`() {
        val shared = now + WebLibraryHost.AVAILABLE_MS
        assertEquals(shared, WebLibraryHost.availableAfterEnd(shared, now, failed = true))
    }

    @Test
    fun `a normal end changes nothing`() {
        assertEquals(0L, WebLibraryHost.availableAfterEnd(0, now, failed = false))
        assertEquals(now + 5, WebLibraryHost.availableAfterEnd(now + 5, now, failed = false))
    }
}

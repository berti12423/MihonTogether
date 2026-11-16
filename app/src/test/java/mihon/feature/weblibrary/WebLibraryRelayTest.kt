package mihon.feature.weblibrary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class WebLibraryRelayTest {
    private val urls =
        listOf("turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:443?transport=tcp")
    private val relay = mapOf("urls" to urls, "username" to "user1", "credential" to "pass1")

    @Test
    fun `valid relay servers are kept`() {
        assertEquals(
            listOf(WebLibraryRelay(urls, "user1", "pass1")),
            WebLibraryRelay.parse(mapOf("iceServers" to listOf(relay))),
        )
    }

    @Test
    fun `anything that is not a relay server with credentials is dropped`() {
        val bad = listOf(
            emptyMap(),
            mapOf("iceServers" to "x"),
            mapOf("iceServers" to listOf(null, 5, "x")),
            mapOf("iceServers" to listOf(relay - "username")),
            mapOf("iceServers" to listOf(relay - "credential")),
            mapOf(
                "iceServers" to listOf(relay + ("urls" to listOf("https://evil.example", "stun:stun.example:3478", 7))),
            ),
            mapOf("iceServers" to listOf(relay + ("urls" to "turn:turn.cloudflare.com:3478?transport=udp"))),
        )
        bad.forEach { assertEquals(emptyList<WebLibraryRelay>(), WebLibraryRelay.parse(it), it.toString()) }
    }

    @Test
    fun `invalid urls are removed from a server and the list is capped`() {
        val mixed = relay + ("urls" to (urls + "turn:turn.cloudflare.com:53"))
        assertEquals(urls, WebLibraryRelay.parse(mapOf("iceServers" to listOf(mixed))).single().urls)
        assertEquals(4, WebLibraryRelay.parse(mapOf("iceServers" to List(20) { relay })).size)
    }

    @Test
    fun `credentials are not printed`() {
        val text = WebLibraryRelay(urls, "user1", "pass1").toString()
        assertFalse(text.contains("user1") || text.contains("pass1"))
    }
}

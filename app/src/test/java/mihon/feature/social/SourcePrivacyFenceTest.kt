package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourcePrivacyFenceTest {
    @Test
    fun `republic then reprivatize one source while another stays private revokes again`() {
        val fence = SourcePrivacyFence(setOf("source-a", "source-b"))
        assertFalse(fence.reconcile(setOf("source-b")))
        assertEquals(setOf("source-b"), fence.handled)
        // Source A can now be intentionally republished while source B stays private.
        assertTrue(fence.reconcile(setOf("source-a", "source-b")))
        // An offline/failed server call must not acknowledge the new private source.
        assertTrue(fence.reconcile(setOf("source-a", "source-b")))
        fence.acknowledge(setOf("source-a", "source-b"))
        assertFalse(fence.reconcile(setOf("source-a", "source-b")))
    }
}

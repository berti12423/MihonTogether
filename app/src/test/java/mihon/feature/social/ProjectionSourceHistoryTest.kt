package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectionSourceHistoryTest {
    @Test
    fun `removing last local title keeps its source observed and revocable`() {
        val history = ProjectionSourceHistory(emptySet())
        history.include(listOf(123L))

        val observed = history.include(emptyList())
        assertEquals(setOf(123L), observed)
        assertEquals(setOf("123"), history.retained)

        val fence = SourcePrivacyFence(emptySet())
        val currentlyPrivate = observed.filter { it == 123L }.map { it.toString() }.toSet()
        assertTrue(fence.reconcile(currentlyPrivate), "Incognito still revokes the previously published source")
    }

    @Test
    fun `restart restores source history even when local library is empty`() {
        val original = ProjectionSourceHistory(setOf("123"))
        original.include(listOf(456L, 123L))

        val restarted = ProjectionSourceHistory(original.retained)
        assertEquals(setOf(123L, 456L), restarted.include(emptyList()))
        assertEquals(setOf("123", "456"), restarted.retained)
    }

    @Test
    fun `restored history permits another privacy fence after public then private transition`() {
        val history = ProjectionSourceHistory(setOf("101", "202"))
        val restoredObservers = history.include(emptyList())
        val fence = SourcePrivacyFence(setOf("101", "202"))

        // Source 101 becomes public while source 202 remains private.
        val stillPrivate = restoredObservers.filter { it == 202L }.map { it.toString() }.toSet()
        assertFalse(fence.reconcile(stillPrivate))
        assertEquals(setOf("202"), fence.handled)

        // Both source history and successful acknowledgements survive a process restart.
        val restartedHistory = ProjectionSourceHistory(history.retained)
        val restartedFence = SourcePrivacyFence(fence.handled)
        val bothPrivate = restartedHistory.include(emptyList()).map { it.toString() }.toSet()
        assertTrue(restartedFence.reconcile(bothPrivate))
        assertTrue(restartedFence.reconcile(bothPrivate), "Failed/offline revocation must remain unacknowledged")
        restartedFence.acknowledge(bothPrivate)
        assertFalse(restartedFence.reconcile(bothPrivate))
    }

    @Test
    fun `malformed non canonical and non positive source ids are excluded`() {
        val history = ProjectionSourceHistory(
            setOf(
                "7", Long.MAX_VALUE.toString(), "", "text", "0", "-1", "+7", "07", "7.0", " 7", "9223372036854775808",
            ),
        )

        assertEquals(setOf(7L, Long.MAX_VALUE), history.include(listOf(0L, -3L, Long.MIN_VALUE, 7L)))
        assertEquals(setOf("7", Long.MAX_VALUE.toString()), history.retained)
    }
}

package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SocialNudgesTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")

    private fun xp(streak: Int, day: String) = SocialXp(total = 100, level = 1, streak = streak, day = day)

    @Test
    fun `streak of 2 or more that last ran yesterday is at risk`() {
        assertTrue(SocialNudges.streakAtRisk(xp(2, "20261005"), now))
        assertTrue(SocialNudges.streakAtRisk(xp(12, "20261005"), now))
    }

    @Test
    fun `read today, older or short streaks are not at risk`() {
        assertFalse(SocialNudges.streakAtRisk(xp(5, "20261006"), now))
        assertFalse(SocialNudges.streakAtRisk(xp(5, "20261004"), now))
        assertFalse(SocialNudges.streakAtRisk(xp(1, "20261005"), now))
        assertFalse(SocialNudges.streakAtRisk(xp(0, ""), now))
    }

    @Test
    fun `no stats means no icon`() {
        assertFalse(SocialNudges.streakAtRisk(null, now))
    }

    @Test
    fun `month boundary counts yesterday in UTC`() {
        assertTrue(SocialNudges.streakAtRisk(xp(3, "20260930"), Instant.parse("2026-10-01T00:30:00Z")))
    }

    @Test
    fun `cool down lasts 24 hours`() {
        val at = 1_000_000L
        assertTrue(SocialNudges.isCoolingDown(at, at + SocialNudges.COOL_DOWN_MS - 1))
        assertFalse(SocialNudges.isCoolingDown(at, at + SocialNudges.COOL_DOWN_MS))
        assertFalse(SocialNudges.isCoolingDown(null, at))
    }

    @Test
    fun `stored nudges round trip and drop stale or broken entries`() {
        val at = 5_000_000_000L
        val stored =
            SocialNudges.encode(mapOf("a" to at, "b" to at - SocialNudges.COOL_DOWN_MS - 1)) + setOf("bad", "|1")
        assertEquals(mapOf("a" to at), SocialNudges.decode(stored, at + 10))
    }
}

package mihon.feature.social.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StripFollowerTest {
    private fun ms(value: Long) = value * 1_000_000

    @Test
    fun `plays the host back slightly delayed, between received positions`() {
        val follower = StripFollower(delaySec = 0.6)
        follower.onTarget(1.0, ms(0))
        follower.onTarget(1.5, ms(500))
        follower.onTarget(2.0, ms(1000))
        // 1250 ms now = 650 ms in playback: 30% from the 500 ms sample to the 1000 ms one.
        assertEquals(1.65, follower.predicted(ms(1250))!!, 1e-9)
    }

    @Test
    fun `never overshoots when the host stops or slows down`() {
        val follower = StripFollower(delaySec = 0.6)
        follower.onTarget(1.0, ms(0))
        follower.onTarget(2.0, ms(500)) // fast scrolling...
        follower.onTarget(2.1, ms(1000)) // ...then nearly stopped
        val positions = (0..60).map { follower.predicted(ms(500 + it * 50L))!! }
        assertTrue(positions.zipWithNext().all { (a, b) -> b >= a }, "only moves forward: $positions")
        assertEquals(2.1, positions.last(), 1e-9)
    }

    @Test
    fun `page jumps snap and a pause glides from the resting position`() {
        val follower = StripFollower(delaySec = 0.6)
        follower.onTarget(1.0, ms(0))
        follower.onTarget(9.0, ms(500)) // a jump, not scrolling
        assertEquals(9.0, follower.predicted(ms(600))!!, 1e-9)

        follower.onTarget(9.5, ms(5_000)) // host resumes after a pause
        assertEquals(9.0, follower.predicted(ms(5_000))!!, 1e-9) // starts where it rests
        assertEquals(9.25, follower.predicted(ms(5_300))!!, 1e-9) // glides
        assertEquals(9.5, follower.predicted(ms(5_700))!!, 1e-9)
    }

    @Test
    fun `easing closes the gap smoothly and settles`() {
        val follower = StripFollower(delaySec = 0.6, easeSec = 0.08)
        assertEquals(0.0, follower.step(0.0, 0.016), 1e-9)
        val firstFrame = follower.step(100.0, 0.016)
        assertTrue(firstFrame > 10 && firstFrame < 30, "moved $firstFrame")
        follower.onTarget(3.0, ms(0))
        assertFalse(follower.settled(ms(100), 0.4)) // playback hasn't reached the sample yet
        assertTrue(follower.settled(ms(700), 0.4))
        follower.reset()
        assertNull(follower.predicted(ms(800)))
    }
}

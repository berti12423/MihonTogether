package mihon.feature.social.reader

import kotlin.math.abs
import kotlin.math.exp

/**
 * Smooths a long-strip follower between the host's position updates (a couple per second, with network jitter), like
 * netcode does for other players: it plays the host's movement back [delaySec] behind real time, interpolating between
 * two positions it actually received. Unlike extrapolating ahead, this never overshoots when the host slows down or
 * stops, so there is no "scroll past, then jump back". The cost is a constant, small delay.
 *
 * Positions are in pages from the chapter start ([ReaderSocialState] strip positions); time is in nanoseconds.
 */
class StripFollower(
    private val delaySec: Double = 0.6,
    private val easeSec: Double = 0.08,
) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** A new host position arrived at [nowNanos]. */
    fun onTarget(strip: Double, nowNanos: Long) {
        val last = samples.lastOrNull()
        when {
            last == null -> Unit
            // A jump (page or chapter change) isn't scrolling: go straight there.
            abs(strip - last.second) >= JUMP_PAGES -> samples.clear()
            // After a pause, glide from where the follower already rests instead of replaying old history.
            (nowNanos - last.first) / 1e9 > PAUSE_SEC -> {
                samples.clear()
                samples.addLast(nowNanos - (delaySec * 1e9).toLong() to last.second)
            }
        }
        samples.addLast(nowNanos to strip)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    /** Where the follower should be now: the host's position [delaySec] ago, between two received positions. */
    fun predicted(nowNanos: Long): Double? {
        if (samples.isEmpty()) return null
        val render = nowNanos - (delaySec * 1e9).toLong()
        if (render <= samples.first().first) return samples.first().second
        if (render >= samples.last().first) return samples.last().second // never guess past the last real position
        val after = samples.indexOfFirst { it.first > render }
        val (t0, s0) = samples[after - 1]
        val (t1, s1) = samples[after]
        return s0 + (s1 - s0) * (render - t0).toDouble() / (t1 - t0)
    }

    /** Pixels to scroll this frame to close [errorPx] smoothly, frame-rate independent. */
    fun step(errorPx: Double, frameSec: Double): Double = errorPx * (1 - exp(-frameSec / easeSec))

    /** True once there's nothing left to animate: close enough and the playback reached the last position. */
    fun settled(nowNanos: Long, errorPx: Double): Boolean {
        val last = samples.lastOrNull() ?: return true
        return abs(errorPx) < 1.0 && nowNanos - (delaySec * 1e9).toLong() >= last.first
    }

    fun reset() {
        samples.clear()
    }

    private companion object {
        const val JUMP_PAGES = 3.0
        const val PAUSE_SEC = 2.0
        const val MAX_SAMPLES = 8
    }
}

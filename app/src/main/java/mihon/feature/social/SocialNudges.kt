package mihon.feature.social

import java.time.Instant

/** Friend nudges: the "streak at risk" decision and the locally remembered 24 h cool-down. */
object SocialNudges {
    /** Matches the backend's one nudge per pair per 24 h. */
    const val COOL_DOWN_MS = 24 * 60 * 60 * 1000L

    /**
     * A friend's streak is at risk when it is worth keeping (2+ days) and their last reading day was yesterday (UTC):
     * nothing yet today, so it breaks tonight. [xp] is null when they don't share stats (then no icon).
     */
    fun streakAtRisk(xp: SocialXp?, now: Instant = Instant.now()): Boolean {
        xp ?: return false
        return xp.streak >= 2 && xp.day == FriendList.statsDay(now.minusSeconds(SECONDS_PER_DAY))
    }

    /** Nudged within the last 24 h (the clock going backwards counts as still nudged). */
    fun isCoolingDown(nudgedAt: Long?, now: Long): Boolean = nudgedAt != null && now - nudgedAt < COOL_DOWN_MS

    /** Stored as `uid|millis` strings; stale and malformed entries are dropped. */
    fun decode(stored: Set<String>, now: Long): Map<String, Long> = stored.mapNotNull { entry ->
        val uid = entry.substringBefore('|', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val at = entry.substringAfter('|', "").toLongOrNull() ?: return@mapNotNull null
        (uid to at).takeIf { isCoolingDown(at, now) }
    }.toMap()

    fun encode(nudges: Map<String, Long>): Set<String> = nudges.mapTo(mutableSetOf()) { (uid, at) -> "$uid|$at" }

    private const val SECONDS_PER_DAY = 24 * 60 * 60L
}

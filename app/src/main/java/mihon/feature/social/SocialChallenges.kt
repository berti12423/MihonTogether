package mihon.feature.social

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The XP rules shown in "How XP works", mirroring the backend constants (`XP`, `MAX_FREEZES`, `CHALLENGE_XP` and
 * `xpForLevel` in `backend/src/domain.ts`). `XpRulesTest` asserts the same literal list as the backend's "XP rules
 * table" test, so a change on one side fails a test until both match.
 */
object XpRules {
    const val CHAPTER = 10
    const val TOGETHER = 5
    const val STREAK_DAY = 5
    const val STREAK_MAX_DAYS = 7
    const val DAILY_CAP = 1_000
    const val MAX_FREEZES = 2

    /** A freeze is earned each time the streak reaches a multiple of this. */
    const val FREEZE_EVERY_DAYS = 7
    const val CHALLENGE_XP = 100

    /** Levels listed in the explainer with the total XP each needs. */
    val SHOWN_LEVELS = listOf(2, 3, 5, 10, 15, 25, 40)
}

/** Reads an `xp/{uid}` document; missing or malformed fields fall back to zero (older docs lack the newer ones). */
internal fun parseXp(data: Map<String, Any?>): SocialXp {
    fun int(map: Map<*, *>, key: String) = (map[key] as? Number)?.toInt() ?: 0
    val total = (data["total"] as? Number)?.toLong() ?: 0
    val month = (data["month"] as? Map<*, *>)?.let { m ->
        (m["id"] as? String)?.let { id ->
            XpMonth(
                id = id,
                chapters = int(m, "chapters"),
                days = int(m, "days"),
                together = int(m, "together"),
                streak = int(m, "streak"),
                bestDay = int(m, "bestDay"),
                xp = int(m, "xp"),
            )
        }
    }
    return SocialXp(
        total = total,
        level = XpLevels.levelFor(total),
        today = int(data, "today"),
        chapters = int(data, "chapters"),
        together = int(data, "together"),
        streak = int(data, "streak"),
        bestStreak = int(data, "bestStreak"),
        freezes = int(data, "freezes"),
        badges = (data["badges"] as? Map<*, *>).orEmpty().mapNotNull { (id, at) ->
            (id as? String)?.let { it to ((at as? Number)?.toLong() ?: 0L) }
        }.toMap(),
        week = data["week"] as? String ?: "",
        weekXp = int(data, "weekXp"),
        weekChapters = int(data, "weekChapters"),
        day = data["day"] as? String ?: "",
        days = int(data, "days"),
        bestDay = int(data, "bestDay"),
        month = month,
        challenges = (data["challenges"] as? Map<*, *>).orEmpty().mapNotNull { (id, ids) ->
            (id as? String)?.let { it to (ids as? List<*>).orEmpty().filterIsInstance<String>() }
        }.toMap(),
    )
}

/** The `xp/{uid}.month` counter a challenge reads. */
enum class ChallengeField { CHAPTERS, DAYS, TOGETHER, STREAK, BEST_DAY, XP }

/** The backend's `CHALLENGES` pool. */
enum class Challenge(val id: String, val counter: ChallengeField, val min: Int, val icon: String) {
    CHAPTERS_100("chapters_100", ChallengeField.CHAPTERS, 100, "📚"),
    CHAPTERS_200("chapters_200", ChallengeField.CHAPTERS, 200, "🏛️"),
    DAYS_15("days_15", ChallengeField.DAYS, 15, "📅"),
    DAYS_20("days_20", ChallengeField.DAYS, 20, "🗓️"),
    TOGETHER_5("together_5", ChallengeField.TOGETHER, 5, "🤝"),
    TOGETHER_10("together_10", ChallengeField.TOGETHER, 10, "💞"),
    STREAK_7("streak_7", ChallengeField.STREAK, 7, "🔥"),
    STREAK_14("streak_14", ChallengeField.STREAK, 14, "⚔️"),
    BINGE_15("binge_15", ChallengeField.BEST_DAY, 15, "🌙"),
    XP_2000("xp_2000", ChallengeField.XP, 2000, "⭐"),
    ;

    /** "Read %d chapters" and friends, formatted with [min]. */
    val title: StringResource
        get() = when (counter) {
            ChallengeField.CHAPTERS -> MR.strings.challenge_chapters
            ChallengeField.DAYS -> MR.strings.challenge_days
            ChallengeField.TOGETHER -> MR.strings.challenge_together
            ChallengeField.STREAK -> MR.strings.challenge_streak
            ChallengeField.BEST_DAY -> MR.strings.challenge_binge
            ChallengeField.XP -> MR.strings.challenge_xp
        }

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** This month's counters from `xp/{uid}.month`. */
data class XpMonth(
    val id: String,
    val chapters: Int = 0,
    val days: Int = 0,
    val together: Int = 0,
    val streak: Int = 0,
    val bestDay: Int = 0,
    val xp: Int = 0,
) {
    fun value(field: ChallengeField) = when (field) {
        ChallengeField.CHAPTERS -> chapters
        ChallengeField.DAYS -> days
        ChallengeField.TOGETHER -> together
        ChallengeField.STREAK -> streak
        ChallengeField.BEST_DAY -> bestDay
        ChallengeField.XP -> xp
    }
}

/** One challenge's progress this month. */
data class ChallengeProgress(val challenge: Challenge, val value: Int, val done: Boolean) {
    val fraction get() = if (done) 1f else (value.toFloat() / challenge.min).coerceIn(0f, 1f)
}

/** Monthly challenges (UTC calendar month), like the backend's `CHALLENGE_ROTATION` and `challengesFor`. */
object MonthlyChallenges {
    /** January..December. */
    val ROTATION: List<List<Challenge>> = listOf(
        listOf(Challenge.CHAPTERS_100, Challenge.DAYS_15, Challenge.STREAK_7),
        listOf(Challenge.CHAPTERS_200, Challenge.TOGETHER_5, Challenge.BINGE_15),
        listOf(Challenge.DAYS_20, Challenge.TOGETHER_10, Challenge.XP_2000),
        listOf(Challenge.CHAPTERS_100, Challenge.STREAK_14, Challenge.TOGETHER_5),
        listOf(Challenge.DAYS_15, Challenge.BINGE_15, Challenge.XP_2000),
        listOf(Challenge.CHAPTERS_200, Challenge.DAYS_20, Challenge.TOGETHER_10),
        listOf(Challenge.CHAPTERS_100, Challenge.TOGETHER_5, Challenge.STREAK_14),
        listOf(Challenge.DAYS_20, Challenge.STREAK_7, Challenge.XP_2000),
        listOf(Challenge.CHAPTERS_200, Challenge.BINGE_15, Challenge.TOGETHER_10),
        listOf(Challenge.CHAPTERS_100, Challenge.DAYS_20, Challenge.TOGETHER_10),
        listOf(Challenge.DAYS_15, Challenge.STREAK_14, Challenge.XP_2000),
        listOf(Challenge.CHAPTERS_200, Challenge.TOGETHER_5, Challenge.STREAK_7),
    )

    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")

    /** The UTC month of [now], "yyyy-mm". */
    fun monthOf(now: Instant = Instant.now()): String = MONTH.format(now.atOffset(ZoneOffset.UTC))

    fun forMonth(month: String): List<Challenge> = ROTATION[YearMonth.parse(month, MONTH).monthValue - 1]

    fun seasonBadge(month: String) = "season_${month.replace('-', '_')}"

    /** The month of a `season_yyyy_mm` badge, or null for other IDs. */
    fun seasonMonth(badgeId: String): YearMonth? {
        val match = Regex("""season_(\d{4})_(\d{2})""").matchEntire(badgeId) ?: return null
        val (year, month) = match.destructured
        return runCatching { YearMonth.of(year.toInt(), month.toInt()) }.getOrNull()
    }

    /** "October 2026", for season badges and the challenge card. */
    fun monthName(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    /** [month]'s three challenges with my progress; counters from another month count as 0. */
    fun progress(xp: SocialXp?, month: String = monthOf()): List<ChallengeProgress> {
        val counters = xp?.month?.takeIf { it.id == month }
        val done = xp?.challenges?.get(month).orEmpty()
        return forMonth(month).map { challenge ->
            val value = counters?.value(challenge.counter) ?: 0
            ChallengeProgress(challenge, value, challenge.id in done || value >= challenge.min)
        }
    }

    /** Season badges I hold, newest first. */
    fun seasons(badges: Map<String, Long>): List<Pair<YearMonth, Long>> =
        badges.mapNotNull { (id, at) -> seasonMonth(id)?.let { it to at } }.sortedByDescending { it.first }
}

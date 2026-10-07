package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import java.util.Locale

class XpRulesTest {
    // The same literal list the backend's "XP rules table" test asserts against domain.ts.
    @Test
    fun `the XP rules match the backend's list`() {
        assertEquals(
            listOf(10, 5, 5, 7, 1000),
            listOf(XpRules.CHAPTER, XpRules.TOGETHER, XpRules.STREAK_DAY, XpRules.STREAK_MAX_DAYS, XpRules.DAILY_CAP),
        )
        assertEquals(2, XpRules.MAX_FREEZES)
        assertEquals(100, XpRules.CHALLENGE_XP)
        assertEquals(listOf(0L, 50L, 150L, 300L, 500L, 750L, 2250L), listOf(1, 2, 3, 4, 5, 6, 10).map(XpLevels::xpFor))
        assertEquals(
            listOf(
                Triple(
                    "chapters_100",
                    ChallengeField.CHAPTERS,
                    100,
                ),
                Triple("chapters_200", ChallengeField.CHAPTERS, 200),
                Triple("days_15", ChallengeField.DAYS, 15), Triple("days_20", ChallengeField.DAYS, 20),
                Triple("together_5", ChallengeField.TOGETHER, 5), Triple("together_10", ChallengeField.TOGETHER, 10),
                Triple("streak_7", ChallengeField.STREAK, 7), Triple("streak_14", ChallengeField.STREAK, 14),
                Triple("binge_15", ChallengeField.BEST_DAY, 15), Triple("xp_2000", ChallengeField.XP, 2000),
            ),
            Challenge.entries.map { Triple(it.id, it.counter, it.min) },
        )
        assertEquals(
            listOf(
                listOf("chapters_100", "days_15", "streak_7"), listOf("chapters_200", "together_5", "binge_15"),
                listOf("days_20", "together_10", "xp_2000"), listOf("chapters_100", "streak_14", "together_5"),
                listOf("days_15", "binge_15", "xp_2000"), listOf("chapters_200", "days_20", "together_10"),
                listOf("chapters_100", "together_5", "streak_14"), listOf("days_20", "streak_7", "xp_2000"),
                listOf("chapters_200", "binge_15", "together_10"), listOf("chapters_100", "days_20", "together_10"),
                listOf("days_15", "streak_14", "xp_2000"), listOf("chapters_200", "together_5", "streak_7"),
            ),
            MonthlyChallenges.ROTATION.map { month -> month.map { it.id } },
        )
    }

    // And straight from the source, so a backend edit without a test edit still fails here.
    @Test
    fun `the XP rules match domain ts`() {
        val domain = listOf(java.io.File("../backend/src/domain.ts"), java.io.File("backend/src/domain.ts")).first {
            it.exists()
        }.readText()
        val xp = domain.substringAfter("export const XP = {").substringBefore("}")
        fun number(name: String) = Regex("""$name: ([\d_]+)""").find(xp)!!.groupValues[1].replace("_", "").toInt()
        assertEquals(XpRules.CHAPTER, number("chapter"))
        assertEquals(XpRules.TOGETHER, number("together"))
        assertEquals(XpRules.STREAK_DAY, number("streakDay"))
        assertEquals(XpRules.STREAK_MAX_DAYS, number("streakMaxDays"))
        assertEquals(XpRules.DAILY_CAP, number("dailyCap"))
        assertEquals("${XpRules.MAX_FREEZES}", Regex("""MAX_FREEZES = (\d+)""").find(domain)!!.groupValues[1])
        assertEquals("${XpRules.CHALLENGE_XP}", Regex("""CHALLENGE_XP = (\d+)""").find(domain)!!.groupValues[1])
        val pool = domain.substringAfter("export const CHALLENGES").substringBefore("];")
        val backend = Regex("""id: "(\w+)", field: "\w+", min: (\d+)""").findAll(pool)
            .map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()
        assertEquals(backend, Challenge.entries.map { it.id to it.min })
        val rotation = domain.substringAfter("export const CHALLENGE_ROTATION").substringBefore("];")
            .substringAfter("= [")
        val ids = Regex(""""(\w+)"""").findAll(rotation).map { it.groupValues[1] }.toList()
        assertEquals(ids, MonthlyChallenges.ROTATION.flatten().map { it.id })
    }

    @Test
    fun `months follow UTC and pick their challenges`() {
        assertEquals("2026-10", MonthlyChallenges.monthOf(Instant.parse("2026-10-31T23:59:00Z")))
        assertEquals("2026-11", MonthlyChallenges.monthOf(Instant.parse("2026-11-01T00:00:00Z")))
        assertEquals(
            listOf(Challenge.CHAPTERS_100, Challenge.DAYS_20, Challenge.TOGETHER_10),
            MonthlyChallenges.forMonth("2026-10"),
        )
        assertEquals("season_2026_10", MonthlyChallenges.seasonBadge("2026-10"))
        assertEquals(YearMonth.of(2026, 10), MonthlyChallenges.seasonMonth("season_2026_10"))
        assertNull(MonthlyChallenges.seasonMonth("streak_7"))
        assertNull(MonthlyChallenges.seasonMonth("season_2026_13"))
        assertEquals("October 2026", MonthlyChallenges.monthName(YearMonth.of(2026, 10), Locale.ENGLISH))
    }

    @Test
    fun `progress reads this month's counters and stamps, stale months count as zero`() {
        val xp = parseXp(
            mapOf(
                "total" to 1200L,
                "month" to mapOf("id" to "2026-10", "chapters" to 40L, "days" to 20L, "together" to 3L),
                "challenges" to mapOf("2026-10" to listOf("days_20"), "2026-09" to listOf("chapters_200")),
                "badges" to mapOf("first_chapter" to 5L, "season_2026_09" to 9L),
                "days" to 33L,
                "bestDay" to 12L,
            ),
        )
        assertEquals(33, xp.days)
        assertEquals(12, xp.bestDay)
        val october = MonthlyChallenges.progress(xp, "2026-10")
        assertEquals(listOf(40, 20, 3), october.map { it.value })
        assertEquals(listOf(false, true, false), october.map { it.done })
        assertEquals(0.4f, october[0].fraction)
        val november = MonthlyChallenges.progress(xp, "2026-11")
        assertTrue(november.all { it.value == 0 && !it.done })
        assertEquals(listOf(YearMonth.of(2026, 9) to 9L), MonthlyChallenges.seasons(xp.badges))
    }

    @Test
    fun `older xp docs without the new fields parse`() {
        val xp = parseXp(mapOf("total" to 60L, "chapters" to 4L))
        assertEquals(2, xp.level)
        assertNull(xp.month)
        assertTrue(xp.challenges.isEmpty())
        assertFalse(MonthlyChallenges.progress(xp, "2026-10").any { it.done })
    }

    @Test
    fun `a completed challenge makes a reward worth showing`() {
        assertFalse(XpReward(gained = 10, total = 10, level = 1, leveledUp = false).notable)
        assertTrue(
            XpReward(gained = 110, total = 110, level = 2, leveledUp = false, challenges = listOf("chapters_100"))
                .notable,
        )
    }
}

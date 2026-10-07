package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.ReadingLogEntry
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class LocalReadingStatsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-10-14T10:00:00Z") // a Wednesday

    private fun at(date: String, hour: Int = 12) = LocalDate.parse(
        date,
    ).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun row(manga: Long, date: String, minutes: Long = 5, hour: Int = 12) =
        ReadingLogEntry(manga, "Title $manga", at(date, hour), minutes * 60_000)

    @Test
    fun `twelve weeks of chapters, Monday start in the phone's zone, oldest first`() {
        val log = listOf(
            row(1, "2026-10-12"),
            row(1, "2026-10-14"), // this week
            row(2, "2026-10-11", hour = 23), // Sunday late evening local: last week
            row(3, "2026-07-20"), // exactly 12 weeks back: outside the window
            row(4, "2026-07-27"), // 11 weeks back: first bar
        )
        val stats = LocalReadingStats.compute(log, 0, now, zone)
        assertEquals(12, stats.weeks.size)
        assertEquals(LocalDate.parse("2026-10-12"), stats.weeks.last().start)
        assertEquals(LocalDate.parse("2026-07-27"), stats.weeks.first().start)
        assertEquals(2, stats.weeks.last().chapters)
        assertEquals(1, stats.weeks[10].chapters)
        assertEquals(1, stats.weeks.first().chapters)
        assertEquals(4, stats.weeks.sumOf { it.chapters })
    }

    @Test
    fun `top titles this month, new titles, month streak, longest day and reset rows`() {
        val log = listOf(
            row(1, "2026-10-01"), row(1, "2026-10-02"), row(1, "2026-10-03"),
            row(2, "2026-10-03", minutes = 30), row(2, "2026-10-03", minutes = 30),
            row(3, "2026-09-28"), row(3, "2026-10-10"), // started last month: not new
            row(4, "2026-09-15", minutes = 200), // longest day, but last month
            ReadingLogEntry(5, "Reset", 0, 999_999), // reset history row: ignored
        )
        val stats = LocalReadingStats.compute(log, 12_345, now, zone)
        assertEquals(listOf(1L to 3, 2L to 2, 3L to 1), stats.topTitles.map { it.mangaId to it.chapters })
        assertEquals(12_345, stats.totalReadMs)
        assertEquals(YearMonth.of(2026, 10), stats.month.month)
        assertEquals(6, stats.month.chapters)
        assertEquals(2, stats.month.newTitles)
        assertEquals(3, stats.month.bestStreak)
        assertEquals(LocalReadingStats.DayCount(LocalDate.parse("2026-09-15"), 1, 200 * 60_000), stats.longestDay)
    }

    @Test
    fun `nothing read yet`() {
        val stats = LocalReadingStats.compute(emptyList(), 0, now, zone)
        assertEquals(0, stats.weeks.sumOf { it.chapters })
        assertNull(stats.longestDay)
        assertEquals(0, stats.month.bestStreak)
        assertEquals(emptyList<LocalReadingStats.TitleCount>(), stats.topTitles)
    }

    @Test
    fun `longest run of days`() {
        val days = listOf("2026-10-01", "2026-10-02", "2026-10-04", "2026-10-05", "2026-10-06").map(LocalDate::parse)
        assertEquals(3, LocalReadingStats.longestRun(days.toSet()))
        assertEquals(0, LocalReadingStats.longestRun(emptySet()))
    }
}

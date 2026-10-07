package mihon.feature.social

import tachiyomi.domain.history.model.ReadingLogEntry
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Reading stats from this phone's Mihon history, for my own stats page only: never uploaded. History keeps one row per
 * chapter with its last read time and total reading time, so a re-read chapter counts once, on its latest day.
 * Days and weeks follow the phone's time zone.
 */
data class LocalReadingStats(
    /** Chapters per week (Monday start), oldest first; the last entry is this week. */
    val weeks: List<WeekCount>,
    /** Most-read titles this month, at most five. */
    val topTitles: List<TitleCount>,
    /** All time spent in the reader (ms), as Mihon records it. */
    val totalReadMs: Long,
    /** The day with the most reading time (most chapters when no time was recorded). */
    val longestDay: DayCount?,
    val month: MonthSummary,
) {
    data class WeekCount(val start: LocalDate, val chapters: Int)
    data class TitleCount(val mangaId: Long, val title: String, val chapters: Int)
    data class DayCount(val date: LocalDate, val chapters: Int, val readMs: Long)

    /** "This month": chapters, titles first read this month, and the longest run of consecutive reading days. */
    data class MonthSummary(val month: YearMonth, val chapters: Int, val newTitles: Int, val bestStreak: Int)

    companion object {
        const val WEEKS = 12
        const val TOP_TITLES = 5

        fun compute(log: List<ReadingLogEntry>, totalReadMs: Long, now: Instant, zone: ZoneId): LocalReadingStats {
            val today = now.atZone(zone).toLocalDate()
            val read = log.filter { it.readAt > 0 }.map {
                it to
                    Instant.ofEpochMilli(it.readAt).atZone(zone).toLocalDate()
            }
            val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val perWeek = read.groupingBy { (_, date) -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
                .eachCount()
            val weeks = (WEEKS - 1 downTo 0).map { back ->
                val start = thisWeek.minusWeeks(back.toLong())
                WeekCount(start, perWeek[start] ?: 0)
            }
            val month = YearMonth.from(today)
            val inMonth = read.filter { (_, date) -> YearMonth.from(date) == month }
            val topTitles = inMonth.groupBy { (entry, _) -> entry.mangaId }
                .map { (mangaId, rows) -> TitleCount(mangaId, rows.first().first.title, rows.size) }
                .sortedWith(compareByDescending<TitleCount> { it.chapters }.thenBy { it.title })
                .take(TOP_TITLES)
            val longestDay = read.groupBy({ it.second }, { it.first })
                .map { (date, rows) -> DayCount(date, rows.size, rows.sumOf { it.readDuration }) }
                .maxWithOrNull(compareBy<DayCount> { it.readMs }.thenBy { it.chapters }.thenBy { it.date })
            val firstRead = read.groupBy({ it.first.mangaId }, { it.second }).mapValues { (_, dates) -> dates.min() }
            val newTitles = firstRead.count { (_, first) -> YearMonth.from(first) == month }
            return LocalReadingStats(
                weeks = weeks,
                topTitles = topTitles,
                totalReadMs = totalReadMs,
                longestDay = longestDay,
                month = MonthSummary(month, inMonth.size, newTitles, longestRun(inMonth.map { it.second }.toSet())),
            )
        }

        /** The longest run of consecutive dates in [days]. */
        fun longestRun(days: Set<LocalDate>): Int {
            var best = 0
            for (day in days) {
                if (day.minusDays(1) in days) continue
                var length = 1
                while (day.plusDays(length.toLong()) in days) length++
                best = maxOf(best, length)
            }
            return best
        }
    }
}

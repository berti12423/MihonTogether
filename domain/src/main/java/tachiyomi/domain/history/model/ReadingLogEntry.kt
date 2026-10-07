package tachiyomi.domain.history.model

/** One chapter's history row with its title: last read time (epoch ms) and total time spent reading it (ms). */
data class ReadingLogEntry(
    val mangaId: Long,
    val title: String,
    val readAt: Long,
    val readDuration: Long,
)

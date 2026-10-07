package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.model.ReadingLogEntry
import tachiyomi.domain.history.repository.HistoryRepository

/** Every read chapter's history row (reset rows excluded), for on-device reading stats. */
@Inject
class GetReadingLog(
    private val repository: HistoryRepository,
) {

    suspend fun await(): List<ReadingLogEntry> {
        return repository.getReadingLog()
    }
}

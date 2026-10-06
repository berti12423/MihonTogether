package mihon.feature.social.reader

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.online.HttpSource
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.feature.social.SocialContent
import mihon.feature.social.SocialIdentity
import mihon.feature.social.SocialSource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/** Pure choices for opening a title to read together. */
object ReadTogetherStart {
    /** Reading order is oldest first (highest source order); the first unread chapter, or the first one. */
    fun nextChapter(chapters: List<Chapter>): Chapter? {
        val ordered = chapters.sortedByDescending { it.sourceOrder }
        return ordered.firstOrNull { !it.read } ?: ordered.firstOrNull()
    }

    /** Joining in one step needs the host's title URL and the source installed here. */
    fun canImport(source: SocialSource?, installedSourceIds: Set<Long>): Boolean =
        source?.url != null && source.id in installedSourceIds

    /** The host's chapter among [chapters] of my copy of [local], by stable chapter key. */
    fun matchChapter(local: SocialContent, chapters: List<Chapter>, chapterKey: String?): Chapter? =
        chapterKey?.let { key ->
            chapters.firstOrNull { ReaderReadingIdentity.chapterKey(local, it.url, it.scanlator) == key }
        }
}

/**
 * One-step join: a room's title that isn't in my library, from a source I have installed, becomes a local
 * (non-library) manga through Mihon's usual network-to-local flow, with its chapters fetched, so the reader can open
 * the host's chapter directly. Nothing is added to the library.
 */
@Inject
class RoomTitleImporter(
    private val sourceManager: SourceManager,
    private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) {
    suspend fun canImport(content: SocialContent): Boolean {
        val source = content.source ?: return false
        return source.url != null && sourceManager.get(source.id) is HttpSource
    }

    /** Where to open the room's title; null when it can't be opened here (the source guide takes over). */
    suspend fun import(content: SocialContent, chapterKey: String?): RoomDestination? = withIOContext {
        val shared = content.source ?: return@withIOContext null
        val url = SocialSource.validUrl(shared.url) ?: return@withIOContext null
        val source = sourceManager.get(shared.id) as? HttpSource ?: return@withIOContext null
        try {
            val manga = getMangaByUrlAndSourceId.await(url, source.id)
                ?: networkToLocalManga(Manga.create().copy(url = url, title = content.title, source = source.id))
            // The URL must lead to exactly the room's edition; never open a lookalike.
            val local = SocialIdentity.content(manga)?.takeIf { ReadingCompatibility.sameEdition(it, content) }
                ?: return@withIOContext null
            var chapters = getChaptersByMangaId.await(manga.id)
            var chapter = ReadTogetherStart.matchChapter(local, chapters, chapterKey)
            if (chapters.isEmpty() || (chapterKey != null && chapter == null)) {
                updateMangaFromRemote(manga, fetchDetails = !manga.initialized, fetchChapters = true).getOrThrow()
                chapters = getChaptersByMangaId.await(manga.id)
                chapter = ReadTogetherStart.matchChapter(local, chapters, chapterKey)
            }
            // Without the host's chapter (or before the host shared one), open the title page.
            RoomDestination(manga.id, chapter?.id)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.WARN, error) { "Room title not imported" }
            null
        }
    }
}

package mihon.feature.social.reader

import mihon.feature.social.SocialContent
import mihon.feature.social.SocialIdentity
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Where a room's title (and the host's chapter, if known) lives on this device. */
data class RoomDestination(val mangaId: Long, val chapterId: Long?)

object RoomDestinations {
    /**
     * Finds the same source edition of [content] in [library], then the chapter with [chapterKey] among its chapters.
     * Returns null when the title isn't in the library; a null chapter means "open the title".
     */
    suspend fun resolve(
        content: SocialContent,
        chapterKey: String?,
        library: List<Manga>,
        chapters: suspend (Long) -> List<Chapter>,
    ): RoomDestination? {
        val (manga, local) = library.firstNotNullOfOrNull { manga ->
            SocialIdentity.content(manga)
                ?.takeIf { ReadingCompatibility.sameEdition(it, content) }
                ?.let { manga to it }
        } ?: return null
        val chapterId = chapterKey?.let { key ->
            chapters(manga.id).firstOrNull { ReaderReadingIdentity.chapterKey(local, it.url, it.scanlator) == key }?.id
        }
        return RoomDestination(manga.id, chapterId)
    }
}

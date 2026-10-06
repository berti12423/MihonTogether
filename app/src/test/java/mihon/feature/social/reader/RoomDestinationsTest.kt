package mihon.feature.social.reader

import kotlinx.coroutines.test.runTest
import mihon.feature.social.SocialIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class RoomDestinationsTest {
    private val manga = Manga.create().copy(
        id = 11,
        source = 42,
        url = "/series/omniscient",
        title = "Omniscient Reader",
    )
    private val other = Manga.create().copy(id = 12, source = 42, url = "/series/other", title = "Other")
    private val chapters = listOf(
        Chapter.create().copy(id = 101, mangaId = 11, url = "/series/omniscient/1", name = "Chapter 1"),
        Chapter.create().copy(id = 102, mangaId = 11, url = "/series/omniscient/2", name = "Chapter 2"),
    )
    private val content = SocialIdentity.content(manga)!!

    @Test
    fun `finds the same edition in the library and the host's chapter`() = runTest {
        val key = ReaderReadingIdentity.chapterKey(content, "/series/omniscient/2", null)
        val destination = RoomDestinations.resolve(content, key, listOf(other, manga)) { chapters }
        assertEquals(RoomDestination(11, 102), destination)
    }

    @Test
    fun `an unknown chapter opens the title and a missing title resolves to nothing`() = runTest {
        assertEquals(
            RoomDestination(11, null),
            RoomDestinations.resolve(content, "missing", listOf(manga)) {
                chapters
            },
        )
        assertNull(RoomDestinations.resolve(content, null, listOf(other)) { chapters })
        // Same work from another source is another edition: pages may differ, so it doesn't match.
        val otherSource = manga.copy(id = 13, source = 43)
        assertNull(RoomDestinations.resolve(content, null, listOf(otherSource)) { chapters })
    }
}

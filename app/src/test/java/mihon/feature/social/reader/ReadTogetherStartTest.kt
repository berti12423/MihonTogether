package mihon.feature.social.reader

import mihon.feature.social.SocialContent
import mihon.feature.social.SocialIdentity
import mihon.feature.social.SocialSource
import mihon.feature.social.content
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ReadTogetherStartTest {
    private fun chapter(id: Long, order: Long, read: Boolean = false, url: String = "/c/$id") =
        Chapter.create().copy(id = id, sourceOrder = order, read = read, url = url)

    @Test
    fun `next chapter is the first unread in reading order, else the first`() {
        // Source order 0 is the newest chapter; reading starts from the highest.
        val chapters = listOf(chapter(3, 0), chapter(2, 1), chapter(1, 2, read = true))
        assertEquals(2L, ReadTogetherStart.nextChapter(chapters)?.id)
        assertEquals(1L, ReadTogetherStart.nextChapter(chapters.map { it.copy(read = true) })?.id)
        assertNull(ReadTogetherStart.nextChapter(emptyList()))
    }

    @Test
    fun `one-step join needs the title URL and the source installed`() {
        val source = SocialSource(7, "Src", "en", "pkg", url = "/title/1")
        assertTrue(ReadTogetherStart.canImport(source, setOf(7)))
        assertFalse(ReadTogetherStart.canImport(source, setOf(8)))
        assertFalse(ReadTogetherStart.canImport(source.copy(url = null), setOf(7)))
        assertFalse(ReadTogetherStart.canImport(null, setOf(7)))
    }

    @Test
    fun `the host's chapter is matched by its stable key`() {
        val manga = Manga.create().copy(id = 1, source = 7, url = "/title/1", title = "Solo")
        val local = SocialIdentity.content(manga)!!
        val chapters = listOf(chapter(1, 1, url = "/c/1"), chapter(2, 0, url = "/c/2"))
        val key = ReaderReadingIdentity.chapterKey(local, "/c/2", null)
        assertEquals(2L, ReadTogetherStart.matchChapter(local, chapters, key)?.id)
        assertNull(ReadTogetherStart.matchChapter(local, chapters, "unknown"))
        assertNull(ReadTogetherStart.matchChapter(local, chapters, null))
    }

    @Test
    fun `title URLs are shared only as a path or public https link`() {
        assertEquals("/manga/solo", SocialSource.validUrl("/manga/solo"))
        assertEquals("https://x.org/m/1", SocialSource.validUrl("https://x.org/m/1"))
        assertNull(SocialSource.validUrl("http://x.org/m/1"))
        assertNull(SocialSource.validUrl("file:///sdcard/x"))
        assertNull(SocialSource.validUrl("/a b"))
        assertNull(SocialSource.validUrl("/"))
        assertNull(SocialSource.validUrl("/" + "x".repeat(SocialSource.MAX_URL)))
        assertNull(SocialSource.validUrl(null))
    }

    @Test
    fun `room content carries the source URL through the wire format`() {
        val content = SocialContent(
            "work",
            "Solo",
            "source",
            "source-work",
            "edition",
            source = SocialSource(7, "Src", "en", "pkg", url = "/title/1"),
        )
        assertEquals(content, content.payload().content())
        val noUrl = content.copy(source = content.source!!.copy(url = null))
        assertFalse("url" in (noUrl.payload()["source"] as Map<*, *>))
        val bad = content.payload().toMutableMap().apply {
            put("source", mapOf("id" to "7", "name" to "Src", "lang" to "en", "pkg" to "pkg", "url" to "javascript:x"))
        }
        assertNull(bad.content().source!!.url)
    }

    @Test
    fun `pending invites go to accepted friends only, at most five, never myself`() {
        val pending = PendingRoomInvite("work", listOf("a", "b", "a", "me", "stranger", "c", "d", "e", "f"), 0)
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            pending.targets(setOf("a", "b", "c", "d", "e", "f", "me"), me = "me"),
        )
        assertEquals(emptyList<String>(), pending.targets(emptySet(), me = "me"))
        assertFalse(pending.expired(PendingRoomInvite.MAX_AGE_MS))
        assertTrue(pending.expired(PendingRoomInvite.MAX_AGE_MS + 1))
        assertTrue(pending.expired(-1))
    }

    @Test
    fun `only one reader takes a pending invite, and clearing the session drops it`() {
        val store = ReadingSessionStore()
        val pending = PendingRoomInvite("work", listOf("a"), 0)
        store.setPending(pending)
        assertTrue(store.takePending(pending))
        assertFalse(store.takePending(pending))

        store.setPending(pending)
        store.clear("some-room")
        assertEquals(pending, store.pendingInvite.value)
        store.clear()
        assertNull(store.pendingInvite.value)
    }
}

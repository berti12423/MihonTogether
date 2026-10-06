package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class SocialIdentityTest {
    private val manga = Manga.create().copy(source = 123L, url = "/title/first", title = "First title")

    @Test
    fun `identity survives local database renumbering and title edits`() {
        val first = checkNotNull(SocialIdentity.content(manga.copy(id = 1)))
        val restored = checkNotNull(SocialIdentity.content(manga.copy(id = 987, title = "Edited local title")))
        assertEquals(first.contentKey, restored.contentKey)
        assertEquals(first.editionKey, restored.editionKey)
        assertTrue(first.contentKey.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun `different work paths and sources do not match editions`() {
        val first = checkNotNull(SocialIdentity.content(manga))
        val otherWork = checkNotNull(SocialIdentity.content(manga.copy(url = "/title/second")))
        val otherSource = checkNotNull(SocialIdentity.content(manga.copy(source = 456L)))
        assertNotEquals(first.contentKey, otherWork.contentKey)
        assertNotEquals(first.editionKey, otherSource.editionKey)
    }

    @Test
    fun `query addressed titles require an explicit source adapter`() {
        assertNull(SocialIdentity.content(manga.copy(url = "/title?id=first")))
        assertNull(SocialIdentity.content(manga.copy(url = "/title?id=second")))
        assertNull(SocialIdentity.publicPath("/?id=first"))
        assertNull(SocialIdentity.publicPath("/"))
    }

    @Test
    fun `public locator excludes user credentials and fragments`() {
        assertEquals(
            "/title/first",
            SocialIdentity.publicPath("https://private:password@example.test/title/first#reader"),
        )
        assertNull(SocialIdentity.publicPath("https://example.test/title/first?auth=private-token"))
        assertNull(SocialIdentity.content(manga.copy(source = 0)))
    }
}

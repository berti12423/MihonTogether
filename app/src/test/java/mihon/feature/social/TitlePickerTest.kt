package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class TitlePickerTest {
    private fun content(key: String, title: String) = SocialContent(key, title, "source", "work-$key", "edition-$key")

    private val solo = content("solo", "Solo Leveling")
    private val berserk = content("berserk", "Berserk")
    private val omniscient = content("orv", "omniscient reader")

    @Test
    fun `titles are listed A to Z and searched by name`() {
        val titles = listOf(solo, omniscient, berserk)
        assertEquals(listOf(berserk, omniscient, solo), TitlePicker.shown(titles, ""))
        assertEquals(listOf(solo), TitlePicker.shown(titles, "  LEVEL "))
        assertEquals(emptyList<SocialContent>(), TitlePicker.shown(titles, "nothing"))
    }

    @Test
    fun `what a friend reads now comes first, only when it matches`() {
        val titles = listOf(solo, omniscient, berserk)
        assertEquals(listOf(solo, berserk, omniscient), TitlePicker.shown(titles, "", pinned = solo))
        assertEquals(listOf(berserk), TitlePicker.shown(titles, "berserk", pinned = solo))
        // Not in my library: nothing is pinned.
        assertEquals(listOf(berserk, omniscient, solo), TitlePicker.shown(titles, "", pinned = content("x", "X")))
    }

    @Test
    fun `duplicate titles show once, since the grid is keyed by content`() {
        assertEquals(listOf(solo), TitlePicker.shown(listOf(solo, solo.copy(title = "Solo Leveling")), ""))
    }

    @Test
    fun `picker covers come from my own library manga`() {
        val mine = Manga.create().copy(
            id = 7,
            source = 123L,
            url = "/title/first",
            title = "First",
            favoriteAt = 1L,
            thumbnailUrl = "https://example.org/cover.jpg",
            coverLastModified = 42,
        )
        val key = SocialIdentity.content(mine)!!.contentKey
        val cover = FriendList.covers(listOf(mine, Manga.create().copy(id = 8, source = 0L, title = "Local")))
        assertEquals(setOf(key), cover.keys)
        assertEquals(7L, cover[key]!!.mangaId)
        assertEquals(true, cover[key]!!.isMangaFavorite)
        assertEquals(42L, cover[key]!!.lastModified)
        assertNull(FriendList.covers(emptyList())[key])
    }
}

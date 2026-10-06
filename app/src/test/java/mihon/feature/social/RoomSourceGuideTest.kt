package mihon.feature.social

import mihon.feature.social.RoomSourceGuide.Companion.Listing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoomSourceGuideTest {
    private val mangadex =
        SocialSource(2499283573021220255, "MangaDex", "en", "eu.kanade.tachiyomi.extension.all.mangadex")
    private val listing = Listing("eu.kanade.tachiyomi.extension.all.mangadex", setOf(2499283573021220255, 1L))

    @Test
    fun `installed beats everything, then untrusted, then installable from a repo, else missing`() {
        assertEquals(
            RoomSourceGuide.Installed(mangadex.id, "MangaDex"),
            RoomSourceGuide.decide(mangadex, setOf(mangadex.id), setOf(mangadex.pkg!!), listOf(listing)),
        )
        assertEquals(
            RoomSourceGuide.Untrusted(mangadex.pkg!!, "MangaDex"),
            RoomSourceGuide.decide(mangadex, emptySet(), setOf(mangadex.pkg!!), listOf(listing)),
        )
        assertEquals(
            RoomSourceGuide.Installable(listing.pkgName, "MangaDex", "en"),
            RoomSourceGuide.decide(mangadex, emptySet(), emptySet(), listOf(listing)),
        )
        assertEquals(
            RoomSourceGuide.Missing("MangaDex", "en"),
            RoomSourceGuide.decide(mangadex, emptySet(), emptySet(), emptyList()),
        )
        assertEquals(RoomSourceGuide.Unknown, RoomSourceGuide.decide(null, setOf(1L), emptySet(), listOf(listing)))
    }

    @Test
    fun `a renamed package still matches by source ID, and built-in sources have no package`() {
        val renamed = Listing("eu.kanade.tachiyomi.extension.all.mangadexv2", setOf(mangadex.id))
        assertEquals(
            "eu.kanade.tachiyomi.extension.all.mangadexv2",
            (
                RoomSourceGuide.decide(
                    mangadex,
                    emptySet(),
                    emptySet(),
                    listOf(renamed),
                ) as RoomSourceGuide.Installable
                ).pkgName,
        )
        val local = SocialSource(0, "Local source", "other", null)
        assertEquals(
            RoomSourceGuide.Missing("Local source", "other"),
            RoomSourceGuide.decide(local, emptySet(), emptySet(), listOf(renamed)),
        )
    }

    @Test
    fun `the source travels in the content payload and back`() {
        val content = SocialContent("k", "Title", "s", "w", "e", source = mangadex)

        @Suppress("UNCHECKED_CAST")
        val payload = content.payload()["source"] as Map<String, Any?>
        assertEquals(
            mapOf("id" to "2499283573021220255", "name" to "MangaDex", "lang" to "en", "pkg" to mangadex.pkg),
            payload,
        )
        assertEquals(content, content.payload().content())
    }
}

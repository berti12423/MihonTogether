package mihon.feature.social

import mihon.feature.social.TitleMatch.Reason
import mihon.feature.social.TitleMatch.Work
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TitleMatchTest {
    private fun work(
        title: String,
        source: String = "a",
        entry: String = title,
        trackers: Map<String, String> = emptyMap(),
        alternatives: List<String> = emptyList(),
    ) = Work(
        sourceKey = "source-v1:$source",
        sourceWorkKey = SocialIdentity.key("$source|$entry"),
        trackers = trackers,
        titles = listOf(title) + alternatives,
    )

    /** The same title on two different sources, so only the title rule can decide. */
    private fun titleMatch(mine: String, theirs: String) = TitleMatch.match(work(mine, "a"), work(theirs, "b"))

    @Test
    fun `edition labels, punctuation, case, width and accents never tell works apart`() {
        listOf(
            "Solo Leveling" to "Solo Leveling (Official)",
            "Solo Leveling" to "SOLO LEVELING [Colored]",
            "Tower of God" to "Tower of God (Webtoon)",
            "Tower of God" to "Tower of God Raw",
            "Tower of God" to "Tower of God - RAW",
            "Omniscient Reader" to "[Official] Omniscient Reader",
            "The Greatest Estate Developer" to "The Greatest Estate Developer (Full Color)",
            "Kaguya-sama: Love Is War" to "Kaguya-sama - Love is War",
            "Frieren: Beyond Journey's End" to "Frieren Beyond Journeys End",
            "Frieren: Beyond Journey’s End" to "Frieren: Beyond Journey's End",
            "Pokémon Adventures" to "Pokemon Adventures",
            "ＯＮＥ ＰＩＥＣＥ" to "One Piece",
            "One-Punch Man" to "Onepunch-Man",
            "Love & Lies" to "Love and Lies",
            "Mushoku Tensei: Jobless Reincarnation" to "Mushoku Tensei: Jobless Reincarnation (Manga)",
            "Lookism" to "Lookism [EN]",
            "進撃の巨人" to "進撃の巨人 (RAW)",
            "나 혼자만 레벨업" to "나 혼자만 레벨업 [Webtoon]",
        ).forEach { (mine, theirs) -> assertEquals(Reason.TITLE, titleMatch(mine, theirs), "$mine / $theirs") }
    }

    @Test
    fun `sequels, subtitles, formats and other works with similar names stay apart`() {
        listOf(
            "Solo Leveling" to "Solo Leveling: Ragnarok",
            "Tower of God" to "Tower of God Season 2",
            "Kingdom" to "Kingdom Hearts",
            "Berserk" to "Berserk of Gluttony",
            "Attack on Titan" to "Attack on Titan: Before the Fall",
            "Overlord" to "Overlord (Novel)",
            "Re:Zero" to "Re:Zero (Light Novel)",
            "Kaguya-sama: Love Is War" to "Kaguya-sama: Love Is War (Doujinshi)",
            "Spy x Family" to "Spy x Family Anthology",
            "Hunter x Hunter" to "Hunter x Hunter 2",
            "Monster" to "Monsters",
            // Leading articles are kept on purpose: sources rarely drop them, and "The X" can be another work.
            "The Beginning After the End" to "Beginning After the End",
            // Japanese dakuten change the word (ga vs ka), unlike accents on Latin letters.
            "ガンツ" to "カンツ",
        ).forEach { (mine, theirs) -> assertNull(titleMatch(mine, theirs), "$mine / $theirs") }
    }

    @Test
    fun `titles too short or empty after normalization never match by title`() {
        assertNull(titleMatch("!!", "!!"))
        assertNull(titleMatch("Ao", "AO"))
        assertNull(titleMatch("(Official)", "[Official]"))
        assertEquals(Reason.TITLE, titleMatch("Ajin", "AJIN"))
    }

    @Test
    fun `alternative titles from my trackers also match`() {
        val mine = work("Solo Leveling", alternatives = listOf("Ore dake Level Up na Ken"))
        assertEquals(Reason.TITLE, TitleMatch.match(mine, work("Ore Dake Level Up na Ken", "b")))
        assertNull(TitleMatch.match(work("Solo Leveling"), work("Ore Dake Level Up na Ken", "b")))
    }

    @Test
    fun `tracker IDs decide before titles, both ways, only within the same tracker`() {
        val anilist = mapOf("anilist" to "105398")
        assertEquals(
            Reason.TRACKER,
            TitleMatch.match(
                work("Solo Leveling", trackers = anilist),
                work("Na Honjaman Level Up", "b", trackers = anilist),
            ),
        )
        // Same name, different entries: two works called "Monster".
        assertNull(
            TitleMatch.match(
                work("Monster", trackers = mapOf("anilist" to "1")),
                work("Monster", "b", trackers = mapOf("anilist" to "2")),
            ),
        )
        // I track on both, they on MyAnimeList only: MyAnimeList decides.
        val both = mapOf("anilist" to "105398", "mal" to "121496")
        assertEquals(
            Reason.TRACKER,
            TitleMatch.match(
                work("Solo Leveling", trackers = both),
                work("x y z", "b", trackers = mapOf("mal" to "121496")),
            ),
        )
        // Different trackers can't be compared: the title decides.
        assertEquals(
            Reason.TITLE,
            TitleMatch.match(
                work("Solo Leveling", trackers = anilist),
                work("Solo Leveling", "b", trackers = mapOf("mal" to "1")),
            ),
        )
    }

    @Test
    fun `the same source entry always matches, another entry of the same source never does by title`() {
        assertEquals(
            Reason.SOURCE_WORK,
            TitleMatch.match(work("Old Name", entry = "/manga/1"), work("Renamed", entry = "/manga/1")),
        )
        assertNull(
            TitleMatch.match(work("Solo Leveling", entry = "/manga/1"), work("Solo Leveling", entry = "/manga/2")),
        )
        // A matching tracker still links two entries of one source.
        val anilist = mapOf("anilist" to "7")
        assertEquals(
            Reason.TRACKER,
            TitleMatch.match(
                work("Solo Leveling", entry = "/1", trackers = anilist),
                work("Solo Leveling (Colored)", entry = "/2", trackers = anilist),
            ),
        )
    }

    @Test
    fun `shared content becomes a work with its own tracker ID`() {
        val content = content("Solo Leveling").copy(canonicalProvider = "mal", canonicalId = "121496")
        val work = Work.of(content)
        assertEquals(mapOf("mal" to "121496"), work.trackers)
        assertEquals(setOf("sololeveling"), work.keys)
        assertEquals(emptyMap<String, String>(), content("x").trackers())
    }

    @Test
    fun `workKey matches the backend vectors`() {
        val tracked = content("Solo Leveling").copy(canonicalProvider = "anilist", canonicalId = "151807")
        assertEquals("e692c2b5b1c74c0c5dce34809370bd00a973919c76c39d14fdc0e4f54b2ab22e", TitleMatch.workKey(tracked))
        val untracked = content("Solo Leveling").copy(sourceWorkKey = SocialIdentity.key("x"))
        assertEquals("10b597de832c7a163ec706b24ea2750a8c68ec7804926aa1d419a69a4881def0", TitleMatch.workKey(untracked))
        assertNotEquals(TitleMatch.workKey(tracked), TitleMatch.workKey(tracked.copy(canonicalProvider = "mal")))
    }

    @Test
    fun `a friend's title is looked up in their source when I have it installed, else everywhere`() {
        val installed = mapOf(2499283573021220255L to "MangaDex", 1L to "Other")
        val fromMangaDex = content("Solo Leveling").copy(sourceKey = SocialIdentity.sourceKey(2499283573021220255L))
        assertEquals(
            RoomSourceGuide.Installed(2499283573021220255L, "MangaDex"),
            RoomSourceGuide.locate(fromMangaDex, installed),
        )
        assertEquals(RoomSourceGuide.Unknown, RoomSourceGuide.locate(fromMangaDex, mapOf(1L to "Other")))
        // A room's explicit source wins when it's installed.
        val room = fromMangaDex.copy(source = SocialSource(1L, "Other", "en", null))
        assertEquals(RoomSourceGuide.Installed(1L, "Other"), RoomSourceGuide.locate(room, installed))
    }

    private fun content(title: String) = SocialContent(
        contentKey = SocialIdentity.key("content|$title"),
        title = title,
        sourceKey = "source-v1:a",
        sourceWorkKey = SocialIdentity.key("work|$title"),
        editionKey = SocialIdentity.key("edition|$title"),
    )
}

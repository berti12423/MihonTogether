package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class FriendListTest {
    private val now = 1_000_000L
    private val content = SocialContent("key", "Title", "source", "work", "edition")

    private fun profile(uid: String, name: String = uid) = SocialProfile(uid, uid, name)

    @Test
    fun `online friends come first, then readers, then most recently seen, then name`() {
        val friends = listOf(
            profile("never", "Zed"),
            profile("seenOld", "Amy"),
            profile("reading", "Bob"),
            profile("online", "Yan"),
            profile("seenNew", "Cat"),
            profile("never2", "abe"),
        )
        val statuses = mapOf(
            "online" to FriendStatus(online = true),
            "reading" to FriendStatus(online = false, reading = content, readingExpiresAt = now + 60_000),
            "seenNew" to FriendStatus(lastSeen = now - 1_000),
            "seenOld" to FriendStatus(lastSeen = now - 100_000),
        )
        assertEquals(
            listOf("online", "reading", "seenNew", "seenOld", "never2", "never"),
            FriendList.sort(friends, statuses, now).map { it.uid },
        )
    }

    @Test
    fun `expired reading activity does not count as reading now`() {
        val status = FriendStatus(reading = content, readingExpiresAt = now - 1)
        assertNull(status.readingAt(now))
        val sorted = FriendList.sort(
            listOf(profile("a", "A"), profile("b", "B")),
            mapOf("b" to status),
            now,
        )
        assertEquals(listOf("a", "b"), sorted.map { it.uid })
    }

    @Test
    fun `local index matches exact source and path, never titles`() {
        val mine = Manga.create().copy(id = 7, source = 123L, url = "/title/first", title = "First title")
        val sameTitleOtherSource = Manga.create().copy(id = 8, source = 456L, url = "/x", title = "First title")
        val index = FriendList.localIndex(listOf(mine, sameTitleOtherSource))
        val friendsCopy = SocialIdentity.content(
            Manga.create().copy(source = 123L, url = "/title/first", title = "Renamed"),
        )!!
        val otherSource = SocialIdentity.content(
            Manga.create().copy(source = 999L, url = "/title/first", title = "First title"),
        )!!
        assertEquals(7L, index[friendsCopy.contentKey])
        assertNull(index[otherSource.contentKey])
    }

    @Test
    fun `local tables match the separate index and covers, the first copy of a title winning`() {
        val first = Manga.create().copy(id = 7, source = 123L, url = "/title/first", title = "First")
        val duplicate = Manga.create().copy(id = 9, source = 123L, url = "/title/first", title = "First again")
        val second = Manga.create().copy(id = 8, source = 456L, url = "/x", title = "Second")
        val library = listOf(first, duplicate, second)
        val tables = FriendList.tables(library)
        assertEquals(library.mapNotNull(SocialIdentity::content), tables.library)
        assertEquals(FriendList.localIndex(library), tables.localIndex)
        assertEquals(FriendList.covers(library), tables.covers)
        assertEquals(7L, tables.localIndex[SocialIdentity.content(first)!!.contentKey])
    }

    @Test
    fun `room companions name joined people first, from the room snapshot or friends`() {
        fun member(uid: String, state: String, name: String, at: Long?) =
            uid to RoomMember(uid, state, name, joinedAt = at)
        val room = SocialRoom(
            "r", "host", listOf("host", "guest", "cat", "dan", "eve"), content, "active", 1, 0, null, null, 0,
            members = mapOf(
                member("host", RoomMember.JOINED, "Alice", 1),
                member("guest", RoomMember.JOINED, "", 2),
                member("cat", RoomMember.JOINED, "Cat", 3),
                member("dan", RoomMember.INVITED, "Dan", null),
                member("eve", RoomMember.JOINED, "Eve", 4),
            ),
        )
        val friends = listOf(profile("guest", "Bob"))
        assertEquals("Bob, Cat +1", FriendList.companions(room, "host", friends))
        assertEquals("Alice, Cat +1", FriendList.companions(room, "guest", friends))
        assertEquals(listOf("host", "guest", "cat", "eve", "dan"), room.others("nobody").map { it.uid })
        assertEquals(4, room.joinedCount)
        val pair = room.copy(participants = listOf("host", "dan"))
        assertEquals("Dan", FriendList.companions(pair, "host", emptyList())) // only invited: still named
        assertEquals("Alice", FriendList.companions(pair, "dan", emptyList()))
        assertNull(FriendList.companions(room.copy(participants = listOf("host")), "host", emptyList()))
    }

    @Test
    fun `ranking orders by chapters then pages and drops empty days`() {
        val ranked = FriendList.rank(
            listOf(
                RankingEntry("a", "Amy", 2, 40, me = false),
                RankingEntry("b", "Bob", 3, 10, me = true),
                RankingEntry("c", "Cat", 2, 55, me = false),
                RankingEntry("d", "Dan", 0, 0, me = false),
            ),
        )
        assertEquals(listOf("b", "c", "a"), ranked.map { it.uid })
    }

    @Test
    fun `global ranking drops blocked accounts and marks me and friends for the tap target`() {
        val board = listOf(
            LeaderboardEntry("stranger", "strange", "Stranger", 2, chapters = 4, streak = 3),
            LeaderboardEntry("me", "me", "Me", 0, chapters = 5, streak = 1),
            LeaderboardEntry("blocked", "bad", "Bad", 0, chapters = 9, streak = 1),
            LeaderboardEntry("friend", "pal", "Pal", 1, chapters = 4, streak = 1),
            LeaderboardEntry("idle", "idle", "Idle", 0, chapters = 0, streak = 1),
        )
        val rows = FriendList.global(board, me = "me", friends = setOf("friend"), blocked = setOf("blocked"))
        assertEquals(listOf("me", "friend", "stranger"), rows.map { it.uid }) // ties by name
        assertEquals(listOf(true, false, false), rows.map { it.me })
        assertEquals(listOf(false, true, false), rows.map { it.friend })
        assertEquals("strange", rows.last().handle)
        assertEquals(2, rows.last().accent)
    }

    @Test
    fun `all-time ranking orders by total XP, leaves out blocked people and nobodies`() {
        val board = listOf(
            LeaderboardEntry("me", "me", "Me", 0, chapters = 40, streak = 1, level = 5, xp = 400),
            LeaderboardEntry("top", "top", "Top", 0, chapters = 30, streak = 1, level = 7, xp = 900),
            LeaderboardEntry("blocked", "b", "B", 0, chapters = 99, streak = 1, xp = 5_000),
            LeaderboardEntry("old", "old", "Old", 0, chapters = 3, streak = 1),
        )
        val rows = FriendList.allTime(board, me = "me", friends = setOf("top"), blocked = setOf("blocked"))
        assertEquals(listOf("top", "me"), rows.map { it.uid })
        assertEquals(listOf(900, 400), rows.map { it.totalXp })
        assertEquals(listOf(true, false), rows.map { it.friend })
    }

    @Test
    fun `stats day is the UTC calendar day like the backend`() {
        assertEquals("20261002", FriendList.statsDay(java.time.Instant.parse("2026-10-02T23:59:00Z")))
        assertEquals("20261003", FriendList.statsDay(java.time.Instant.parse("2026-10-03T00:00:00Z")))
    }

    @Test
    fun `invite links accept only valid handles`() {
        assertEquals("alice_smoke", FriendInvite.parse("mihontogether://add/alice_smoke"))
        assertEquals("alice_smoke", FriendInvite.parse("mihontogether://add/alice_smoke/"))
        assertNull(FriendInvite.parse("mihontogether://add/Al"))
        assertNull(FriendInvite.parse("mihontogether://add/alice?x=1"))
        assertNull(FriendInvite.parse("https://evil.example/add/alice_smoke"))
        assertNull(FriendInvite.parse(null))
    }

    @Test
    fun `friend codes from the web invite page are recognised, nothing else on the clipboard is`() {
        assertEquals("mihontogether-invite:ana_b", FriendInvite.code("ana_b"))
        assertEquals("ana_b", FriendInvite.parseCode("mihontogether-invite:ana_b"))
        assertEquals("ana_b", FriendInvite.parseCode("  Join me! mihontogether-invite:ana_b\n"))
        assertEquals("ana_b", FriendInvite.parseCode("mihontogether://add/ana_b"))
        assertNull(FriendInvite.parseCode("mihontogether-invite:Ana"))
        assertNull(FriendInvite.parseCode("xmihontogether-invite:ana_b"))
        assertNull(FriendInvite.parseCode("mihontogether-invite:ana_b-2"))
        assertNull(FriendInvite.parseCode("ana_b"))
        assertNull(FriendInvite.parseCode("a".repeat(600) + " mihontogether-invite:ana_b"))
        assertNull(FriendInvite.parseCode(null))
        assertEquals("ana_b", FriendInvite.parseTyped("@Ana_B "))
        assertEquals("ana_b", FriendInvite.parseTyped("mihontogether-invite:ana_b"))
        assertNull(FriendInvite.parseTyped("no way"))
    }

    @Test
    fun `share text includes the web link only when configured`() {
        assertEquals(
            "Add me on Mihon Together: @bob\nhttps://mt.example/add/bob\nmihontogether://add/bob",
            FriendInvite.shareText("bob", "https://mt.example/"),
        )
        assertEquals("Add me on Mihon Together: @bob\nmihontogether://add/bob", FriendInvite.shareText("bob", ""))
    }
}

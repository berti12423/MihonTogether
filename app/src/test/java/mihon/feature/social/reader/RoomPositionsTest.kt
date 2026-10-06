package mihon.feature.social.reader

import mihon.feature.social.FriendList
import mihon.feature.social.FriendStatus
import mihon.feature.social.MemberPage
import mihon.feature.social.OpenRoomCard
import mihon.feature.social.RoomMember
import mihon.feature.social.SocialContent
import mihon.feature.social.SocialPosition
import mihon.feature.social.SocialRoom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomPositionsTest {
    private val now = 1_000_000L
    private val content = SocialContent("work", "A title", "source", "source-work", "edition")
    private val hostPosition = SocialPosition("chapter-1", "Chapter 1", 4, 20, "map")
    private fun member(uid: String, page: MemberPage? = null, state: String = RoomMember.JOINED, accent: Int = 0) =
        uid to RoomMember(uid, state, uid.uppercase(), accent, joinedAt = 1, page = page)
    private val room = SocialRoom(
        "room", "host", listOf("host", "me", "ana", "ben", "cat"), content, "active", 1, 3, hostPosition, null,
        Long.MAX_VALUE,
        members = mapOf(
            member("host"),
            member("me", MemberPage("chapter-1", 9, now)),
            member("ana", MemberPage("chapter-1", 12, now - 1_000), accent = 3),
            member("ben", MemberPage("chapter-2", 1, now)),
            member("cat", MemberPage("chapter-1", 0, now), state = RoomMember.INVITED),
        ),
    )

    private fun place(
        room: SocialRoom? = this.room,
        chapterKey: String? = "chapter-1",
        pageCount: Int = 20,
        following: Boolean = false,
    ) = RoomMarkers.place(room, "me", chapterKey, pageCount, following, now)

    @Test
    fun `markers show the host and joined members on my chapter, never me, sorted by page`() {
        val markers = place()
        assertEquals(listOf("host" to 4, "ana" to 12), markers.map { it.uid to it.pageIndex })
        assertTrue(markers.first().isHost)
        assertEquals(3, markers.last().accent)
        assertEquals("ANA", markers.last().name)
    }

    @Test
    fun `no markers while following, outside an active room or without my chapter`() {
        assertTrue(place(following = true).isEmpty())
        assertTrue(place(room = room.copy(state = "invited")).isEmpty())
        assertTrue(place(room = null).isEmpty())
        assertTrue(place(chapterKey = null).isEmpty())
        assertTrue(place(pageCount = 0).isEmpty())
    }

    @Test
    fun `other chapters, stale places and pages past my chapter's end are left out`() {
        assertEquals(listOf("ben"), place(chapterKey = "chapter-2").map { it.uid })
        val stale = room.copy(
            members = room.members + member("ana", MemberPage("chapter-1", 12, now - RoomMarkers.MAX_AGE_MS - 1)),
        )
        assertEquals(listOf("host"), place(room = stale).map { it.uid })
        // My copy of the chapter is shorter than where ana is: no marker beyond the slider.
        assertEquals(listOf("host"), place(pageCount = 10).map { it.uid })
        val hostElsewhere = room.copy(position = hostPosition.copy(chapterKey = "chapter-3"))
        assertEquals(listOf("ana"), place(room = hostElsewhere).map { it.uid })
    }

    @Test
    fun `marker fraction runs from the first to the last page`() {
        val marker = RoomMarker("ana", "ANA", 0, 0, false)
        assertEquals(0f, marker.fraction(20))
        assertEquals(1f, marker.copy(pageIndex = 19).fraction(20))
        assertEquals(0.5f, marker.copy(pageIndex = 2).fraction(5))
        assertEquals(0f, marker.copy(pageIndex = 3).fraction(1))
        assertEquals(1f, marker.copy(pageIndex = 40).fraction(20))
    }

    @Test
    fun `I share my place only as a joined member on the room's title, and clear it elsewhere`() {
        val reading = LocalReading(content, hostPosition.copy(pageIndex = 7), 1L)
        assertEquals(MemberPlace("chapter-1", 7), MemberPlace.of(reading, room, "me"))
        val otherTitle = reading.copy(content = content.copy(contentKey = "other", editionKey = "other-edition"))
        assertEquals(MemberPlace.CLEARED, MemberPlace.of(otherTitle, room, "me"))
        assertEquals(MemberPlace.CLEARED, MemberPlace.of(null, room, "me"))
        assertNull(MemberPlace.of(reading, room, "cat")) // invited, not joined
        assertNull(MemberPlace.of(reading, room, "stranger"))
        assertNull(MemberPlace.of(reading, room.copy(state = "invited"), "me"))
        assertNull(MemberPlace.of(reading, null, "me"))
    }

    @Test
    fun `open room cards list live rooms I'm not in, fullest first`() {
        fun card(id: String, members: Int, lease: Long = now + 60_000, max: Int = 6) =
            OpenRoomCard(id, "host-$id", "Title $id", members, max, lease)
        val statuses = mapOf(
            "a" to FriendStatus(openRoom = card("a", 2)),
            "b" to FriendStatus(openRoom = card("b", 4)),
            "c" to FriendStatus(openRoom = card("c", 3, lease = now - 1)),
            "d" to FriendStatus(openRoom = card("d", 6)),
            "e" to FriendStatus(openRoom = card("e", 5)),
            "f" to FriendStatus(),
        )
        assertEquals(listOf("b", "a"), FriendList.openRooms(statuses, setOf("e"), now).map { it.roomId })
    }
}

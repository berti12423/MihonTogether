package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FriendsRouteTest {
    private val group = "b".repeat(64)
    private val room = "c".repeat(64)

    private fun push(kind: String, uid: String? = "bob1") = SocialPushMessage(
        kind = kind,
        name = "Bob",
        uid = uid,
        groupId = group,
        roomId = room,
    )

    @Test
    fun `each push kind opens its own place`() {
        assertEquals(FriendsRoute.Chat("bob1"), FriendsRoute.forPush(push(SocialPushMessage.MESSAGE)))
        assertEquals(FriendsRoute.Chat("bob1"), FriendsRoute.forPush(push(SocialPushMessage.READ_MATCH)))
        assertEquals(FriendsRoute.Group(group), FriendsRoute.forPush(push(SocialPushMessage.GROUP_MESSAGE)))
        assertEquals(FriendsRoute.Group(group), FriendsRoute.forPush(push(SocialPushMessage.GROUP_ADDED)))
        assertEquals(FriendsRoute.Room(room), FriendsRoute.forPush(push(SocialPushMessage.ROOM_INVITE)))
        assertEquals(FriendsRoute.Requests, FriendsRoute.forPush(push(SocialPushMessage.FRIEND_REQUEST)))
        assertEquals(FriendsRoute.Profile("bob1"), FriendsRoute.forPush(push(SocialPushMessage.FRIEND_ACCEPTED)))
        assertEquals(FriendsRoute.Home, FriendsRoute.forPush(push(SocialPushMessage.JOIN_REQUEST)))
    }

    @Test
    fun `a push without its subject falls back to Friends`() {
        assertEquals(FriendsRoute.Home, FriendsRoute.forPush(push(SocialPushMessage.MESSAGE, uid = null)))
        assertEquals(
            FriendsRoute.Home,
            FriendsRoute.forPush(push(SocialPushMessage.ROOM_INVITE).copy(roomId = null)),
        )
    }

    @Test
    fun `routes survive the intent extras round trip`() {
        listOf(
            FriendsRoute.Chat("bob1"),
            FriendsRoute.Group(group),
            FriendsRoute.Room(room),
            FriendsRoute.Room(room, join = true),
            FriendsRoute.Profile("bob1"),
            FriendsRoute.Requests,
            FriendsRoute.Home,
        ).forEach { route ->
            val (screen, id) = FriendsRoute.extras(route)
            assertEquals(route, FriendsRoute.parse(screen, id))
        }
    }

    @Test
    fun `malformed extras open plain Friends`() {
        assertEquals(FriendsRoute.Home, FriendsRoute.parse("chat", "../x"))
        assertEquals(FriendsRoute.Home, FriendsRoute.parse("group", "short"))
        assertEquals(FriendsRoute.Home, FriendsRoute.parse("join", null))
        assertEquals(FriendsRoute.Home, FriendsRoute.parse("settings", "x"))
        assertEquals(FriendsRoute.Home, FriendsRoute.parse(null, null))
    }

    @Test
    fun `chats and groups open the Chats tab, the rest the Friends tab`() {
        assertEquals(FriendsTab.CHATS, FriendsRoute.Chat("a").tab)
        assertEquals(FriendsTab.CHATS, FriendsRoute.Group(group).tab)
        assertEquals(FriendsTab.FRIENDS, FriendsRoute.Room(room, join = true).tab)
        assertEquals(FriendsTab.FRIENDS, FriendsRoute.Requests.tab)
        assertEquals(FriendsTab.FRIENDS, FriendsRoute.Profile("a").tab)
    }

    @Test
    fun `the Chats badge counts unread messages from friends and in groups`() {
        val chats = listOf(
            ChatSummary("a", "c1", "hi", "a", 1, unread = 2),
            ChatSummary("gone", "c2", "hi", "gone", 1, unread = 5),
            ChatSummary("b", "c3", "hi", "b", 1, unread = 0),
        )
        val groups = listOf(GroupSummary(group, "Club", 3, "yo", "A", unread = 3, updatedAt = 1))
        assertEquals(5, FriendsTabs.unreadChats(chats, groups, setOf("a", "b")))
        assertEquals(0, FriendsTabs.unreadChats(emptyList(), emptyList(), emptySet()))
    }

    @Test
    fun `the Friends badge counts room invites, read-along and friend requests`() {
        val content = SocialContent("k", "T", "s", "w", "e")
        val invited = SocialRoom(
            room, "host", listOf("host", "me"), content, "invited", 1, 0, null, null, Long.MAX_VALUE,
            members = mapOf("me" to RoomMember("me", RoomMember.INVITED, "Me")),
        )
        val state = FriendsState(
            account = SocialAccount("me", null, true),
            rooms = listOf(invited, invited.copy(id = "d".repeat(64), members = emptyMap())),
            joinRequests = listOf(JoinRequest("x", "X", "x", 0, Long.MAX_VALUE)),
            incoming = listOf(SocialRequest("r", "y", "me")),
        )
        assertEquals(3, FriendsTabs.pendingFriends(state))
        assertEquals(0, FriendsTabs.pendingFriends(state.copy(account = null)))
    }
}

package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class SocialPushMessageTest {
    private val room = "a".repeat(64)

    @Test
    fun `known kinds with a name parse, anything else is dropped`() {
        val invite = SocialPushMessage.parse(
            mapOf(
                "kind" to "room_invite",
                "name" to " Bob ",
                "title" to "Solo",
                "roomId" to room,
            ),
        )
        assertEquals(SocialPushMessage("room_invite", "Bob", title = "Solo", roomId = room), invite)
        assertNull(SocialPushMessage.parse(mapOf("kind" to "promo", "name" to "Bob")))
        assertNull(SocialPushMessage.parse(mapOf("kind" to "friend_request", "name" to "  ")))
        assertNull(SocialPushMessage.parse(mapOf("name" to "Bob")))
    }

    @Test
    fun `network values are length capped and room IDs validated`() {
        val push = SocialPushMessage.parse(
            mapOf("kind" to "friend_request", "name" to "x".repeat(99), "handle" to "h".repeat(99), "roomId" to "../x"),
        )!!
        assertEquals(40, push.name.length)
        assertEquals(24, push.handle!!.length)
        assertNull(push.roomId)
    }

    @Test
    fun `repeat pushes about the same thing share a notification`() {
        val a = SocialPushMessage("room_invite", "Bob", roomId = room)
        assertEquals(a.notificationId(), a.copy(name = "Bobby").notificationId())
        assertNotEquals(a.notificationId(), a.copy(roomId = "b".repeat(64)).notificationId())
    }

    @Test
    fun `sender uid is validated and length capped`() {
        fun uid(
            value: String,
        ) = SocialPushMessage.parse(mapOf("kind" to "message", "name" to "Bob", "uid" to value))!!.uid
        assertEquals("abcDEF123_-", uid("abcDEF123_-"))
        assertNull(uid("../friends"))
        assertNull(uid("a b"))
        assertNull(uid(""))
        assertNull(uid("x".repeat(129)))
        assertEquals(128, uid("x".repeat(128))!!.length)
    }

    @Test
    fun `read match parses with the friend and the title, and is its own notification per title`() {
        val match = SocialPushMessage.parse(
            mapOf("kind" to "read_match", "name" to "Bob", "title" to "Solo Leveling", "uid" to "bob1"),
        )!!
        assertEquals(SocialPushMessage("read_match", "Bob", title = "Solo Leveling", uid = "bob1"), match)
        assertNotEquals(match.notificationId(), match.copy(title = "Berserk").notificationId())
        assertEquals(match.notificationId(), match.copy(name = "Bobby").notificationId())
    }

    @Test
    fun `messages from one friend share a notification, keyed by uid`() {
        val a = SocialPushMessage("message", "Bob", uid = "bob1", title = "hi")
        assertEquals(a.notificationId(), a.copy(title = "again").notificationId())
        assertNotEquals(a.notificationId(), a.copy(uid = "carol").notificationId())
    }

    @Test
    fun `kinds match the backend`() {
        val domain = listOf(File("../backend/src/domain.ts"), File("backend/src/domain.ts")).first { it.exists() }
        val declaration = Regex("""export type NoticeKind = (.*);""").find(domain.readText())!!.groupValues[1]
        val backend = Regex(""""(\w+)"""").findAll(declaration).map { it.groupValues[1] }.toList()
        assertEquals(backend.toSet(), SocialPushMessage.KINDS.toSet())
    }
}

package mihon.feature.social

import mihon.feature.social.SocialForeground.Target
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SocialForegroundTest {
    private val group = "a".repeat(64)
    private val room = "b".repeat(64)
    private val message = SocialPushMessage(SocialPushMessage.MESSAGE, "Bob", title = "hi", uid = "bob")
    private val groupMessage = SocialPushMessage(
        SocialPushMessage.GROUP_MESSAGE,
        "Bob",
        title = "hi",
        group = "Club",
        groupId = group,
        uid = "bob",
    )
    private val invite =
        SocialPushMessage(SocialPushMessage.ROOM_INVITE, "Bob", title = "Solo", roomId = room, uid = "bob")

    @AfterEach
    fun reset() {
        SocialForeground.onScreen?.let(SocialForeground::hide)
    }

    @Test
    fun `the conversation on screen in the foreground stays quiet`() {
        assertFalse(SocialForeground.shouldNotify(message, Target.Chat("bob"), foreground = true))
        assertFalse(SocialForeground.shouldNotify(groupMessage, Target.Group(group), foreground = true))
        assertFalse(SocialForeground.shouldNotify(invite, Target.Room(room), foreground = true))
        // "You both want to read X" lands in that chat too.
        val match = SocialPushMessage(SocialPushMessage.READ_MATCH, "Bob", title = "Solo", uid = "bob")
        assertFalse(SocialForeground.shouldNotify(match, Target.Chat("bob"), foreground = true))
    }

    @Test
    fun `other conversations, the background and nothing on screen still notify`() {
        assertTrue(SocialForeground.shouldNotify(message, Target.Chat("cat"), foreground = true))
        assertTrue(SocialForeground.shouldNotify(message, Target.Group(group), foreground = true))
        assertTrue(SocialForeground.shouldNotify(message, Target.Chat("bob"), foreground = false))
        assertTrue(SocialForeground.shouldNotify(message, null, foreground = true))
        // A group message from Bob isn't the private chat with Bob.
        assertTrue(SocialForeground.shouldNotify(groupMessage, Target.Chat("bob"), foreground = true))
        assertTrue(SocialForeground.shouldNotify(invite, Target.Room("c".repeat(64)), foreground = true))
    }

    @Test
    fun `requests are never about the open conversation`() {
        listOf(
            SocialPushMessage.FRIEND_REQUEST,
            SocialPushMessage.FRIEND_ACCEPTED,
            SocialPushMessage.JOIN_REQUEST,
        ).forEach { kind ->
            val push = SocialPushMessage(kind, "Bob", uid = "bob")
            assertNull(SocialForeground.targetOf(push))
            assertTrue(SocialForeground.shouldNotify(push, Target.Chat("bob"), foreground = true))
        }
        // Malformed subjects were dropped by parse: nothing to match.
        assertNull(SocialForeground.targetOf(SocialPushMessage(SocialPushMessage.MESSAGE, "Bob")))
    }

    @Test
    fun `opening a conversation clears the ids its pushes were shown under`() {
        assertEquals(listOf(message.notificationId()), SocialForeground.notificationIds(Target.Chat("bob")))
        val added = SocialPushMessage(SocialPushMessage.GROUP_ADDED, "Bob", title = "Club", groupId = group)
        assertEquals(
            listOf(groupMessage.notificationId(), added.notificationId()),
            SocialForeground.notificationIds(Target.Group(group)),
        )
        assertEquals(listOf(invite.notificationId()), SocialForeground.notificationIds(Target.Room(room)))
    }

    @Test
    fun `a screen clears only its own mark`() {
        SocialForeground.show(Target.Chat("bob"))
        // The next screen started before the previous one stopped.
        SocialForeground.show(Target.Group(group))
        SocialForeground.hide(Target.Chat("bob"))
        assertEquals(Target.Group(group), SocialForeground.onScreen)
        SocialForeground.hide(Target.Group(group))
        assertNull(SocialForeground.onScreen)
    }
}

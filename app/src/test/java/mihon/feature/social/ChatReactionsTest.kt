package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatReactionsTest {
    private val millis: (Any?) -> Long? = { (it as? Number)?.toLong() }

    @Test
    fun `reaction emojis match the backend - one emoji of at most 16 bytes`() {
        // Same cases as backend isReactionEmoji and the web's test.
        listOf(
            "❤️", "❤", "😂", "😮", "😢", "😡", "👍", "👍🏽",
            "🏳️‍🌈", "❤️‍🔥", "🧑🏽‍💻",
            "1️⃣", "#️⃣", "🇩🇪", "🫠", "©️",
        ).forEach { assertTrue(ReactionEmoji.isValid(it), it) }
        listOf(
            "", "a", "1", "ok", " ", "\n", "😂😂", "🇩🇪🇫🇷",
            "👨‍👩‍👧", "‍", "️", "🏽",
            " ❤", "❤ ", "x❤", "😂\u0000", "👁️‍🗨️",
        ).forEach { assertFalse(ReactionEmoji.isValid(it), it) }
        ChatReactions.QUICK.forEach { assertTrue(ReactionEmoji.isValid(it), it) }
    }

    @Test
    fun `chips count each emoji, mark mine and keep a fixed order`() {
        val chips = ChatReactions.chips(mapOf("a" to "😂", "me" to "❤️", "b" to "❤️", "c" to "👍"), "me")
        assertEquals(
            listOf(ReactionChip("❤️", 2, true), ReactionChip("👍", 1, false), ReactionChip("😂", 1, false)),
            chips,
        )
        assertEquals(emptyList<ReactionChip>(), ChatReactions.chips(emptyMap(), "me"))
    }

    @Test
    fun `toggling sets, replaces and removes my reaction like the server`() {
        val start = mapOf("bob" to "😂")
        val set = ChatReactions.toggle(start, "me", "❤️")
        assertEquals(mapOf("bob" to "😂", "me" to "❤️"), set)
        val replaced = ChatReactions.toggle(set, "me", "👍")
        assertEquals(mapOf("bob" to "😂", "me" to "👍"), replaced)
        assertEquals(start, ChatReactions.toggle(replaced, "me", "👍"))
    }

    @Test
    fun `reactions and quotes parse leniently, malformed parts are dropped`() {
        assertEquals(
            mapOf("a" to "❤️", "b" to "🆕"),
            ChatReactions.parse(
                mapOf("a" to "❤️", "b" to "🆕", "c" to 3, 4 to "😂", "d" to " ", "e" to "x".repeat(17)),
            ),
        )
        assertEquals(emptyMap<String, String>(), ChatReactions.parse("nope"))
        assertEquals(
            ChatQuote(4, "bob", null, "hello"),
            ChatReactions.quote(mapOf("seq" to 4L, "senderUid" to "bob", "preview" to "hello")),
        )
        assertEquals(
            ChatQuote(4, "bob", "Bob", "📖 Solo"),
            ChatReactions.quote(mapOf("seq" to 4, "senderUid" to "bob", "preview" to "📖 Solo", "senderName" to "Bob")),
        )
        assertNull(ChatReactions.quote(mapOf("seq" to 4L, "preview" to "hello")))
        assertNull(ChatReactions.quote(null))
    }

    @Test
    fun `the reply bar previews like the server quote`() {
        val content = SocialContent("work", "Solo", "source", "source-work", "edition")
        assertEquals("📖 Solo", ChatReactions.preview("look", content))
        assertEquals("x".repeat(100), ChatReactions.preview("x".repeat(150), null))
        // Code points, never half an emoji.
        assertEquals("😂".repeat(100), ChatReactions.preview("😂".repeat(120), null))
    }

    @Test
    fun `chat and group messages carry their quote and reactions`() {
        val message = ChatMessages.parse(
            mapOf(
                "seq" to 7L,
                "senderUid" to "bob",
                "text" to "agreed",
                "createdAt" to 9L,
                "replyTo" to mapOf("seq" to 3L, "senderUid" to "me", "preview" to "Read this?"),
                "reactions" to mapOf("me" to "❤️"),
            ),
            millis,
        )!!
        assertEquals(ChatQuote(3, "me", null, "Read this?"), message.replyTo)
        assertEquals(mapOf("me" to "❤️"), message.reactions)
        val plain = ChatMessages.parse(mapOf("seq" to 8L, "senderUid" to "bob", "text" to "hi"), millis)!!
        assertNull(plain.replyTo)
        assertEquals(emptyMap<String, String>(), plain.reactions)
    }

    @Test
    fun `group quotes from before I joined or by people I blocked are hidden, and so are their reactions`() {
        val quote = ChatQuote(seq = 5, senderUid = "a", senderName = "A", preview = "hi")
        assertEquals(quote, ChatReactions.visibleQuote(quote, since = 3, blocked = emptySet()))
        assertEquals(quote, ChatReactions.visibleQuote(quote, since = 5, blocked = emptySet()))
        assertNull(ChatReactions.visibleQuote(quote, since = 6, blocked = emptySet()))
        assertNull(ChatReactions.visibleQuote(quote, since = 0, blocked = setOf("a")))
        val reactions = mapOf("a" to "❤️", "b" to "😂", "me" to "❤️")
        assertEquals(mapOf("a" to "❤️", "me" to "❤️"), ChatReactions.withoutBlocked(reactions, setOf("b")))
        assertEquals(
            listOf(ReactionChip("❤️", 2, true)),
            ChatReactions.chips(ChatReactions.withoutBlocked(reactions, setOf("b")), "me"),
        )
    }
}

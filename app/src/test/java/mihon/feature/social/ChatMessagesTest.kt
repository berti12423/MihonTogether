package mihon.feature.social

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatMessagesTest {
    private val content = SocialContent("work", "Solo", "source", "source-work", "edition")
    private val millis: (Any?) -> Long? = { (it as? Number)?.toLong() }

    private fun parse(vararg fields: Pair<String, Any?>) =
        ChatMessages.parse(mapOf("seq" to 3L, "senderUid" to "bob", "createdAt" to 9L, *fields), millis)

    @Test
    fun `plain messages and recommendations parse, unknown fields are ignored`() {
        assertEquals(ChatMessage(3, "bob", "hi", 9), parse("text" to "hi", "future" to mapOf("x" to 1)))
        assertEquals(ChatMessage(3, "bob", "", 9, content), parse("text" to "", "content" to content.payload()))
        assertNull(ChatMessages.parse(mapOf("senderUid" to "bob", "text" to "hi"), millis))
        assertNull(parse("text" to ""))
    }

    @Test
    fun `a match carries the title, without it nothing is shown`() {
        val match = parse("text" to "", "system" to "match", "content" to content.payload())!!
        assertEquals(ChatMessage.SYSTEM_MATCH, match.system)
        assertEquals(content, match.content)
        assertNull(parse("text" to "", "system" to "match"))
        assertNull(parse("text" to "", "system" to "match", "content" to mapOf("title" to "broken")))
    }

    @Test
    fun `a recap reads text or number chapter labels and caps its fields`() {
        val recap = parse(
            "text" to "",
            "system" to "recap",
            "recap" to mapOf("title" to " Solo ", "fromChapter" to 12, "toChapter" to "14.5", "minutes" to 42),
        )!!
        assertEquals(ChatMessage.SYSTEM_RECAP, recap.system)
        assertEquals(ChatRecap("Solo", "12", "14.5", 42), recap.recap)

        val odd = parse(
            "system" to "recap",
            "recap" to mapOf("title" to "Solo", "fromChapter" to 7.5, "minutes" to -3),
        )!!.recap!!
        assertEquals(ChatRecap("Solo", "7.5", null, null), odd)
        assertNull(parse("system" to "recap", "recap" to mapOf("minutes" to 4)))
    }

    @Test
    fun `an unknown system line is only shown when it has text`() {
        assertEquals(ChatMessage(3, "bob", "Something new", 9), parse("text" to "Something new", "system" to "poll"))
        assertNull(parse("text" to "", "system" to "poll"))
    }

    @Test
    fun `recap chapter span`() {
        assertEquals("1" to "3", ChatRecap("T", "1", "3", null).chapterSpan())
        assertEquals("2" to null, ChatRecap("T", "2", "2", null).chapterSpan())
        assertEquals("5" to null, ChatRecap("T", null, "5", null).chapterSpan())
        assertNull(ChatRecap("T", null, null, 3).chapterSpan())
    }

    @Test
    fun `read wishes parse, expire and sort matches first`() {
        val data = mapOf(
            "otherUid" to "bob",
            "contentKey" to "work",
            "content" to content.payload(),
            "matched" to true,
            "matchedAt" to 50L,
            "createdAt" to 10L,
            "expiresAt" to 1_000L,
        )
        val wish = ReadWish.parse(data, millis, now = 100)!!
        assertTrue(wish.matched)
        assertEquals(content, wish.content)
        assertNull(ReadWish.parse(data, millis, now = 1_000))
        assertNull(ReadWish.parse(data - "otherUid", millis, now = 100))
        // A content that isn't the wished key isn't trusted for display.
        assertNull(ReadWish.parse(data + ("contentKey" to "other"), millis, now = 100)!!.content)

        val open = ReadWish("carol", "a", null, matched = false, matchedAt = null, createdAt = 90, expiresAt = null)
        val older = open.copy(otherUid = "dan", createdAt = 20)
        assertFalse(open.matched)
        assertEquals(listOf(wish, open, older), ReadWish.sorted(listOf(older, open, wish)))
    }
}

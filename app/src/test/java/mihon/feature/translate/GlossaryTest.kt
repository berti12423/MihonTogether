package mihon.feature.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlossaryTest {
    @Test
    fun `replacements apply in order, ignoring case, and survive a round trip`() {
        val glossary = Glossary(
            listOf(
                GlossaryEntry("Jin-woo", "Jinwoo"),
                GlossaryEntry("hunter", "Hunter"),
                GlossaryEntry("  ", "ignored"),
            ),
        )
        assertEquals("Jinwoo is an E-rank Hunter.", glossary.apply("jin-woo is an E-rank hunter."))
        val cleaned = glossary.cleaned()
        assertEquals(2, cleaned.entries.size)
        assertEquals(cleaned, Glossary.decode(cleaned.encode()))
        assertEquals(Glossary.EMPTY, Glossary.decode(""))
        assertEquals(Glossary.EMPTY, Glossary.decode("not json"))
        assertEquals("", Glossary.EMPTY.encode())
        assertEquals("same", Glossary.EMPTY.apply("same"))
    }

    @Test
    fun `room quotes keep the translation and stay within 200 characters`() {
        assertEquals("❝잠깐만!❞ → Wait!", RoomQuote.format(" 잠깐만!\n", "Wait! "))

        val longOriginal = "가".repeat(300)
        val quote = RoomQuote.format(longOriginal, "Wait, where are you going?")
        assertTrue(quote.length <= 200, "${quote.length}")
        assertTrue(quote.endsWith("❞ → Wait, where are you going?"))
        assertTrue("…" in quote)

        val both = RoomQuote.format("가".repeat(300), "a".repeat(300))
        assertTrue(both.length <= 200, "${both.length}")
        assertTrue(both.startsWith("❝") && both.endsWith("…"))
    }
}

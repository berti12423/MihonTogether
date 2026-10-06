package mihon.feature.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypesetMathTest {
    @Test
    fun `text area adds a margin, widens narrow vertical boxes and stays in the image`() {
        val normal = TypesetMath.textArea(NormBox(0.1f, 0.2f, 0.2f, 0.6f), 1000, 1000)
        assertTrue(normal.left < 200 && normal.right > 600 && normal.top < 100 && normal.bottom > 200)

        // A one-column vertical bubble (40 px wide, 300 px tall) gets room for horizontal English.
        val vertical = TypesetMath.textArea(NormBox(0.1f, 0.48f, 0.4f, 0.52f), 1000, 1000)
        assertTrue(vertical.width >= 180, "width ${vertical.width}")
        assertTrue(kotlin.math.abs((vertical.left + vertical.right) / 2 - 500) <= 1, "still centered")

        val edge = TypesetMath.textArea(NormBox(0f, 0f, 0.05f, 0.05f), 500, 500)
        assertTrue(edge.left >= 0 && edge.top >= 0)
    }

    @Test
    fun `font size bisects to the largest fitting size`() {
        assertEquals(20f, TypesetMath.fitTextSize(8f, 40f) { it <= 20f }, 0.5f)
        assertEquals(8f, TypesetMath.fitTextSize(8f, 40f) { false }) // nothing fits: smallest
        assertEquals(40f, TypesetMath.fitTextSize(8f, 40f) { true }, 0.5f)
    }

    @Test
    fun `background is the median colour and text contrasts with it`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        // Mostly white paper with a few black text strokes on the border.
        assertEquals(white, TypesetMath.medianColor(IntArray(20) { if (it < 6) black else white }))
        assertEquals(black, TypesetMath.textColorFor(white))
        assertEquals(white, TypesetMath.textColorFor(0xFF202124.toInt()))
    }

    @Test
    fun `word breaks inside a word are detected, normal wraps are fine`() {
        val text = "Uh... Welcome."
        assertTrue(TypesetMath.breaksInsideWord(text, listOf(12, 14))) // "Uh... Welcom" / "e."
        assertTrue(!TypesetMath.breaksInsideWord(text, listOf(6, 14))) // "Uh... " / "Welcome."
        assertTrue(TypesetMath.breaksInsideWord("Welcome~", listOf(6, 8))) // one English word, split
        assertTrue(TypesetMath.breaksInsideWord("Uh... Welcome.", listOf(6, 13, 14))) // stranded "."
        assertTrue(!TypesetMath.breaksInsideWord("part-time job", listOf(5, 13))) // after a hyphen is fine
        assertTrue(!TypesetMath.breaksInsideWord("안녕하세요", listOf(2, 5))) // CJK without spaces: any break is fine
        val grown = TypesetMath.grow(PixelRect(100, 100, 200, 200), 1.3f, 1000, 1000)
        assertEquals(PixelRect(85, 85, 215, 215), grown)
    }

    @Test
    fun `hyphenated breaks are fine with three letters on each side`() {
        val text = "Translation please"
        assertTrue(!TypesetMath.breaksInsideWord(text, listOf(6, 18), minFragment = 3)) // "Transl-" / "ation"
        assertTrue(TypesetMath.breaksInsideWord(text, listOf(2, 18), minFragment = 3)) // "Tr-" is too short
        assertTrue(TypesetMath.breaksInsideWord(text, listOf(10, 18), minFragment = 3)) // "n" alone: too short
        assertTrue(TypesetMath.breaksInsideWord(text, listOf(6, 18))) // strict by default
    }

    @Test
    fun `pixel boxes, expanding and insetting stay inside the image`() {
        assertEquals(PixelRect(100, 50, 300, 150), TypesetMath.pixelBox(NormBox(0.1f, 0.2f, 0.3f, 0.6f), 500, 500))
        assertEquals(PixelRect(0, 40, 320, 160), TypesetMath.expand(PixelRect(10, 50, 310, 150), 10, 320, 500))
        assertEquals(PixelRect(12, 12, 18, 18), TypesetMath.inset(PixelRect(10, 10, 20, 20), 2))
        val tiny = TypesetMath.inset(PixelRect(10, 10, 12, 12), 5)
        assertTrue(tiny.width >= 1 && tiny.height >= 1)
        assertTrue(TypesetMath.isLight(0xFFF5F5F5.toInt()))
        assertTrue(!TypesetMath.isLight(0xFF101010.toInt()))
    }
}

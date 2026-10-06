package mihon.feature.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class BubbleMathTest {
    /** A white image with a black elliptical outline (3 px) and black "letters" (small blocks) in the middle. */
    private fun page(
        width: Int = 200,
        height: Int = 200,
        rx: Double = 45.0,
        ry: Double = 25.0,
        outline: Boolean = true,
    ): IntArray {
        val lum = IntArray(width * height) { 255 }
        val cx = width / 2.0
        val cy = height / 2.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val d = hypot((x - cx) / rx, (y - cy) / ry)
                if (outline && d in 0.97..1.03) lum[y * width + x] = 0
            }
        }
        // Four letters of 10x20 px with 5 px gaps, around (100, 100).
        for (letter in 0 until 4) {
            for (y in 90 until 110) for (x in (72 + letter * 15) until (82 + letter * 15)) lum[y * width + x] = 20
        }
        return lum
    }

    private val textBox = PixelRect(71, 89, 128, 111)

    @Test
    fun `a filled outlined bubble gives a large inner rectangle around the text`() {
        val gray = GrayImage(200, 200, page())
        val bubble = BubbleMath.findBubble(gray, textBox, lightThreshold = 195)!!
        val inner = bubble.inner
        // Contains the text box, larger than it, and stays inside the ellipse (45 x 25 radii: at best 64 x 35).
        assertTrue(inner.left <= textBox.left && inner.right >= textBox.right, "$inner")
        assertTrue(inner.top <= textBox.top + 2 && inner.bottom >= textBox.bottom - 2, "$inner")
        assertTrue(inner.width * inner.height > 1.5 * textBox.width * textBox.height, "$inner")
        assertTrue(inner.width <= 66 && inner.height <= 37, "$inner")
        // Letters are inside the bubble (holes filled), the outline and the outside are not.
        assertTrue(bubble.contains(75, 100))
        assertFalse(bubble.contains(100, 75)) // on the outline (cy - ry)
        assertFalse(bubble.contains(5, 5))
    }

    @Test
    fun `text on open background or in a huge area is not a bubble`() {
        // No outline: the fill runs to the edge.
        assertNull(BubbleMath.findBubble(GrayImage(200, 200, page(outline = false)), textBox, 195))
        // Outline far bigger than the text: more than 5x the box area.
        assertNull(BubbleMath.findBubble(GrayImage(400, 400, page(400, 400, 190.0, 190.0)), textBox, 195))
        // Dark everywhere: no light seed.
        assertNull(BubbleMath.findBubble(GrayImage(10, 10, IntArray(100)), PixelRect(2, 2, 8, 8), 195))
    }

    @Test
    fun `inner rectangle of a plain rectangle mask is the rectangle`() {
        val w = 20
        val h = 10
        val mask = BooleanArray(w * h) { i -> i % w in 3 until 15 && i / w in 2 until 8 }
        assertEquals(PixelRect(3, 2, 15, 8), BubbleMath.innerRect(mask, w, h, 10, 5))
        assertNull(BubbleMath.innerRect(mask, w, h, 0, 0))
    }

    @Test
    fun `holes enclosed by the mask are filled, the outside stays out`() {
        val w = 7
        val h = 7
        // A ring of mask around a 1-pixel hole at (3, 3).
        val mask = BooleanArray(w * h) { i -> i % w in 2..4 && i / w in 2..4 && !(i % w == 3 && i / w == 3) }
        val filled = BubbleMath.fillHoles(mask, w, h, PixelRect(2, 2, 5, 5))
        assertTrue(filled[3 * w + 3])
        assertTrue(filled[2 * w + 2])
        assertFalse(filled[0])
    }

    @Test
    fun `downsampling keeps the darkest pixel so thin outlines survive`() {
        val lum = IntArray(16 * 16) { 255 }
        for (y in 0 until 16) lum[y * 16 + 5] = 0 // a 1 px vertical line
        val small = BubbleMath.downsampleMin(lum, 16, 16, 4)
        assertEquals(4, small.width)
        assertEquals(0, small[1, 2])
        assertEquals(255, small[3, 2])
        assertEquals(2, BubbleMath.cellSize(512, 100))
        assertEquals(1, BubbleMath.cellSize(200, 200))
    }

    @Test
    fun `erasing hits only dark letter pixels and their edge, inside the allowed area`() {
        val w = 10
        val h = 5
        val lum = IntArray(w * h) { 250 }
        lum[2 * w + 3] = 10 // a letter pixel
        lum[2 * w + 8] = 10 // part of the outline: not allowed
        val mask = BubbleMath.eraseMask(lum, w, h, PixelRect(0, 0, w, h), paper = 250, radius = 1) { x, _ -> x < 7 }
        assertTrue(mask[2 * w + 3])
        assertTrue(mask[1 * w + 2] && mask[3 * w + 4]) // grown by one pixel
        assertFalse(mask[0]) // plain paper stays
        assertFalse(mask[2 * w + 8]) // outline survives
        assertEquals(9, mask.count { it })
    }

    @Test
    fun `luminance weights green most`() {
        assertEquals(255, BubbleMath.luminance(0xFFFFFFFF.toInt()))
        assertEquals(0, BubbleMath.luminance(0xFF000000.toInt()))
        assertTrue(BubbleMath.luminance(0xFF00FF00.toInt()) > BubbleMath.luminance(0xFFFF0000.toInt()))
    }
}

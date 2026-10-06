package mihon.feature.translate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageSlicerTest {
    @Test
    fun `normal pages go out whole, strips become overlapping 2x3 slices`() {
        assertEquals(listOf(Slice(0, 1200)), PageSlicer.slices(800, 1200))
        assertEquals(listOf(Slice(0, 1000)), PageSlicer.slices(500, 1000))

        val slices = PageSlicer.slices(500, 3300) // the spike's strip
        assertEquals(5, slices.size)
        assertEquals(0, slices.first().top)
        assertEquals(3300, slices.last().bottom)
        assertTrue(slices.all { it.height == 750 })
        slices.zipWithNext().forEach { (a, b) -> assertTrue(b.top < a.bottom, "slices overlap: $a $b") }
    }

    @Test
    fun `slice boxes map back into page space`() {
        val box = NormBox(top = 0.5f, left = 0.1f, bottom = 0.6f, right = 0.9f)
        val page = PageSlicer.toPage(box, Slice(top = 1000, bottom = 1750), pageHeight = 3000)
        assertEquals((1000 + 375) / 3000f, page.top, 1e-6f)
        assertEquals((1000 + 450) / 3000f, page.bottom, 1e-6f)
        assertEquals(0.1f, page.left)
        assertEquals(0.9f, page.right)
    }

    @Test
    fun `seam duplicates collapse to the larger box and keep reading order`() {
        fun block(text: String, top: Float, bottom: Float) =
            TranslatedBlock(NormBox(top, 0.1f, bottom, 0.4f), "sfx", false, text, text)
        val merged = PageSlicer.merge(
            listOf(
                block("first", 0.05f, 0.1f),
                block("creak (cut)", 0.20f, 0.24f), // bottom of slice 1, cut by the seam
                block("creak", 0.20f, 0.26f), // same SFX, whole, from slice 2
                block("last", 0.5f, 0.6f),
            ),
        )
        assertEquals(listOf("first", "creak", "last"), merged.map { it.source })
    }

    @Test
    fun `box_2d is clamped and invalid boxes are dropped`() {
        assertEquals(NormBox(0.1f, 0.2f, 0.3f, 1f), NormBox.fromBox2d(listOf(100, 200, 300, 1200)))
        assertEquals(null, NormBox.fromBox2d(listOf(300, 200, 100, 400)))
        assertEquals(null, NormBox.fromBox2d(listOf(1, 2, 3)))
    }
}

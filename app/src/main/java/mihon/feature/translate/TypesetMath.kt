package mihon.feature.translate

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A pixel rectangle; [right] and [bottom] are exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Platform-free layout decisions for drawing translations into a page (used by [BitmapPageImages]). */
object TypesetMath {
    /**
     * The area the translation may cover: the text box plus a small margin, widened for tall narrow boxes (vertical
     * Japanese) because English laid out in a one-character-wide column is unreadable. Stays inside the image.
     */
    fun textArea(box: NormBox, imageWidth: Int, imageHeight: Int): PixelRect {
        var left = box.left * imageWidth
        var right = box.right * imageWidth
        var top = box.top * imageHeight
        var bottom = box.bottom * imageHeight
        // Model boxes are often a little tight; a generous margin hides leftover original strokes.
        val margin = max(4f, min(right - left, bottom - top) * 0.18f)
        left -= margin
        right += margin
        top -= margin
        bottom += margin
        val minWidth = min((bottom - top) * 0.6f, imageWidth * 0.45f)
        if (right - left < minWidth) {
            val center = (left + right) / 2
            left = center - minWidth / 2
            right = center + minWidth / 2
        }
        return PixelRect(
            left.toInt().coerceIn(0, imageWidth - 1),
            top.toInt().coerceIn(0, imageHeight - 1),
            right.toInt().coerceIn(1, imageWidth),
            bottom.toInt().coerceIn(1, imageHeight),
        )
    }

    /** [box] in pixels without any margin, inside the image. */
    fun pixelBox(box: NormBox, imageWidth: Int, imageHeight: Int): PixelRect = PixelRect(
        (box.left * imageWidth).toInt().coerceIn(0, imageWidth - 1),
        (box.top * imageHeight).toInt().coerceIn(0, imageHeight - 1),
        (box.right * imageWidth).roundToInt().coerceIn(1, imageWidth),
        (box.bottom * imageHeight).roundToInt().coerceIn(1, imageHeight),
    )

    /** [area] grown by [margin] pixels on every side, inside the image. */
    fun expand(area: PixelRect, margin: Int, imageWidth: Int, imageHeight: Int) = PixelRect(
        (area.left - margin).coerceAtLeast(0),
        (area.top - margin).coerceAtLeast(0),
        (area.right + margin).coerceAtMost(imageWidth),
        (area.bottom + margin).coerceAtMost(imageHeight),
    )

    /** [area] shrunk by [inset] pixels on every side (never inverted). */
    fun inset(area: PixelRect, inset: Int): PixelRect {
        val dx = min(inset, (area.width - 1) / 2).coerceAtLeast(0)
        val dy = min(inset, (area.height - 1) / 2).coerceAtLeast(0)
        return PixelRect(area.left + dx, area.top + dy, area.right - dx, area.bottom - dy)
    }

    /**
     * Largest text size in [min, max] for which [fits] holds, by bisection to half a pixel. Returns [min] if even that
     * doesn't fit (the caller then lets the text overflow a little rather than vanish).
     */
    fun fitTextSize(min: Float, max: Float, fits: (Float) -> Boolean): Float {
        if (!fits(min)) return min
        var low = min
        var high = max
        while (high - low > 0.5f) {
            val mid = (low + high) / 2
            if (fits(mid)) low = mid else high = mid
        }
        return low
    }

    /** [area] scaled by [factor] around its center, kept inside the image. */
    fun grow(area: PixelRect, factor: Float, imageWidth: Int, imageHeight: Int): PixelRect {
        val dx = (area.width * (factor - 1) / 2).roundToInt()
        val dy = (area.height * (factor - 1) / 2).roundToInt()
        return PixelRect(
            (area.left - dx).coerceAtLeast(0),
            (area.top - dy).coerceAtLeast(0),
            (area.right + dx).coerceAtMost(imageWidth),
            (area.bottom + dy).coerceAtMost(imageHeight),
        )
    }

    /**
     * True if a line wrap (at the [lineEnds] offsets a text layout produced) falls inside a word, like "Welcom/e".
     * Wrapping at a space, or after a hyphen, dash or slash, is fine; so is CJK text without spaces, where any break is.
     * With [minFragment], a hyphenated break is fine when at least that many letters stay on each side ("transla-/tion").
     */
    fun breaksInsideWord(text: String, lineEnds: List<Int>, minFragment: Int = Int.MAX_VALUE): Boolean {
        if (text.none { it.isWhitespace() } && text.any { it.code >= CJK_START }) return false
        return lineEnds.dropLast(1).any { end ->
            val before = text.getOrNull(end - 1) ?: return@any false
            val after = text.getOrNull(end) ?: return@any false
            // Breaking between two non-space characters splits a word ("Welcom/e") or strands punctuation ("Welcome/.").
            val inside = !before.isWhitespace() && !after.isWhitespace() && before !in "-—–/"
            inside && !(lettersBefore(text, end) >= minFragment && lettersAfter(text, end) >= minFragment)
        }
    }

    private fun lettersBefore(text: String, end: Int): Int {
        var count = 0
        var i = end - 1
        while (i >= 0 && !text[i].isWhitespace()) {
            if (!text[i].isLetter()) return count
            count++
            i--
        }
        return count
    }

    private fun lettersAfter(text: String, start: Int): Int {
        var count = 0
        var i = start
        while (i < text.length && !text[i].isWhitespace()) {
            if (!text[i].isLetter()) return count
            count++
            i++
        }
        return count
    }

    /** Whether text on [background] would be black, i.e. it's light paper (where bubble detection makes sense). */
    fun isLight(background: Int): Boolean = textColorFor(background) == 0xFF000000.toInt()

    private const val CJK_START = 0x2E80

    /** Per-channel median of ARGB pixels: the bubble's paper colour, ignoring text strokes and a few outliers. */
    fun medianColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 0xFFFFFFFF.toInt()
        fun median(shift: Int): Int {
            val channel = IntArray(pixels.size) { (pixels[it] shr shift) and 0xFF }
            channel.sort()
            return channel[channel.size / 2]
        }
        return (0xFF shl 24) or (median(16) shl 16) or (median(8) shl 8) or median(0)
    }

    /** Black text on light backgrounds, white on dark ones. */
    fun textColorFor(background: Int): Int {
        val r = (background shr 16) and 0xFF
        val g = (background shr 8) and 0xFF
        val b = background and 0xFF
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255
        return if (luminance > 0.55) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
}

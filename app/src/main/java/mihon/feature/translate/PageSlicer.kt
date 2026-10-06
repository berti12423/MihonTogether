package mihon.feature.translate

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A horizontal band of a page, in pixels: rows [top, bottom). */
data class Slice(val top: Int, val bottom: Int) {
    val height get() = bottom - top
}

/**
 * Tall pages (webtoon strips) must be cut into page-shaped pieces before a vision model sees them: on whole 1:6 strips
 * the model's boxes drift by a bubble's height (docs/translation-spike.md). Slices are about 2:3 and overlap, so text
 * crossing a cut is fully inside at least one slice; [merge] then drops the duplicate found in the overlap.
 */
object PageSlicer {
    /** Pages up to this height/width ratio go out whole. */
    const val MAX_WHOLE_RATIO = 2.0f

    /** Slice height as a multiple of the page width (2:3). */
    const val SLICE_RATIO = 1.5f

    /** Fraction of a slice repeated in the next one. */
    const val OVERLAP = 0.12f

    fun slices(width: Int, height: Int): List<Slice> {
        require(width > 0 && height > 0)
        if (height <= width * MAX_WHOLE_RATIO) return listOf(Slice(0, height))
        val sliceHeight = (width * SLICE_RATIO).roundToInt()
        val step = (sliceHeight * (1 - OVERLAP)).roundToInt()
        // Evenly spaced cuts: same count as stepping by [step], but the last slice isn't a sliver.
        val count = 1 + ceil((height - sliceHeight).toDouble() / step).toInt()
        val stride = (height - sliceHeight).toDouble() / (count - 1)
        return (0 until count).map { i ->
            val top = (i * stride).roundToInt()
            Slice(top, min(height, top + sliceHeight))
        }
    }

    /** Maps a box normalized to a slice into a box normalized to the whole page. */
    fun toPage(box: NormBox, slice: Slice, pageHeight: Int): NormBox {
        val top = (slice.top + box.top * slice.height) / pageHeight
        val bottom = (slice.top + box.bottom * slice.height) / pageHeight
        return box.copy(top = top, bottom = bottom)
    }

    /**
     * Combines per-slice results (already in page space, slices top to bottom, each in the model's reading order, so
     * the concatenation is the reading order) and removes duplicates from overlaps: two blocks that mostly cover the
     * same area are one bubble. The larger box wins, since it is less likely to be cut off by a seam; it takes the
     * earlier one's place in the order.
     */
    fun merge(blocks: List<TranslatedBlock>): List<TranslatedBlock> {
        val kept = mutableListOf<TranslatedBlock>()
        for (block in blocks) {
            val duplicate = kept.indexOfFirst { overlapRatio(it.box, block.box) > 0.5f }
            when {
                duplicate == -1 -> kept += block
                block.box.area > kept[duplicate].box.area -> kept[duplicate] = block
            }
        }
        return kept
    }

    /** Intersection over the smaller box: 1.0 when one box sits inside the other. */
    fun overlapRatio(a: NormBox, b: NormBox): Float {
        val smaller = min(a.area, b.area)
        return if (smaller <= 0f) 0f else a.intersection(b) / smaller
    }

    /** Long side cap for images sent to the model: enough for small manga text, without wasting tokens. */
    const val MAX_UPLOAD_SIDE = 1600

    /** Scale factor (<= 1) so neither side of a [width] x [height] image exceeds [maxSide] (0: no limit). */
    fun uploadScale(width: Int, height: Int, maxSide: Int = MAX_UPLOAD_SIDE): Float =
        if (maxSide <= 0) 1f else min(1f, maxSide.toFloat() / max(width, height))
}

/** The long-side cap ([maxSide], 0 for none) and JPEG [quality] of the slices an engine is given. */
data class ImageFormat(val maxSide: Int, val quality: Int) {
    companion object {
        /** For cloud vision models: enough for manga text without wasting upload tokens. */
        val UPLOAD = ImageFormat(PageSlicer.MAX_UPLOAD_SIDE, 90)

        /** For on-device OCR: full resolution (capped only to bound memory), near-lossless. */
        val OCR = ImageFormat(4096, 95)
    }
}

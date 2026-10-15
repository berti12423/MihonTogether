package mihon.feature.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android image work for [PageTranslator]: slices pages for upload and draws translations into a copy of the page.
 * In outlined light bubbles only the original letters are erased and the translation fills the bubble ([BubbleMath]);
 * elsewhere the text box is covered with its sampled paper colour.
 */
class BitmapPageImages : PageImages {
    override fun size(image: ByteArray): Pair<Int, Int>? {
        if (ImageUtil.isAnimatedAndSupported(Buffer().write(image))) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /** The page as lossless PNG, for receivers that can't read its format (the PC translator and AVIF). */
    fun toPng(image: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return null
        return try {
            java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    override fun encodeSlice(image: ByteArray, slice: Slice, format: ImageFormat): ByteArray {
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(image, 0, image.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(image, 0, image.size, false)
        } ?: error("Page image couldn't be decoded")
        try {
            val region = decoder.decodeRegion(Rect(0, slice.top, decoder.width, slice.bottom), null)
            val scale = PageSlicer.uploadScale(region.width, region.height, format.maxSide)
            val upload = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    region,
                    (region.width * scale).roundToInt(),
                    (region.height * scale).roundToInt(),
                    true,
                )
                    .also { region.recycle() }
            } else {
                region
            }
            return Buffer().also {
                upload.compress(Bitmap.CompressFormat.JPEG, format.quality, it.outputStream())
            }.readByteArray()
                .also { upload.recycle() }
        } finally {
            decoder.recycle()
        }
    }

    override fun render(image: ByteArray, blocks: List<TranslatedBlock>, out: File) {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            // Very large strips: halve rather than risk running out of memory.
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(image, 0, image.size, options)
        options.inSampleSize = if (options.outWidth.toLong() * options.outHeight > MAX_RENDER_PIXELS) 2 else 1
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, options)
            ?: error("Page image couldn't be decoded")
        try {
            val canvas = Canvas(bitmap)
            blocks.forEach { draw(canvas, bitmap, it) }
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun draw(canvas: Canvas, bitmap: Bitmap, block: TranslatedBlock) {
        val box = TypesetMath.textArea(block.box, bitmap.width, bitmap.height)
        if (box.width < 4 || box.height < 4) return
        val background = TypesetMath.medianColor(borderPixels(bitmap, box))
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TypesetMath.textColorFor(background)
            typeface = comicTypeface
        }
        val minSize = max(7f, bitmap.width / 110f)

        // Light bubble with an outline: erase only the letters and use the whole bubble for the translation.
        val bubble = if (TypesetMath.isLight(background)) findBubble(bitmap, block.box, background) else null
        val inBubble = bubble?.let { fit(block.translation, it.textArea, paint, bitmap, minSize) }
        if (bubble != null && inBubble != null) {
            erase(bitmap, bubble, background)
            drawText(canvas, block.translation, paint, inBubble)
            return
        }

        // Otherwise cover the text box. If the translation can't fit without breaking words, grow it a little once.
        val fitted = fit(block.translation, box, paint, bitmap, minSize)
            ?: fit(block.translation, TypesetMath.grow(box, 1.3f, bitmap.width, bitmap.height), paint, bitmap, minSize)
            ?: Fitted(box, minSize, padding(box), hyphenate = true)
        val area = fitted.area
        val radius = minOf(area.width, area.height) * 0.2f
        canvas.drawRoundRect(
            RectF(area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat()),
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background },
        )
        drawText(canvas, block.translation, paint, fitted)
    }

    private fun drawText(canvas: Canvas, text: String, paint: TextPaint, fitted: Fitted) {
        val area = fitted.area
        val textWidth = area.width - 2 * fitted.padding
        val textHeight = area.height - 2 * fitted.padding
        if (textWidth <= 0 || textHeight <= 0) return
        val layout = layout(text, paint, fitted.size, textWidth, fitted.hyphenate)
        canvas.save()
        canvas.translate(
            (area.left + fitted.padding).toFloat(),
            area.top + fitted.padding + (textHeight - layout.height) / 2f,
        )
        layout.draw(canvas)
        canvas.restore()
    }

    /** A bubble around a text box, in page pixels: where the text goes, and which pixels may be erased. */
    private class FoundBubble(
        val textArea: PixelRect,
        val textBox: PixelRect,
        val region: PixelRect,
        val cell: Int,
        val bubble: Bubble,
    ) {
        fun inside(x: Int, y: Int) = bubble.contains((x - region.left) / cell, (y - region.top) / cell)
    }

    /**
     * Looks for an outlined bubble around [box] on a min-downsampled copy of the area around it. Null (use the plain
     * rectangle) when the fill escapes, the bubble is smaller than the text, or the area is too big to scan.
     */
    private fun findBubble(bitmap: Bitmap, box: NormBox, paper: Int): FoundBubble? {
        val textBox = TypesetMath.pixelBox(box, bitmap.width, bitmap.height)
        if (textBox.width < 4 || textBox.height < 4) return null
        val margin = (max(textBox.width, textBox.height) * 1.5f).roundToInt()
        val region = TypesetMath.expand(textBox, margin, bitmap.width, bitmap.height)
        if (region.width.toLong() * region.height > MAX_BUBBLE_SCAN_PIXELS) return null
        val pixels = IntArray(region.width * region.height)
        bitmap.getPixels(pixels, 0, region.width, region.left, region.top, region.width, region.height)
        val lum = IntArray(pixels.size) { BubbleMath.luminance(pixels[it]) }
        val cell = BubbleMath.cellSize(region.width, region.height)
        val grid = BubbleMath.downsampleMin(lum, region.width, region.height, cell)
        val gridBox = PixelRect(
            (textBox.left - region.left) / cell,
            (textBox.top - region.top) / cell,
            (textBox.right - region.left + cell - 1) / cell,
            (textBox.bottom - region.top + cell - 1) / cell,
        )
        val threshold = (BubbleMath.luminance(paper) - 60).coerceIn(90, 230)
        val bubble = BubbleMath.findBubble(grid, gridBox, threshold) ?: return null
        // Back to page pixels, one cell in from the outline.
        val inner = TypesetMath.inset(
            PixelRect(
                region.left + bubble.inner.left * cell,
                region.top + bubble.inner.top * cell,
                region.left + bubble.inner.right * cell,
                region.top + bubble.inner.bottom * cell,
            ),
            cell,
        )
        if (inner.width.toLong() * inner.height < textBox.width.toLong() * textBox.height / 2) return null
        return FoundBubble(inner, textBox, region, cell, bubble)
    }

    /** Paints the paper colour over the original letters only: dark pixels near the text box, inside the bubble. */
    private fun erase(bitmap: Bitmap, found: FoundBubble, paper: Int) {
        val box = found.textBox
        val widen = max(3, (minOf(box.width, box.height) * 0.12f).roundToInt())
        val area = TypesetMath.expand(box, widen, bitmap.width, bitmap.height)
        val pixels = IntArray(area.width * area.height)
        bitmap.getPixels(pixels, 0, area.width, area.left, area.top, area.width, area.height)
        val lum = IntArray(pixels.size) { BubbleMath.luminance(pixels[it]) }
        val mask = BubbleMath.eraseMask(
            lum,
            area.width,
            area.height,
            PixelRect(0, 0, area.width, area.height),
            paper = BubbleMath.luminance(paper),
            radius = max(1, bitmap.width / 600),
        ) { x, y -> found.inside(area.left + x, area.top + y) }
        for (i in pixels.indices) if (mask[i]) pixels[i] = paper
        bitmap.setPixels(pixels, 0, area.width, area.left, area.top, area.width, area.height)
    }

    private data class Fitted(val area: PixelRect, val size: Float, val padding: Int, val hyphenate: Boolean)

    private fun padding(area: PixelRect) = max(2, (minOf(area.width, area.height) * 0.06f).roundToInt())

    /**
     * Largest text size that fits [area] without breaking a word, or null if even [minSize] doesn't. Hyphenated
     * layouts are used only when they allow clearly larger text (or nothing fits otherwise).
     */
    private fun fit(text: String, area: PixelRect, paint: TextPaint, bitmap: Bitmap, minSize: Float): Fitted? {
        val padding = padding(area)
        val width = area.width - 2 * padding
        val height = area.height - 2 * padding
        if (width <= 0 || height <= 0) return null
        fun fits(size: Float, hyphenate: Boolean): Boolean {
            val candidate = layout(text, paint, size, width, hyphenate)
            val ends = (0 until candidate.lineCount).map(candidate::getLineEnd)
            return candidate.height <= height &&
                !TypesetMath.breaksInsideWord(text, ends, if (hyphenate) MIN_HYPHEN_FRAGMENT else Int.MAX_VALUE)
        }
        val maxSize = max(minSize, minOf(height.toFloat(), bitmap.width / 14f))
        val strict = if (fits(minSize, false)) TypesetMath.fitTextSize(minSize, maxSize) { fits(it, false) } else null
        val hyphenated = if (fits(minSize, true)) TypesetMath.fitTextSize(minSize, maxSize) { fits(it, true) } else null
        return when {
            strict != null && (hyphenated == null || hyphenated < strict * 1.25f) -> Fitted(
                area,
                strict,
                padding,
                false,
            )
            hyphenated != null -> Fitted(area, hyphenated, padding, true)
            else -> null
        }
    }

    private fun layout(text: String, paint: TextPaint, size: Float, width: Int, hyphenate: Boolean): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.95f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(
                if (hyphenate) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE,
            )
            .build()
    }

    /** Pixels along the inside edge of [area]: mostly bubble paper, since text sits in the middle. */
    private fun borderPixels(bitmap: Bitmap, area: PixelRect): IntArray {
        val step = max(1, (area.width + area.height) / 120)
        val pixels = ArrayList<Int>()
        for (x in area.left until area.right step step) {
            pixels += bitmap.getPixel(x, area.top)
            pixels += bitmap.getPixel(x, area.bottom - 1)
        }
        for (y in area.top until area.bottom step step) {
            pixels += bitmap.getPixel(area.left, y)
            pixels += bitmap.getPixel(area.right - 1, y)
        }
        return pixels.toIntArray()
    }

    companion object {
        /** Comic lettering: the system's "casual" family (Coming Soon) where present, else the plain bold font. */
        private val comicTypeface: Typeface by lazy {
            val casual = Typeface.create("casual", Typeface.BOLD)
            val fallback = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            if (casual == Typeface.DEFAULT_BOLD || casual == Typeface.create(Typeface.DEFAULT, Typeface.BOLD)) {
                fallback
            } else {
                casual
            }
        }

        /** Letters that must stay on each side of a hyphenated line break. */
        private const val MIN_HYPHEN_FRAGMENT = 3

        /** Bubble search reads at most this many pixels around a text box (about 24 MB as ints). */
        private const val MAX_BUBBLE_SCAN_PIXELS = 6_000_000L

        /** Above this many pixels a page is drawn at half resolution (an 800x40000 strip would need 128 MB). */
        const val MAX_RENDER_PIXELS = 24_000_000L
    }
}

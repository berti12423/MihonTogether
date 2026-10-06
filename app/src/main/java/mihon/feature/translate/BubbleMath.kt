package mihon.feature.translate

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A grayscale image: luminance 0..255 per pixel, row by row. */
class GrayImage(val width: Int, val height: Int, val lum: IntArray) {
    init {
        require(lum.size == width * height)
    }

    operator fun get(x: Int, y: Int) = lum[y * width + x]
}

/**
 * A speech bubble found around a text box, in the coordinates of the [GrayImage] it was found in. [interior] is the
 * bubble's inside with the letters included (its holes filled), [inner] the largest rectangle inside it that contains
 * the text box's center, [bounds] the interior's bounding box.
 */
class Bubble(val width: Int, val height: Int, val interior: BooleanArray, val inner: PixelRect, val bounds: PixelRect) {
    fun contains(x: Int, y: Int) = x in 0 until width && y in 0 until height && interior[y * width + x]
}

/**
 * Platform-free bubble detection for [BitmapPageImages]: flood fill from the text over light pixels until the dark
 * outline, then the largest rectangle that fits inside for the translation, and a mask of only the original letters
 * to erase. Everything here runs in plain JVM tests.
 */
object BubbleMath {
    /** Grid cells per side when looking for a bubble: enough for outlines, cheap to fill. */
    const val GRID_SIZE = 256

    fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (299 * r + 587 * g + 114 * b) / 1000
    }

    /** Cell size so that a [width] x [height] region becomes at most [GRID_SIZE] cells per side. */
    fun cellSize(width: Int, height: Int): Int = max(1, ceil(max(width, height) / GRID_SIZE.toDouble()).toInt())

    /** Downsamples [lum] by [cell], keeping each cell's darkest pixel, so thin outlines and strokes survive. */
    fun downsampleMin(lum: IntArray, width: Int, height: Int, cell: Int): GrayImage {
        val gw = (width + cell - 1) / cell
        val gh = (height + cell - 1) / cell
        val out = IntArray(gw * gh) { 255 }
        for (y in 0 until height) {
            val row = (y / cell) * gw
            for (x in 0 until width) {
                val i = row + x / cell
                val value = lum[y * width + x]
                if (value < out[i]) out[i] = value
            }
        }
        return GrayImage(gw, gh, out)
    }

    /**
     * Fills from the light pixels inside [box] over pixels at least [lightThreshold] bright (4-connected), stopping
     * at the dark outline. Returns null when this isn't an enclosed bubble: no light seed, the fill reaches the edge
     * of [image] (callers pass a region around the box, so the edge also means "far away"), or it covers more than
     * [maxGrowth] times the box's area (text on open background, or a box crossing the outline).
     */
    fun findBubble(image: GrayImage, box: PixelRect, lightThreshold: Int, maxGrowth: Float = 5f): Bubble? {
        val w = image.width
        val h = image.height
        val clipped =
            PixelRect(
                box.left.coerceIn(0, w),
                box.top.coerceIn(0, h),
                box.right.coerceIn(0, w),
                box.bottom.coerceIn(0, h),
            )
        if (clipped.width <= 0 || clipped.height <= 0) return null
        val limit = (clipped.width.toLong() * clipped.height * maxGrowth).toLong()
        val mask = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        for (y in clipped.top until clipped.bottom) {
            for (x in clipped.left until clipped.right) {
                val i = y * w + x
                if (image.lum[i] >= lightThreshold) {
                    mask[i] = true
                    queue[tail++] = i
                }
            }
        }
        if (tail == 0) return null
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) return null
            if (tail > limit) return null
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
            for (n in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                if (!mask[n] && image.lum[n] >= lightThreshold) {
                    mask[n] = true
                    queue[tail++] = n
                }
            }
        }
        val bounds = PixelRect(minX, minY, maxX + 1, maxY + 1)
        val interior = fillHoles(mask, w, h, bounds)
        val cx = ((clipped.left + clipped.right) / 2).coerceIn(bounds.left, bounds.right - 1)
        val cy = ((clipped.top + clipped.bottom) / 2).coerceIn(bounds.top, bounds.bottom - 1)
        val inner = innerRect(interior, w, h, cx, cy) ?: return null
        return Bubble(w, h, interior, inner, bounds)
    }

    /**
     * [mask] plus everything it encloses inside [bounds] (letters, and the holes inside letters): whatever can't be
     * reached from outside [bounds] without crossing the mask. The outline itself stays outside.
     */
    fun fillHoles(mask: BooleanArray, width: Int, height: Int, bounds: PixelRect): BooleanArray {
        // Work in bounds grown by one pixel, so "outside" is connected all around.
        val left = bounds.left - 1
        val top = bounds.top - 1
        val bw = bounds.width + 2
        val bh = bounds.height + 2
        fun masked(x: Int, y: Int): Boolean {
            val gx = left + x
            val gy = top + y
            return gx in 0 until width && gy in 0 until height && mask[gy * width + gx]
        }
        val outside = BooleanArray(bw * bh)
        val queue = IntArray(bw * bh)
        var tail = 0
        for (x in 0 until bw) {
            for (y in intArrayOf(0, bh - 1)) {
                val i = y * bw + x
                if (!outside[i]) {
                    outside[i] = true
                    queue[tail++] = i
                }
            }
        }
        for (y in 0 until bh) {
            for (x in intArrayOf(0, bw - 1)) {
                val i = y * bw + x
                if (!outside[i]) {
                    outside[i] = true
                    queue[tail++] = i
                }
            }
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % bw
            val y = i / bw
            if (x > 0) tail = visit(i - 1, x - 1, y, outside, queue, tail, ::masked)
            if (x < bw - 1) tail = visit(i + 1, x + 1, y, outside, queue, tail, ::masked)
            if (y > 0) tail = visit(i - bw, x, y - 1, outside, queue, tail, ::masked)
            if (y < bh - 1) tail = visit(i + bw, x, y + 1, outside, queue, tail, ::masked)
        }
        val result = BooleanArray(width * height)
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                if (!outside[(y - top) * bw + (x - left)]) result[y * width + x] = true
            }
        }
        return result
    }

    private inline fun visit(
        i: Int,
        x: Int,
        y: Int,
        outside: BooleanArray,
        queue: IntArray,
        tail: Int,
        masked: (Int, Int) -> Boolean,
    ): Int {
        if (outside[i] || masked(x, y)) return tail
        outside[i] = true
        queue[tail] = i
        return tail + 1
    }

    /**
     * The largest rectangle of [mask] containing ([cx], [cy]), or null if that point isn't in the mask. Each row's
     * run through column [cx] bounds the rectangle, so it's exact for any shape and O(height²).
     */
    fun innerRect(mask: BooleanArray, width: Int, height: Int, cx: Int, cy: Int): PixelRect? {
        if (cx !in 0 until width || cy !in 0 until height || !mask[cy * width + cx]) return null
        val runLeft = IntArray(height)
        val runRight = IntArray(height) // exclusive
        val valid = BooleanArray(height)
        for (y in 0 until height) {
            if (!mask[y * width + cx]) continue
            var l = cx
            while (l > 0 && mask[y * width + l - 1]) l--
            var r = cx + 1
            while (r < width && mask[y * width + r]) r++
            runLeft[y] = l
            runRight[y] = r
            valid[y] = true
        }
        var firstTop = cy
        while (firstTop > 0 && valid[firstTop - 1]) firstTop--
        var lastBottom = cy
        while (lastBottom < height - 1 && valid[lastBottom + 1]) lastBottom++
        // Narrowest run from each top row down to cy, and from cy down to each bottom row.
        val upLeft = IntArray(height)
        val upRight = IntArray(height)
        var l = runLeft[cy]
        var r = runRight[cy]
        for (t in cy downTo firstTop) {
            l = max(l, runLeft[t])
            r = min(r, runRight[t])
            upLeft[t] = l
            upRight[t] = r
        }
        val downLeft = IntArray(height)
        val downRight = IntArray(height)
        l = runLeft[cy]
        r = runRight[cy]
        for (b in cy..lastBottom) {
            l = max(l, runLeft[b])
            r = min(r, runRight[b])
            downLeft[b] = l
            downRight[b] = r
        }
        var best: PixelRect? = null
        var bestArea = -1L
        for (t in firstTop..cy) {
            for (b in cy..lastBottom) {
                val left = max(upLeft[t], downLeft[b])
                val right = min(upRight[t], downRight[b])
                val area = (right - left).toLong() * (b - t + 1)
                if (area > bestArea) {
                    bestArea = area
                    best = PixelRect(left, t, right, b + 1)
                }
            }
        }
        return best
    }

    /**
     * Which pixels of a [width] x [height] region to paint with the paper colour: those inside [area] that are
     * clearly darker than [paper] (by [delta]), grown by [radius] pixels to catch anti-aliased edges, and only where
     * [allowed] (inside the bubble, so its outline survives).
     */
    fun eraseMask(
        lum: IntArray,
        width: Int,
        height: Int,
        area: PixelRect,
        paper: Int,
        radius: Int,
        delta: Int = 40,
        allowed: (Int, Int) -> Boolean,
    ): BooleanArray {
        val threshold = paper - delta
        val dark = BooleanArray(width * height)
        for (y in max(0, area.top) until min(height, area.bottom)) {
            for (x in max(0, area.left) until min(width, area.right)) {
                if (lum[y * width + x] < threshold) dark[y * width + x] = true
            }
        }
        val result = BooleanArray(width * height)
        for (y in max(0, area.top) until min(height, area.bottom)) {
            for (x in max(0, area.left) until min(width, area.right)) {
                if (!allowed(x, y)) continue
                var hit = false
                loop@ for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        if (nx in 0 until width && dark[ny * width + nx]) {
                            hit = true
                            break@loop
                        }
                    }
                }
                if (hit) result[y * width + x] = true
            }
        }
        return result
    }
}

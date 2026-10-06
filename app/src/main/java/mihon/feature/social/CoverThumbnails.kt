package mihon.feature.social

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Small cover copies for the web: many sources refuse to show their covers on other websites, but the phone already
 * has every library cover cached. A thumbnail is 240 px wide JPEG, kept under [MAX_BYTES] (the Storage rules allow
 * 100 KB), uploaded once per title and cover (see [CoverThumbnails.marker]).
 */
object CoverThumbnails {
    const val WIDTH = 240
    const val MAX_BYTES = 90 * 1024

    /** What was uploaded for a title: its key plus a hash of the cover link, so a changed cover uploads again. */
    fun marker(contentKey: String, coverUrl: String?): String = "$contentKey:${(coverUrl ?: "").hashCode()}"

    /** The JPEG to upload, or null when the file isn't a readable image. */
    fun encode(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val height = (decoded.height * WIDTH.toFloat() / decoded.width).roundToInt().coerceIn(1, WIDTH * 3)
        val scaled = if (decoded.width > WIDTH) {
            Bitmap.createScaledBitmap(decoded, WIDTH, height, true).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }
        try {
            for (quality in listOf(80, 65, 50, 35)) {
                val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                    .toByteArray()
                if (bytes.size <= MAX_BYTES) return bytes
            }
            return null
        } finally {
            scaled.recycle()
        }
    }
}

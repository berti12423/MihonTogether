package mihon.feature.translate

import kotlinx.serialization.Serializable

/** A rectangle in page space, as fractions (0..1) of the page width and height. */
@Serializable
data class NormBox(val top: Float, val left: Float, val bottom: Float, val right: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)

    fun intersection(other: NormBox): Float {
        val w = minOf(right, other.right) - maxOf(left, other.left)
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    companion object {
        /** Gemini's `box_2d`: [ymin, xmin, ymax, xmax] normalized to 0..1000. */
        fun fromBox2d(box: List<Int>): NormBox? {
            if (box.size != 4) return null
            val (y0, x0, y1, x1) = box.map { it.coerceIn(0, 1000) / 1000f }
            if (y1 <= y0 || x1 <= x0) return null
            return NormBox(y0, x0, y1, x1)
        }
    }
}

/** One translated text region (a bubble, caption, sign or sound effect). */
@Serializable
data class TranslatedBlock(
    val box: NormBox,
    val kind: String,
    val vertical: Boolean,
    val source: String,
    val translation: String,
    val lines: Int = 1,
    /** The reader changed [translation] by hand ("Edit page translation"). */
    val edited: Boolean = false,
    /** The reader chose not to draw this block (the original stays visible). */
    val hidden: Boolean = false,
) {
    val isSfx get() = kind == KIND_SFX

    companion object {
        const val KIND_SFX = "sfx"
    }
}

/** Everything an engine found on one page, in page space and reading order. */
@Serializable
data class PageTranslation(
    val blocks: List<TranslatedBlock>,
    val sourceLanguage: String? = null,
    val engine: String,
)

/** Why a translation failed, so the reader can say something useful and stop wasting requests. */
sealed class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No key, or the key was rejected. */
    class InvalidKey(message: String) : TranslationException(message)

    /** Daily or per-minute free quota used up (HTTP 429). Further requests will fail too. */
    class QuotaExceeded(message: String) : TranslationException(message)

    /** The model is overloaded (HTTP 503) or another temporary server problem. */
    class Unavailable(message: String) : TranslationException(message)

    /** The on-device language pack is missing and may only download on Wi-Fi (the reader is on mobile data). */
    class NeedsWifi(message: String) : TranslationException(message)

    /** Network failure, or a reply we couldn't read. */
    class Failed(message: String, cause: Throwable? = null) : TranslationException(message, cause)
}

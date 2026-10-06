package mihon.feature.translate

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One line of text an OCR engine found, in pixels of the image it was given. */
data class OcrLine(val left: Float, val top: Float, val right: Float, val bottom: Float, val text: String) {
    val width get() = right - left
    val height get() = bottom - top

    /** Japanese and Chinese columns: much taller than wide. */
    val vertical get() = text.length > 1 && height > width * 1.5f
}

/** Everything OCR found on one image. */
data class OcrResult(val width: Int, val height: Int, val lines: List<OcrLine>)

/** Finds text on an image (Android: [MlKitTextFinder]). */
interface TextFinder {
    /** [language] is "ko", "ja" or "zh": each needs its own recognizer. */
    suspend fun find(image: ByteArray, language: String): OcrResult
}

/** Translates plain text (on-device ML Kit, or a cloud service with the reader's own key). */
interface TextTranslator {
    /** Identifies the service in the cache key. */
    val id: String

    /** One translation per text, same order. [context] holds earlier translated lines, oldest first. */
    suspend fun translate(texts: List<String>, source: String, target: String, context: List<String>): List<String>

    /** Releases on-device models; the reader calls this when it closes. */
    fun close() {}
}

/** A bubble or caption: lines that belong together, in reading order. */
data class TextGroup(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val lines: List<OcrLine>,
    val vertical: Boolean,
    val text: String,
)

/**
 * The free path: OCR finds the text, then a text translator translates it. Unlike [GeminiTranslationEngine] it can't
 * tell bubbles from sound effects or read context from the art, but it has no daily limit (on-device) and works
 * offline once the language packs are downloaded.
 */
class OcrTranslationEngine(
    private val finder: TextFinder,
    private val translator: TextTranslator,
) : TranslationEngine {
    override val id = "ocr:v$OCR_VERSION:${translator.id}"

    override val imageFormat = ImageFormat.OCR

    /** The language found on earlier pages when the reader chose "detect", so later pages run one recognizer. */
    @Volatile
    private var detected: String? = null

    /** Slices of the first page arrive in parallel: only one of them runs detection, the others wait for it. */
    private val detection = Mutex()

    override suspend fun translate(image: ByteArray, mimeType: String, request: TranslationRequest): EngineResult {
        val (language, found) = request.sourceLanguage?.let { it to finder.find(image, it) }
            ?: detected?.let { it to finder.find(image, it) }
            ?: detection.withLock { detected?.let { it to finder.find(image, it) } ?: detect(image) }
        val groups = TextGrouping.group(found.lines, language).filter { Scripts.count(it.text, language) > 0 }
        if (groups.isEmpty() || found.width <= 0 || found.height <= 0) return EngineResult(emptyList(), language)
        val translations = translator.translate(
            groups.map {
                it.text
            },
            language,
            request.targetLanguage,
            request.context,
        )
        val blocks = groups.zip(translations).map { (group, translation) ->
            TranslatedBlock(
                box = NormBox(
                    top = (group.top / found.height).coerceIn(0f, 1f),
                    left = (group.left / found.width).coerceIn(0f, 1f),
                    bottom = (group.bottom / found.height).coerceIn(0f, 1f),
                    right = (group.right / found.width).coerceIn(0f, 1f),
                ),
                kind = "dialogue",
                vertical = group.vertical,
                source = group.text,
                translation = translation.trim(),
                lines = group.lines.size.coerceIn(1, 30),
            )
        }
        return EngineResult(blocks, language)
    }

    /** Korean and Japanese recognizers first; Japanese-script text without kana is Chinese. */
    private suspend fun detect(image: ByteArray): Pair<String, OcrResult> {
        val korean = finder.find(image, "ko")
        val japanese = finder.find(image, "ja")
        val hangul = korean.lines.sumOf { Scripts.count(it.text, "ko") }
        val cjk = japanese.lines.sumOf { Scripts.count(it.text, "ja") }
        val kana = japanese.lines.sumOf { Scripts.kana(it.text) }
        val result = when {
            hangul == 0 && cjk == 0 -> return "ko" to korean // nothing readable: don't remember a guess
            hangul >= cjk -> "ko" to korean
            kana > 0 -> "ja" to japanese
            else -> "zh" to finder.find(image, "zh")
        }
        detected = result.first
        return result
    }

    companion object {
        /** Bump when grouping or filtering changes, so cached pages are re-read. */
        const val OCR_VERSION = 3
    }
}

/** Which characters belong to a language's script. */
object Scripts {
    fun count(text: String, language: String): Int = text.count { char ->
        when (language) {
            "ko" -> char in '가'..'힣' || char in 'ᄀ'..'ᇿ' || char in '㄰'..'㆏'
            "ja" -> isKana(char) || isHan(char)
            "zh" -> isHan(char)
            else -> char.isLetter()
        }
    }

    fun kana(text: String) = text.count(::isKana)

    private fun isKana(char: Char) = char in '぀'..'ヿ' || char in 'ｦ'..'ﾟ'
    private fun isHan(char: Char) = char in '一'..'鿿' || char in '㐀'..'䶿'
}

/**
 * Joins OCR lines into bubbles. Horizontal lines join when stacked closely with overlapping columns and similar
 * height; vertical columns join when side by side with overlapping rows. Groups come out top to bottom (right to
 * left for vertical text on the same band).
 */
object TextGrouping {
    fun group(lines: List<OcrLine>, language: String): List<TextGroup> {
        val usable = lines.filter { it.text.isNotBlank() && it.width > 0 && it.height > 0 }
        val parent = IntArray(usable.size) { it }
        fun root(i: Int): Int {
            var x = i
            while (parent[x] != x) x = parent[x].also { parent[x] = parent[parent[x]] }
            return x
        }
        for (i in usable.indices) {
            for (j in i + 1 until usable.size) {
                if (belongTogether(usable[i], usable[j])) parent[root(i)] = root(j)
            }
        }
        val joiner = if (language == "ko") " " else ""
        return usable.indices.groupBy(::root).values.map { members ->
            val groupLines = members.map { usable[it] }
            val vertical = groupLines.count { it.vertical } * 2 > groupLines.size
            val ordered = if (vertical) groupLines.sortedByDescending { it.right } else groupLines.sortedBy { it.top }
            TextGroup(
                left = groupLines.minOf { it.left },
                top = groupLines.minOf { it.top },
                right = groupLines.maxOf { it.right },
                bottom = groupLines.maxOf { it.bottom },
                lines = ordered,
                vertical = vertical,
                text = ordered.joinToString(joiner) { it.text.trim() },
            )
        }.sortedWith(readingOrder(usable))
    }

    /**
     * Rows of the page first (bands about three text lines high), then within a band: vertical text right to left,
     * horizontal text left to right. A key-based order, so it's always consistent for sorting.
     */
    private fun readingOrder(lines: List<OcrLine>): Comparator<TextGroup> {
        val sizes = lines.map { minOf(it.width, it.height) }.sorted()
        val band = ((sizes.getOrNull(sizes.size / 2) ?: 1f) * 3f).coerceAtLeast(1f)
        return compareBy<TextGroup> { (it.top / band).toInt() }
            .thenBy { if (it.vertical) -it.right else it.left }
            .thenBy { it.top }
    }

    private fun belongTogether(a: OcrLine, b: OcrLine): Boolean {
        if (a.vertical != b.vertical) return false
        return if (!a.vertical) {
            val lineHeight = minOf(a.height, b.height)
            val gap = maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)
            val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
            maxOf(a.height, b.height) < lineHeight * 1.8f && gap < lineHeight * 0.8f && overlap > -lineHeight * 0.3f
        } else {
            val columnWidth = minOf(a.width, b.width)
            val gap = maxOf(a.left, b.left) - minOf(a.right, b.right)
            val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            maxOf(a.width, b.width) < columnWidth * 1.8f && gap < columnWidth * 0.8f && overlap > 0f
        }
    }
}

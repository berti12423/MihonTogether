package mihon.feature.translate

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** Platform image work, so [PageTranslator]'s logic runs in plain JVM tests (Android: [BitmapPageImages]). */
interface PageImages {
    /** Width and height of a still image, or null for animated or unreadable images (not translated). */
    fun size(image: ByteArray): Pair<Int, Int>?

    /** The rows of [slice], downscaled to fit [format]'s cap, as JPEG of its quality. */
    fun encodeSlice(image: ByteArray, slice: Slice, format: ImageFormat = ImageFormat.UPLOAD): ByteArray

    /** Draws [blocks] over a copy of the page and writes it to [out] as JPEG. */
    fun render(image: ByteArray, blocks: List<TranslatedBlock>, out: File)
}

/**
 * One page: cache lookup, then slicing, the engine (two slices at a time), seam merge, cache write and rendering.
 * Returns the rendered page, or null when the page isn't a still image.
 */
class PageTranslator(
    private val cache: TranslationCache,
    private val images: PageImages,
    private val parallelSlices: Int = 2,
) {
    suspend fun translate(
        image: ByteArray,
        engine: TranslationEngine,
        request: TranslationRequest,
        includeSfx: Boolean,
        glossary: Glossary = Glossary.EMPTY,
    ): Result? {
        val (width, height) = images.size(image) ?: return null
        if (engine is PageRenderingEngine) return renderWholePage(image, engine, request.targetLanguage)
        val key = cache.key(image, engine.id, request)
        val translation = cache.read(key) ?: translateSlices(image, width, height, engine, request).also {
            cache.write(key, it)
        }
        return Result(render(image, key, translation, includeSfx, glossary), translation, key)
    }

    /**
     * The page as the engine drew it, cached by image, engine and target language, so a page is never sent twice.
     * The translation has no blocks: the engine's image is the result.
     */
    private suspend fun renderWholePage(image: ByteArray, engine: PageRenderingEngine, target: String): Result {
        val key = cache.pageKey(image, engine.id, target)
        val rendered = cache.renderedFile(key, "page", extension = "png")
        if (rendered.isFile) {
            cache.touch(rendered)
        } else {
            val png = engine.renderPage(image, target)
            cache.atomicWrite(rendered) { it.writeBytes(png) }
            cache.afterWrite()
        }
        return Result(rendered, PageTranslation(blocks = emptyList(), engine = engine.id), key)
    }

    /** Stores the reader's edits of a page's translation (under its [cacheKey]) and draws the page again. */
    fun saveEdits(
        image: ByteArray,
        cacheKey: String,
        translation: PageTranslation,
        includeSfx: Boolean,
        glossary: Glossary,
    ): Result {
        cache.write(cacheKey, translation)
        return Result(render(image, cacheKey, translation, includeSfx, glossary), translation, cacheKey)
    }

    /**
     * The drawn page for exactly these blocks: its file name carries a hash of what is drawn, so edits and glossary
     * changes produce a new file (and the viewer notices), while unchanged pages reuse theirs.
     */
    private fun render(
        image: ByteArray,
        key: String,
        translation: PageTranslation,
        includeSfx: Boolean,
        glossary: Glossary,
    ): File {
        val shown = shownBlocks(translation, includeSfx, glossary)
        val drawn = shown.joinToString("\n") { "${it.box}|${it.translation}" }.hashCode().toUInt().toString(16)
        val rendered = cache.renderedFile(key, "r$RENDER_VERSION-${if (includeSfx) "sfx" else "nosfx"}-$drawn")
        if (rendered.isFile) {
            cache.touch(rendered) // still in use: keep it when the cache is trimmed
        } else {
            cache.atomicWrite(rendered) { images.render(image, shown, it) }
            cache.afterWrite()
        }
        return rendered
    }

    private suspend fun translateSlices(
        image: ByteArray,
        width: Int,
        height: Int,
        engine: TranslationEngine,
        request: TranslationRequest,
    ): PageTranslation = coroutineScope {
        val permits = Semaphore(parallelSlices)
        val perSlice = PageSlicer.slices(width, height).map { slice ->
            async {
                permits.withPermit {
                    val result = engine.translate(
                        images.encodeSlice(image, slice, engine.imageFormat),
                        "image/jpeg",
                        request,
                    )
                    result.copy(blocks = result.blocks.map { it.copy(box = PageSlicer.toPage(it.box, slice, height)) })
                }
            }
        }.awaitAll()
        PageTranslation(
            blocks = PageSlicer.merge(perSlice.flatMap { it.blocks }),
            sourceLanguage = perSlice.firstNotNullOfOrNull { it.sourceLanguage },
            engine = engine.id,
        )
    }

    data class Result(val rendered: File, val translation: PageTranslation, val cacheKey: String)

    companion object {
        /** What gets drawn: no hidden blocks, SFX only when wanted, the title's glossary applied, nothing blank. */
        fun shownBlocks(translation: PageTranslation, includeSfx: Boolean, glossary: Glossary): List<TranslatedBlock> =
            translation.blocks
                .filter { (includeSfx || !it.isSfx) && !it.hidden }
                .map { it.copy(translation = glossary.apply(it.translation)) }
                .filter { it.translation.isNotBlank() }

        /** Bump when rendering changes, so cached pages are redrawn (translations are kept). */
        const val RENDER_VERSION = 5
    }
}

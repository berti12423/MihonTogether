package mihon.feature.translate

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PageTranslatorTest {
    @TempDir
    lateinit var dir: File

    /** Fake image: the bytes are "width x height"; slices are encoded as "top-bottom". */
    private class FakeImages : PageImages {
        val rendered = mutableListOf<List<TranslatedBlock>>()

        override fun size(image: ByteArray): Pair<Int, Int>? {
            val text = image.decodeToString()
            if (text == "animated") return null
            return text.split("x").let { (w, h) -> w.toInt() to h.toInt() }
        }

        val formats = mutableListOf<ImageFormat>()

        override fun encodeSlice(image: ByteArray, slice: Slice, format: ImageFormat): ByteArray {
            synchronized(formats) { formats += format }
            return "${slice.top}-${slice.bottom}".toByteArray()
        }

        override fun render(image: ByteArray, blocks: List<TranslatedBlock>, out: File) {
            rendered += blocks
            out.writeText("rendered")
        }
    }

    /** Puts one dialogue block in the middle of every slice, plus one SFX at the top of the first. */
    private class FakeEngine : TranslationEngine {
        override val id = "fake:v1"
        val calls = mutableListOf<String>()

        override suspend fun translate(image: ByteArray, mimeType: String, request: TranslationRequest): EngineResult {
            val slice = image.decodeToString().also { synchronized(calls) { calls += it } }
            val blocks = buildList {
                add(TranslatedBlock(NormBox(0.45f, 0.1f, 0.55f, 0.9f), "dialogue", false, "src $slice", "tr $slice"))
                if (slice.startsWith(
                        "0-",
                    )
                ) {
                    add(TranslatedBlock(NormBox(0f, 0f, 0.05f, 0.2f), "sfx", false, "쿵", "Thud"))
                }
            }
            return EngineResult(blocks, "ko")
        }
    }

    private val request = TranslationRequest("en", null)

    @Test
    fun `each engine gets slices in its own format, ocr at full resolution`() = runTest {
        val images = FakeImages()
        val translator = PageTranslator(TranslationCache(dir), images)
        translator.translate("800x1200".toByteArray(), FakeEngine(), request, includeSfx = false)
        val ocr = object : TranslationEngine by FakeEngine() {
            override val imageFormat = ImageFormat.OCR
        }
        translator.translate("800x1201".toByteArray(), ocr, request, includeSfx = false)
        assertEquals(listOf(ImageFormat.UPLOAD, ImageFormat.OCR), images.formats)
        assertEquals(1f, PageSlicer.uploadScale(3000, 4000, ImageFormat.OCR.maxSide)) // a 4000 px page fits as it is
        assertEquals(0.4f, PageSlicer.uploadScale(1000, 4000), 0.001f) // the upload cap still shrinks for Gemini
        assertEquals(1f, PageSlicer.uploadScale(1000, 40000, 0)) // 0: no limit
    }

    @Test
    fun `tall pages are translated per slice, mapped back, cached and rendered without sfx`() = runTest {
        val images = FakeImages()
        val engine = FakeEngine()
        val translator = PageTranslator(TranslationCache(dir), images)

        val result = translator.translate("500x3300".toByteArray(), engine, request, includeSfx = false)!!
        assertEquals(5, engine.calls.size)
        assertEquals(6, result.translation.blocks.size) // 5 dialogue + 1 sfx kept in the translation
        val dialogue = result.translation.blocks.filterNot { it.isSfx }
        assertTrue(dialogue.zipWithNext().all { (a, b) -> a.box.top < b.box.top }) // slice order kept
        assertEquals(listOf(false), images.rendered.single().map { it.isSfx }.distinct()) // sfx not drawn
        assertTrue(result.rendered.isFile)

        // Same page again: no engine call, no re-render.
        translator.translate("500x3300".toByteArray(), engine, request, includeSfx = false)
        assertEquals(5, engine.calls.size)
        assertEquals(1, images.rendered.size)

        // Showing SFX is another rendering of the same cached translation.
        val withSfx = translator.translate("500x3300".toByteArray(), engine, request, includeSfx = true)!!
        assertEquals(5, engine.calls.size)
        assertNotEquals(result.rendered, withSfx.rendered)
        assertTrue(images.rendered.last().any { it.isSfx })
    }

    @Test
    fun `animated pages are skipped and cache keys depend on engine and language`() = runTest {
        val translator = PageTranslator(TranslationCache(dir), FakeImages())
        assertNull(translator.translate("animated".toByteArray(), FakeEngine(), request, includeSfx = false))

        val cache = TranslationCache(dir)
        val image = "800x1200".toByteArray()
        val base = cache.key(image, "a", request)
        assertEquals(base, cache.key(image, "a", request))
        assertNotEquals(base, cache.key(image, "b", request))
        assertNotEquals(base, cache.key(image, "a", request.copy(targetLanguage = "de")))
        assertNotEquals(base, cache.key("800x1201".toByteArray(), "a", request))
    }

    @Test
    fun `trim drops the oldest files past the limit`() {
        val cache = TranslationCache(dir, maxBytes = 10)
        listOf("old", "mid", "new").forEachIndexed { i, name ->
            File(dir, name).apply { writeText("12345") }.setLastModified(1_000L * (i + 1))
        }
        cache.trim()
        assertEquals(setOf("mid", "new"), dir.list()!!.toSet())
    }

    @Test
    fun `reusing a rendered page marks it as recently used, so trimming keeps it`() = runTest {
        val translator = PageTranslator(TranslationCache(dir), FakeImages())
        val first = translator.translate("800x1200".toByteArray(), FakeEngine(), request, includeSfx = false)!!
        first.rendered.setLastModified(1_000L)
        translator.translate("800x1200".toByteArray(), FakeEngine(), request, includeSfx = false)
        assertTrue(first.rendered.lastModified() > 1_000L)
    }

    @Test
    fun `trimming runs on the first write and then every tenth, and skips files being written`() {
        val cache = TranslationCache(dir, maxBytes = 0, trimEvery = 10)
        fun file(name: String) = File(dir, name).apply { writeText("12345") }
        file("a")
        file("busy.jpg.tmp")
        cache.afterWrite() // 1st: trims
        assertEquals(setOf("busy.jpg.tmp"), dir.list()!!.toSet())
        file("b")
        repeat(9) { cache.afterWrite() } // 2nd..10th: no trim
        assertTrue(File(dir, "b").isFile)
        cache.afterWrite() // 11th: trims
        assertFalse(File(dir, "b").isFile)
    }

    @Test
    fun `hidden blocks are not drawn, the glossary is, and edits are saved without a new request`() = runTest {
        val images = FakeImages()
        val engine = FakeEngine()
        val translator = PageTranslator(TranslationCache(dir), images)
        val image = "800x1200".toByteArray()
        val first = translator.translate(image, engine, request, includeSfx = true)!!
        assertEquals(2, images.rendered.last().size)

        val glossary = Glossary(listOf(GlossaryEntry("tr", "TL")))
        val withGlossary = translator.translate(image, engine, request, includeSfx = true, glossary = glossary)!!
        assertNotEquals(first.rendered, withGlossary.rendered) // drawn again under a new name
        assertEquals("TL 0-1200", images.rendered.last().first().translation)
        assertEquals("tr 0-1200", withGlossary.translation.blocks.first().translation) // cache keeps the original

        val edited = first.translation.blocks.mapIndexed { i, block ->
            if (i == 0) block.copy(translation = "Hello", edited = true) else block.copy(hidden = true)
        }
        val saved = translator.saveEdits(
            image,
            first.cacheKey,
            first.translation.copy(blocks = edited),
            includeSfx = true,
            glossary = Glossary.EMPTY,
        )
        assertEquals(listOf("Hello"), images.rendered.last().map { it.translation })
        assertNotEquals(first.rendered, saved.rendered)

        val reread = translator.translate(image, engine, request, includeSfx = true)!!
        assertEquals(1, engine.calls.size)
        assertEquals(saved.rendered, reread.rendered) // the edits are what's cached now
        assertTrue(reread.translation.blocks.first().edited && reread.translation.blocks.last().hidden)
    }
}

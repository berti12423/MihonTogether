package mihon.feature.translate

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTranslationTest {
    @TempDir
    lateinit var dir: File

    private class Images : PageImages {
        override fun size(image: ByteArray) = 800 to 1200
        override fun encodeSlice(image: ByteArray, slice: Slice, format: ImageFormat) = image
        override fun render(image: ByteArray, blocks: List<TranslatedBlock>, out: File) = out.writeText("rendered")
    }

    private class Engine(private val failWith: TranslationException? = null) : TranslationEngine {
        override val id = "fake"
        val requests = mutableListOf<Pair<String, TranslationRequest>>()

        override suspend fun translate(image: ByteArray, mimeType: String, request: TranslationRequest): EngineResult {
            val page = image.decodeToString()
            requests += page to request
            failWith?.let { throw it }
            return EngineResult(
                listOf(TranslatedBlock(NormBox(0.1f, 0.1f, 0.2f, 0.9f), "dialogue", false, "src", "line of $page")),
                "ko",
            )
        }
    }

    private fun chapter(pages: Int): List<ReaderPage> {
        val chapter = ReaderChapter(Chapter.create().copy(id = 7, url = "/c/7"))
        val list = (0 until pages).map { index ->
            ReaderPage(index, stream = { "page $index".byteInputStream() }).also {
                it.chapter = chapter
                it.status = Page.State.Ready
            }
        }
        chapter.state = ReaderChapter.State.Loaded(list)
        return list
    }

    private fun settings(key: String = "key") =
        TranslationSettings(key, "m", null, "en", translateSfx = false, engine = TranslationPreferences.ENGINE_GEMINI)

    @Test
    fun `without a key it asks for one instead of switching on`() = runTest {
        val translation =
            ReaderTranslation(backgroundScope, {
                settings(key = "")
            }, PageTranslator(TranslationCache(dir), Images()), { Engine() })
        translation.setEnabled(true)
        assertFalse(translation.state.value.enabled)
        assertEquals(ReaderTranslation.Problem.NO_KEY, translation.state.value.problem)
    }

    @Test
    fun `the selected page and the next two are translated in order, with earlier lines as context`() = runTest {
        val engine = Engine()
        val translation = ReaderTranslation(
            backgroundScope,
            { settings() },
            PageTranslator(TranslationCache(dir), Images()),
            { engine },
            UnconfinedTestDispatcher(testScheduler),
        )
        val pages = chapter(5)
        translation.setEnabled(true, pages[1])

        assertEquals(listOf("page 1", "page 2", "page 3"), engine.requests.map { it.first })
        assertEquals(listOf("line of page 1"), engine.requests[1].second.context)
        assertNotNull(translation.fileFor(pages[1]))
        assertNull(translation.fileFor(pages[0]))

        translation.setEnabled(false)
        assertNull(translation.fileFor(pages[1])) // originals again
        translation.setEnabled(true, pages[1])
        assertEquals(3, engine.requests.size) // already translated pages aren't requested again
        assertNotNull(translation.fileFor(pages[2]))
    }

    @Test
    fun `a used-up quota stops the queue and says so`() = runTest {
        val engine = Engine(TranslationException.QuotaExceeded("limit: 20 requests per day"))
        val translation = ReaderTranslation(
            backgroundScope,
            { settings() },
            PageTranslator(TranslationCache(dir), Images()),
            { engine },
            UnconfinedTestDispatcher(testScheduler),
        )
        translation.setEnabled(true, chapter(5)[0])

        assertEquals(1, engine.requests.size)
        assertTrue(translation.state.value.enabled)
        assertEquals(ReaderTranslation.Problem.QUOTA, translation.state.value.problem)
        assertFalse(translation.state.value.working)
    }

    @Test
    fun `after a used-up quota or a rejected key, page turns call the engine no more until switched on again`() =
        runTest {
            for (failure in listOf(
                TranslationException.QuotaExceeded("quota"),
                TranslationException.InvalidKey("bad"),
            )) {
                val engine = Engine(failure)
                val translation = ReaderTranslation(
                    backgroundScope,
                    { settings() },
                    PageTranslator(TranslationCache(File(dir, failure.message!!)), Images()),
                    { engine },
                    UnconfinedTestDispatcher(testScheduler),
                )
                val pages = chapter(6)
                translation.setEnabled(true, pages[0])
                assertTrue(translation.state.value.halted)

                translation.onPageSelected(pages[1])
                translation.onPageSelected(pages[2])
                assertEquals(1, engine.requests.size) // no new requests while halted

                translation.setEnabled(true, pages[3]) // switching on again (e.g. after saving a new key) retries
                assertEquals(2, engine.requests.size)
                assertEquals("page 3", engine.requests.last().first)
            }
        }

    @Test
    fun `a temporary failure does not halt the queue`() = runTest {
        val engine = Engine(TranslationException.Unavailable("503"))
        val translation = ReaderTranslation(
            backgroundScope,
            { settings() },
            PageTranslator(TranslationCache(dir), Images()),
            { engine },
            UnconfinedTestDispatcher(testScheduler),
        )
        translation.setEnabled(true, chapter(5)[0])
        assertFalse(translation.state.value.halted)
        assertEquals(3, engine.requests.size)
    }

    @Test
    fun `a rendered page the cache trimmed falls back to the original and is drawn again from the cached text`() =
        runTest {
            val engine = Engine()
            val translation = ReaderTranslation(
                backgroundScope,
                { settings() },
                PageTranslator(TranslationCache(dir), Images()),
                { engine },
                UnconfinedTestDispatcher(testScheduler),
            )
            val pages = chapter(1)
            translation.setEnabled(true, pages[0])
            val rendered = translation.fileFor(pages[0])!!
            assertEquals(rendered, translation.fileFlow(pages[0]).first())

            rendered.delete() // trimmed during a long session
            assertNull(translation.fileFor(pages[0]))
            assertNull(translation.fileFlow(pages[0]).first())

            translation.onPageSelected(pages[0])
            assertTrue(translation.fileFor(pages[0])!!.isFile)
            assertEquals(1, engine.requests.size) // redrawn from the cached translation, no new request
        }

    @Test
    fun `the on-device engine goes on through the whole chapter and skips a page nobody loads`() = runTest {
        val engine = Engine()
        val translation = ReaderTranslation(
            backgroundScope,
            { settings().copy(engine = TranslationPreferences.ENGINE_ON_DEVICE) },
            PageTranslator(TranslationCache(dir), Images()),
            { engine },
            UnconfinedTestDispatcher(testScheduler),
        )
        val pages = chapter(7)
        pages[4].status = Page.State.Queue // never requested by the loader
        translation.setEnabled(true, pages[1])

        // Current page, the next two, the rest ahead, then the pages already passed; page 4 doesn't block the rest.
        assertEquals(
            listOf("page 1", "page 2", "page 3", "page 5", "page 6", "page 0"),
            engine.requests.map { it.first },
        )
        assertEquals(6, translation.state.value.done)
        assertEquals(7, translation.state.value.total)

        advanceTimeBy(6_000) // past the grace period: page 4 is dropped from the queue, not waited on for a minute
        runCurrent()
        pages[4].status = Page.State.Ready
        advanceTimeBy(1_000)
        assertEquals(6, engine.requests.size)

        translation.onPageSelected(pages[2]) // the next page turn queues it again
        assertEquals("page 4", engine.requests.last().first)
        assertEquals(7, translation.state.value.done)
    }

    @Test
    fun `cloud engines only prefetch, and a page that is still loading goes after the ready ones`() = runTest {
        val engine = Engine()
        val translation = ReaderTranslation(
            backgroundScope,
            { settings() },
            PageTranslator(TranslationCache(dir), Images()),
            { engine },
            UnconfinedTestDispatcher(testScheduler),
        )
        val pages = chapter(8)
        pages[2].status = Page.State.DownloadImage
        translation.setEnabled(true, pages[1])
        assertEquals(listOf("page 1", "page 3"), engine.requests.map { it.first })
        assertEquals(0, translation.state.value.total)

        advanceTimeBy(20_000) // still loading: waited for, well past the short grace for unloaded pages
        pages[2].status = Page.State.Ready
        advanceTimeBy(500)
        assertEquals(listOf("page 1", "page 3", "page 2"), engine.requests.map { it.first })
    }

    @Test
    fun `edits and glossary changes redraw the page without asking the engine again`() = runTest {
        val engine = Engine()
        var glossary = Glossary.EMPTY
        val translation = ReaderTranslation(
            backgroundScope,
            { settings() },
            PageTranslator(TranslationCache(dir), Images()),
            { engine },
            UnconfinedTestDispatcher(testScheduler),
            glossary = { glossary },
        )
        val pages = chapter(1)
        assertNull(translation.translationFor(pages[0]))
        translation.setEnabled(true, pages[0])
        val original = translation.translationFor(pages[0])!!
        val firstFile = translation.fileFor(pages[0])!!

        val edited = original.blocks.map { it.copy(translation = "Hi!", edited = true) }
        assertTrue(translation.saveEdits(pages[0], edited))
        val editedFile = translation.fileFor(pages[0])!!
        assertNotEquals(firstFile, editedFile)
        assertEquals("Hi!", translation.translationFor(pages[0])!!.blocks.single().translation)

        glossary = Glossary(listOf(GlossaryEntry("Hi", "Hey")))
        translation.redraw(pages[0])
        assertNotEquals(editedFile, translation.fileFor(pages[0]))
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `a missing language pack on mobile data says so at once and stops the queue`() = runTest {
        val engine = Engine(TranslationException.NeedsWifi("pack needs Wi-Fi"))
        val translation = ReaderTranslation(
            backgroundScope,
            { settings().copy(engine = TranslationPreferences.ENGINE_ON_DEVICE) },
            PageTranslator(TranslationCache(dir), Images()),
            { engine },
            UnconfinedTestDispatcher(testScheduler),
        )
        val pages = chapter(5)
        translation.setEnabled(true, pages[0])
        assertEquals(ReaderTranslation.Problem.NEEDS_WIFI, translation.state.value.problem)
        assertTrue(translation.state.value.halted)
        translation.onPageSelected(pages[1])
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `mobile data permission is part of the saved settings`() {
        val base = settings()
        assertFalse(base.allowMobileData) // Wi-Fi only by default
        assertTrue(base.copy(allowMobileData = true) != base)
    }
}

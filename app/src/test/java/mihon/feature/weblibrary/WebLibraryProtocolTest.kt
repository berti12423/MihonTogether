package mihon.feature.weblibrary

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebLibraryProtocolTest {

    private class FakeChapter(
        override val pageCount: Int,
        override val downloaded: Boolean,
        val pages: (Int) -> ByteArray = { byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), it.toByte()) },
    ) : WebOpenedChapter {
        var gate: CompletableDeferred<Unit>? = null
        var closed = false
        override suspend fun image(index: Int): ByteArray {
            gate?.await()
            return pages(index)
        }
        override fun close() {
            closed = true
        }
    }

    private class FakeBackend : WebLibraryBackend {
        var library = WebLibrary(emptyList(), emptyList())
        val details = mutableMapOf<Long, WebMangaDetails>()
        val chapters = mutableMapOf<Long, FakeChapter>()
        val sourceErrors = mutableSetOf<Long>()
        val targets = mutableMapOf<Long, WebProgressTarget>()
        val saved = mutableListOf<Triple<Long, Int, Boolean>>()
        val finished = mutableListOf<Pair<Long, Int?>>()
        var cover: ByteArray? = null
        var opened = 0

        override suspend fun librarySize() = library.manga.size
        override suspend fun library() = library
        override suspend fun cover(mangaId: Long, maxWidth: Int) = cover
        override suspend fun details(mangaId: Long) = details[mangaId]
        override suspend fun openChapter(chapterId: Long): WebOpenedChapter? {
            opened++
            if (chapterId in sourceErrors) throw WebLibraryException(WebLibraryException.SOURCE_ERROR, "HTTP 403")
            return chapters[chapterId]
        }
        override suspend fun progressTarget(chapterId: Long) = targets[chapterId]
        override suspend fun saveProgress(chapterId: Long, lastPageRead: Int, read: Boolean) {
            saved += Triple(chapterId, lastPageRead, read)
        }
        override suspend fun chapterFinished(chapterId: Long, pageCount: Int?) {
            finished += chapterId to pageCount
        }
    }

    private class FakeSink : WebLibrarySink {
        val texts = mutableListOf<JsonObject>()
        val frames = mutableListOf<ByteArray>()
        override suspend fun sendText(text: String) {
            texts += Json.parseToJsonElement(text).jsonObject
        }
        override suspend fun sendBinary(frame: ByteArray) {
            frames += frame
        }
        fun response(id: Long): JsonObject = texts.single { it["id"]!!.jsonPrimitive.long == id }
        fun result(id: Long): JsonObject = response(id)["result"]!!.jsonObject
        fun errorCode(id: Long): String = response(id)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    }

    private val backend = FakeBackend()
    private val sink = FakeSink()

    private fun TestScope.session() =
        WebLibrarySession(backend, WebLibraryHostInfo("Galaxy A35", 8300), sink, this)

    private fun request(id: Long, method: String, params: String = "{}") =
        """{"id": $id, "method": "$method", "params": $params}"""

    private fun TestScope.greeted(): WebLibrarySession = session().also {
        it.receive(request(1, "hello", """{"protocol": 1, "client": "web"}"""))
        runCurrent()
    }

    @Test
    fun `hello returns the phone`() = runTest {
        backend.library = WebLibrary(emptyList(), listOf(manga(1), manga(2)))
        greeted()
        val result = sink.result(1)
        assertEquals(1, result["protocol"]!!.jsonPrimitive.int)
        assertEquals("Galaxy A35", result["deviceName"]!!.jsonPrimitive.content)
        assertEquals(8300, result["appBuild"]!!.jsonPrimitive.int)
        assertEquals(2, result["librarySize"]!!.jsonPrimitive.int)
    }

    @Test
    fun `other protocol is refused and the session stays closed`() = runTest {
        val session = session()
        session.receive(request(1, "hello", """{"protocol": 2, "client": "web"}"""))
        session.receive(request(2, "library.list"))
        runCurrent()
        assertEquals("unsupported_protocol", sink.errorCode(1))
        assertEquals("bad_request", sink.errorCode(2))
    }

    @Test
    fun `requests before hello are bad requests`() = runTest {
        val session = session()
        session.receive(request(5, "manga.details", """{"mangaId": "1"}"""))
        runCurrent()
        assertEquals("bad_request", sink.errorCode(5))
    }

    @Test
    fun `malformed messages without an id are ignored, unknown methods are bad requests`() = runTest {
        val session = greeted()
        session.receive("not json")
        session.receive("""{"method": "library.list"}""")
        session.receive(request(2, "library.delete"))
        runCurrent()
        assertEquals(2, sink.texts.size)
        assertEquals("bad_request", sink.errorCode(2))
    }

    @Test
    fun `library list uses string ids and hides an empty default category`() = runTest {
        val categories = listOf(WebCategory(0, "Default", 0), WebCategory(3, "Reading", 1))
        backend.library = WebLibrary(
            categories,
            listOf(
                manga(123, categories = listOf(3)),
                manga(Long.MAX_VALUE, categories = listOf(3), lastRead = 1728450000000),
            ),
        )
        val session = greeted()
        session.receive(request(2, "library.list"))
        runCurrent()
        val result = sink.result(2)
        assertEquals(listOf("3"), result["categories"]!!.jsonArray.map { it.jsonObject["categoryId"]!!.string() })
        val manga = result["manga"]!!.jsonArray.map { it.jsonObject }
        assertEquals("123", manga[0]["mangaId"]!!.string())
        assertEquals("9223372036854775807", manga[1]["mangaId"]!!.string())
        assertEquals("2499283573021220255", manga[0]["sourceId"]!!.string())
        assertEquals(JsonArray(listOf(JsonPrimitive("3"))), manga[0]["categoryIds"])
        assertEquals("1728400000000", manga[0]["coverVersion"]!!.string())
        assertEquals(JsonNull, manga[0]["lastRead"])
        assertEquals(1728450000000, manga[1]["lastRead"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, manga[0]["author"])
    }

    @Test
    fun `library list keeps the default category for uncategorized titles, without listing it on the title`() =
        runTest {
            backend.library = WebLibrary(
                listOf(WebCategory(3, "Reading", 1), WebCategory(0, "Default", 0)),
                listOf(manga(1, categories = listOf(0)), manga(2, categories = listOf(3))),
            )
            val session = greeted()
            session.receive(request(2, "library.list"))
            runCurrent()
            val result = sink.result(2)
            assertEquals(
                listOf("0", "3"),
                result["categories"]!!.jsonArray.map { it.jsonObject["categoryId"]!!.string() },
            )
            assertEquals(JsonArray(emptyList()), result["manga"]!!.jsonArray[0].jsonObject["categoryIds"])
        }

    @Test
    fun `details lists chapters with string ids and unknown numbers as -1`() = runTest {
        backend.details[123] = WebMangaDetails(
            id = 123,
            title = "Frieren",
            author = "Yamada Kanehito",
            artist = null,
            description = null,
            status = WebLibraryWire.status(1),
            genres = listOf("Adventure", "Fantasy"),
            sourceName = "MangaDex",
            url = null,
            readingMode = WebLibraryWire.readingMode(2),
            chapters = listOf(
                WebChapter(9876, "Chapter 140", 140.0, "Group", false, true, 3, true, 1728000000000),
                WebChapter(9875, "Extra", -2.0, null, true, false, 0, false, -1),
            ),
        )
        val session = greeted()
        session.receive(request(2, "manga.details", """{"mangaId": "123"}"""))
        session.receive(request(3, "manga.details", """{"mangaId": "999"}"""))
        session.receive(request(4, "manga.details", """{"mangaId": "abc"}"""))
        runCurrent()
        val result = sink.result(2)
        assertEquals("123", result["mangaId"]!!.string())
        assertEquals("ongoing", result["status"]!!.string())
        assertEquals("rtl", result["readingMode"]!!.string())
        assertEquals(JsonNull, result["url"])
        val chapters = result["chapters"]!!.jsonArray.map { it.jsonObject }
        assertEquals("9876", chapters[0]["chapterId"]!!.string())
        assertEquals(140.0, chapters[0]["number"]!!.jsonPrimitive.double)
        assertTrue(chapters[0]["bookmark"]!!.jsonPrimitive.boolean)
        assertEquals(-1.0, chapters[1]["number"]!!.jsonPrimitive.double)
        assertEquals(0, chapters[1]["dateUpload"]!!.jsonPrimitive.long)
        assertEquals("not_found", sink.errorCode(3))
        assertEquals("bad_request", sink.errorCode(4))
    }

    @Test
    fun `reading modes and statuses map to the protocol names`() {
        assertEquals(listOf("ltr", "rtl", "vertical", "webtoon", "webtoon"), (1..5).map(WebLibraryWire::readingMode))
        assertEquals(
            listOf(
                "unknown",
                "ongoing",
                "completed",
                "licensed",
                "publishing_finished",
                "cancelled",
                "on_hiatus",
                "unknown",
            ),
            (0L..7L).map(WebLibraryWire::status),
        )
    }

    @Test
    fun `chapter pages for downloaded and online chapters, opened once per connection`() = runTest {
        backend.chapters[1] = FakeChapter(pageCount = 42, downloaded = true)
        backend.chapters[2] = FakeChapter(pageCount = 7, downloaded = false)
        backend.sourceErrors += 3
        val session = greeted()
        session.receive(request(2, "chapter.pages", """{"chapterId": "1"}"""))
        session.receive(request(3, "chapter.pages", """{"chapterId": "2"}"""))
        runCurrent()
        session.receive(request(4, "page.image", """{"chapterId": "2", "index": 6}"""))
        session.receive(request(5, "chapter.pages", """{"chapterId": "3"}"""))
        session.receive(request(6, "chapter.pages", """{"chapterId": "4"}"""))
        runCurrent()
        assertEquals(42, sink.result(2)["pageCount"]!!.jsonPrimitive.int)
        assertTrue(sink.result(2)["downloaded"]!!.jsonPrimitive.boolean)
        assertEquals("1", sink.result(2)["chapterId"]!!.string())
        assertEquals(7, sink.result(3)["pageCount"]!!.jsonPrimitive.int)
        assertEquals(false, sink.result(3)["downloaded"]!!.jsonPrimitive.boolean)
        assertEquals(1, sink.result(4)["chunks"]!!.jsonPrimitive.int)
        assertEquals("source_error", sink.errorCode(5))
        assertEquals("HTTP 403", sink.response(5)["error"]!!.jsonObject["message"]!!.string())
        assertEquals("not_found", sink.errorCode(6))
        // Chapter 2 was opened by chapter.pages and reused by page.image.
        assertEquals(4, backend.opened)
    }

    @Test
    fun `page image out of range is not found, and without an index a bad request`() = runTest {
        backend.chapters[1] = FakeChapter(pageCount = 3, downloaded = true)
        val session = greeted()
        session.receive(request(2, "page.image", """{"chapterId": "1", "index": 3}"""))
        session.receive(request(3, "page.image", """{"chapterId": "1", "index": -1}"""))
        session.receive(request(4, "page.image", """{"chapterId": "1"}"""))
        runCurrent()
        assertEquals("not_found", sink.errorCode(2))
        assertEquals("not_found", sink.errorCode(3))
        assertEquals("bad_request", sink.errorCode(4))
        assertTrue(sink.frames.isEmpty())
    }

    @Test
    fun `images are sent as chunks with an 8-byte big-endian header`() = runTest {
        val png = ByteArray(WebLibraryWire.MAX_PAYLOAD * 2 + 5) { (it % 251).toByte() }
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(png)
        backend.chapters[1] = FakeChapter(pageCount = 1, downloaded = true) { png }
        val session = greeted()
        val id = 0x01020304L
        session.receive(request(id, "page.image", """{"chapterId": "1", "index": 0}"""))
        runCurrent()
        val result = sink.result(id)
        assertEquals("image/png", result["contentType"]!!.string())
        assertEquals(png.size, result["size"]!!.jsonPrimitive.int)
        assertEquals(3, result["chunks"]!!.jsonPrimitive.int)
        assertEquals(listOf(16_384, 16_384, 13), sink.frames.map { it.size })
        sink.frames.forEachIndexed { index, frame ->
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 0, 0, 0, index.toByte()), frame.copyOfRange(0, 8))
        }
        val joined = sink.frames.fold(ByteArray(0)) { all, frame -> all + frame.copyOfRange(8, frame.size) }
        assertArrayEquals(png, joined)
    }

    @Test
    fun `chunk counts at the payload boundary`() {
        assertEquals(1, WebLibraryWire.chunkCount(1))
        assertEquals(1, WebLibraryWire.chunkCount(16_376))
        assertEquals(2, WebLibraryWire.chunkCount(16_377))
        val frame = WebLibraryWire.frame(0xFFFF_FFFFL, 1, ByteArray(16_377))
        assertEquals(9, frame.size)
        assertArrayEquals(
            byteArrayOf(-1, -1, -1, -1, 0, 0, 0, 1),
            frame.copyOfRange(0, 8),
        )
    }

    @Test
    fun `image types are sniffed`() {
        fun bytes(vararg text: String) = text.joinToString("").toByteArray(Charsets.ISO_8859_1)
        assertEquals("image/jpeg", WebLibraryWire.sniff(byteArrayOf(-1, -40, -1, 0)))
        assertEquals("image/gif", WebLibraryWire.sniff(bytes("GIF89a")))
        assertEquals("image/webp", WebLibraryWire.sniff(bytes("RIFF", "\u0000\u0000\u0000\u0000", "WEBPVP8 ")))
        assertEquals("image/avif", WebLibraryWire.sniff(bytes("\u0000\u0000\u0000\u001c", "ftypavif")))
        assertEquals("image/jpeg", WebLibraryWire.sniff(ByteArray(2)))
    }

    @Test
    fun `cover without an image is not found, with one it is binary`() = runTest {
        val session = greeted()
        session.receive(request(2, "manga.cover", """{"mangaId": "1", "maxWidth": 300}"""))
        runCurrent()
        assertEquals("not_found", sink.errorCode(2))
        backend.cover = byteArrayOf(-1, -40, -1, 1, 2, 3)
        session.receive(request(3, "manga.cover", """{"mangaId": "1"}"""))
        runCurrent()
        assertEquals("image/jpeg", sink.result(3)["contentType"]!!.string())
        assertEquals(1, sink.frames.size)
    }

    @Test
    fun `progress never un-marks a read chapter and counts a newly read one once`() = runTest {
        backend.targets[1] = WebProgressTarget(read = true, incognito = false)
        backend.targets[2] = WebProgressTarget(read = false, incognito = false)
        backend.chapters[2] = FakeChapter(pageCount = 20, downloaded = false)
        val session = greeted()
        session.receive(request(2, "chapter.progress", """{"chapterId": "1", "lastPageRead": 4, "read": false}"""))
        session.receive(request(3, "chapter.pages", """{"chapterId": "2"}"""))
        runCurrent()
        session.receive(request(4, "chapter.progress", """{"chapterId": "2", "lastPageRead": 12, "read": false}"""))
        runCurrent()
        session.receive(request(5, "chapter.progress", """{"chapterId": "2", "lastPageRead": 19, "read": true}"""))
        session.receive(request(6, "chapter.progress", """{"chapterId": "1", "lastPageRead": 9, "read": true}"""))
        session.receive(request(7, "chapter.progress", """{"chapterId": "8", "lastPageRead": 0, "read": true}"""))
        runCurrent()
        assertTrue(sink.result(2)["ok"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf(Triple(1L, 4, true), Triple(2L, 12, false), Triple(2L, 19, true), Triple(1L, 9, true)),
            backend.saved,
        )
        // Only chapter 2 became read; the page count comes from the opened chapter.
        assertEquals(listOf(2L to 20), backend.finished)
        assertEquals("not_found", sink.errorCode(7))
    }

    @Test
    fun `incognito progress records nothing`() = runTest {
        backend.targets[1] = WebProgressTarget(read = false, incognito = true)
        val session = greeted()
        session.receive(request(2, "chapter.progress", """{"chapterId": "1", "lastPageRead": 30, "read": true}"""))
        runCurrent()
        assertTrue(sink.result(2)["ok"]!!.jsonPrimitive.boolean)
        assertTrue(backend.saved.isEmpty())
        assertTrue(backend.finished.isEmpty())
    }

    @Test
    fun `a fifth concurrent request is busy`() = runTest {
        val gate = CompletableDeferred<Unit>()
        backend.chapters[1] = FakeChapter(pageCount = 10, downloaded = true).also { it.gate = gate }
        val session = greeted()
        (2L..6L).forEach { id -> session.receive(request(id, "page.image", """{"chapterId": "1", "index": 0}""")) }
        runCurrent()
        assertEquals("busy", sink.errorCode(6))
        assertEquals(2, sink.texts.size) // hello and busy
        gate.complete(Unit)
        runCurrent()
        (2L..5L).forEach { id -> assertEquals(1, sink.result(id)["chunks"]!!.jsonPrimitive.int) }
        // Slots are free again.
        session.receive(request(7, "page.image", """{"chapterId": "1", "index": 1}"""))
        runCurrent()
        assertEquals(1, sink.result(7)["chunks"]!!.jsonPrimitive.int)
    }

    @Test
    fun `closing the session closes opened chapters`() = runTest {
        val chapter = FakeChapter(pageCount = 2, downloaded = true)
        backend.chapters[1] = chapter
        val session = greeted()
        session.receive(request(2, "chapter.pages", """{"chapterId": "1"}"""))
        runCurrent()
        session.close()
        runCurrent()
        assertTrue(chapter.closed)
        session.receive(request(3, "library.list"))
        runCurrent()
        assertNull(sink.texts.firstOrNull { it["id"]!!.jsonPrimitive.long == 3L })
    }

    private fun kotlinx.serialization.json.JsonElement.string(): String = jsonPrimitive.content

    private fun manga(
        id: Long,
        categories: List<Long> = emptyList(),
        lastRead: Long? = null,
    ) = WebManga(
        id = id,
        title = "Title $id",
        author = null,
        sourceId = 2499283573021220255,
        sourceName = "MangaDex",
        unread = 3,
        downloaded = 12,
        categoryIds = categories,
        coverVersion = 1728400000000,
        lastRead = lastRead,
        dateAdded = 1720000000000,
    )
}

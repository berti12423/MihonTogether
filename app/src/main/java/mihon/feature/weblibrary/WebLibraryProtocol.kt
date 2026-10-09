package mihon.feature.weblibrary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/*
 * "My library on the website", phone side: the data channel protocol from docs/WEB_LIBRARY_PROTOCOL.md (section 2
 * and 3). Pure: everything the phone owns goes through [WebLibraryBackend], and frames leave through [WebLibrarySink],
 * so the request rules are unit-tested with fakes. All IDs are strings on the wire.
 */

/** Wire constants and the binary chunk framing. */
object WebLibraryWire {
    const val PROTOCOL = 1
    const val MAX_CONCURRENT = 4

    /** Bytes 0-3 request id, 4-7 chunk index (both uint32, big-endian). */
    const val HEADER_BYTES = 8
    const val MAX_PAYLOAD = 16_376

    const val DEFAULT_COVER_WIDTH = 300
    const val MIN_COVER_WIDTH = 100
    const val MAX_COVER_WIDTH = 600

    fun chunkCount(size: Int): Int = (size + MAX_PAYLOAD - 1) / MAX_PAYLOAD

    /** Binary frame [index] of the response to request [id]: header plus at most [MAX_PAYLOAD] bytes of [bytes]. */
    fun frame(id: Long, index: Int, bytes: ByteArray): ByteArray {
        val offset = index * MAX_PAYLOAD
        require(index >= 0 && offset < bytes.size) { "No chunk $index" }
        val length = minOf(MAX_PAYLOAD, bytes.size - offset)
        val frame = ByteArray(HEADER_BYTES + length)
        putUInt32(frame, 0, id)
        putUInt32(frame, 4, index.toLong())
        System.arraycopy(bytes, offset, frame, HEADER_BYTES, length)
        return frame
    }

    private fun putUInt32(target: ByteArray, at: Int, value: Long) {
        target[at] = (value ushr 24 and 0xFF).toInt().toByte()
        target[at + 1] = (value ushr 16 and 0xFF).toInt().toByte()
        target[at + 2] = (value ushr 8 and 0xFF).toInt().toByte()
        target[at + 3] = (value and 0xFF).toInt().toByte()
    }

    /** The image type from its first bytes; JPEG when unknown (covers are always re-encoded as JPEG). */
    fun sniff(bytes: ByteArray): String {
        fun at(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF) ?: -1
        fun ascii(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(4, "ftypavif") || ascii(4, "ftypavis") -> "image/avif"
            else -> "image/jpeg"
        }
    }

    /** Mihon's SManga status codes as the protocol's names. */
    fun status(code: Long): String = when (code) {
        1L -> "ongoing"
        2L -> "completed"
        3L -> "licensed"
        4L -> "publishing_finished"
        5L -> "cancelled"
        6L -> "on_hiatus"
        else -> "unknown"
    }

    /**
     * A resolved reader mode flag (never DEFAULT) as the protocol's name: paged left/right, paged vertical, or the
     * long strip (webtoon and continuous vertical).
     */
    fun readingMode(flag: Int): String = when (flag) {
        2 -> "rtl"
        3 -> "vertical"
        4, 5 -> "webtoon"
        else -> "ltr"
    }
}

data class WebCategory(val id: Long, val name: String, val order: Long)

data class WebManga(
    val id: Long,
    val title: String,
    val author: String?,
    val sourceId: Long,
    val sourceName: String,
    val unread: Long,
    val downloaded: Int,
    /** As stored: the default category is 0 (the protocol leaves it out). */
    val categoryIds: List<Long>,
    val coverVersion: Long,
    val lastRead: Long?,
    val dateAdded: Long,
)

data class WebLibrary(val categories: List<WebCategory>, val manga: List<WebManga>)

data class WebChapter(
    val id: Long,
    val name: String,
    val number: Double,
    val scanlator: String?,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val downloaded: Boolean,
    val dateUpload: Long,
)

data class WebMangaDetails(
    val id: Long,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val status: String,
    val genres: List<String>,
    val sourceName: String,
    val url: String?,
    val readingMode: String,
    /** Newest first (the source's order). */
    val chapters: List<WebChapter>,
)

/** A chapter opened for this connection: from disk when downloaded, otherwise its page list from the source. */
interface WebOpenedChapter {
    val pageCount: Int
    val downloaded: Boolean

    /** Page [index] (already checked to be in range). Throws [WebLibraryException] on a source error. */
    suspend fun image(index: Int): ByteArray

    fun close()
}

/** What `chapter.progress` needs to know first. */
data class WebProgressTarget(val read: Boolean, val incognito: Boolean)

class WebLibraryException(val code: String, message: String) : Exception(message) {
    companion object {
        const val BAD_REQUEST = "bad_request"
        const val NOT_FOUND = "not_found"
        const val SOURCE_ERROR = "source_error"
        const val BUSY = "busy"
        const val UNSUPPORTED_PROTOCOL = "unsupported_protocol"
        const val INTERNAL = "internal"
    }
}

/** The phone's library, as the protocol reads and updates it. */
interface WebLibraryBackend {
    suspend fun librarySize(): Int
    suspend fun library(): WebLibrary

    /** JPEG no wider than [maxWidth], or null when there's no cover. */
    suspend fun cover(mangaId: Long, maxWidth: Int): ByteArray?
    suspend fun details(mangaId: Long): WebMangaDetails?

    /** Null when the chapter isn't in the library. */
    suspend fun openChapter(chapterId: Long): WebOpenedChapter?
    suspend fun progressTarget(chapterId: Long): WebProgressTarget?

    /** Saves the last page and read state, and the history (read now), like the reader. */
    suspend fun saveProgress(chapterId: Long, lastPageRead: Int, read: Boolean)

    /** The chapter was just finished: count it like the reader does (stats, XP, the activity feed). */
    suspend fun chapterFinished(chapterId: Long, pageCount: Int?)
}

/** Outgoing frames. Implementations apply flow control (suspend while the channel's buffer is full). */
interface WebLibrarySink {
    suspend fun sendText(text: String)
    suspend fun sendBinary(frame: ByteArray)
}

data class WebLibraryHostInfo(val deviceName: String, val appBuild: Int)

/**
 * One browser connection. [receive] runs on the data channel's thread: it checks the request synchronously (hello,
 * the 4-request limit) and handles it in [scope].
 */
class WebLibrarySession(
    private val backend: WebLibraryBackend,
    private val info: WebLibraryHostInfo,
    private val sink: WebLibrarySink,
    private val scope: CoroutineScope,
    private val onRequest: () -> Unit = {},
) {
    @Volatile private var greeted = false
    private val active = AtomicInteger(0)
    private val chapterLock = Mutex()
    private val chapters = LinkedHashMap<Long, CompletableDeferred<WebOpenedChapter?>>()

    @Volatile private var closed = false

    fun receive(text: String) {
        if (closed) return
        val request = parse(text) ?: return
        onRequest()
        val (id, method, params) = request
        if (method == null) return reply(id, error(WebLibraryException.BAD_REQUEST, "Missing method"))
        if (method == "hello") {
            val protocol = params["protocol"]?.jsonInt()
            if (protocol != WebLibraryWire.PROTOCOL) {
                return reply(
                    id,
                    error(
                        WebLibraryException.UNSUPPORTED_PROTOCOL,
                        "The phone speaks protocol ${WebLibraryWire.PROTOCOL}",
                    ),
                )
            }
            greeted = true
        } else if (!greeted) {
            return reply(id, error(WebLibraryException.BAD_REQUEST, "Send hello first"))
        }
        if (active.incrementAndGet() > WebLibraryWire.MAX_CONCURRENT) {
            active.decrementAndGet()
            return reply(id, error(WebLibraryException.BUSY, "At most ${WebLibraryWire.MAX_CONCURRENT} requests"))
        }
        scope.launch {
            try {
                handle(id, method, params)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    /** Closes the chapters opened for this connection (archive files, page lists). */
    fun close() {
        closed = true
        scope.launch {
            chapterLock.withLock {
                chapters.values.forEach { deferred -> closeQuietly(deferred) }
                chapters.clear()
            }
        }
    }

    private fun reply(id: Long, message: JsonObject) {
        scope.launch { send(id, message) }
    }

    private suspend fun send(id: Long, message: JsonObject) {
        sink.sendText(JsonObject(mapOf("id" to JsonPrimitive(id)) + message).toString())
    }

    private suspend fun handle(id: Long, method: String, params: JsonObject) {
        try {
            when (method) {
                "hello" -> send(id, result(hello()))
                "library.list" -> send(id, result(libraryList()))
                "manga.cover" -> cover(id, params)
                "manga.details" -> send(id, result(details(params)))
                "chapter.pages" -> send(id, result(chapterPages(params)))
                "page.image" -> pageImage(id, params)
                "chapter.progress" -> send(id, result(progress(params)))
                else -> throw WebLibraryException(WebLibraryException.BAD_REQUEST, "Unknown method $method")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: WebLibraryException) {
            send(id, error(error.code, error.message.orEmpty()))
        } catch (error: Exception) {
            send(id, error(WebLibraryException.INTERNAL, error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun hello() = buildJsonObject {
        put("protocol", WebLibraryWire.PROTOCOL)
        put("deviceName", info.deviceName)
        put("appBuild", info.appBuild)
        put("librarySize", backend.librarySize())
    }

    private suspend fun libraryList(): JsonObject {
        val library = backend.library()
        val uncategorized = library.manga.any { manga -> manga.categoryIds.none { it != 0L } }
        val categories = library.categories
            .filter { it.id != 0L || uncategorized }
            .sortedBy { it.order }
        return buildJsonObject {
            put(
                "categories",
                buildJsonArray {
                    categories.forEach { category ->
                        add(
                            buildJsonObject {
                                put("categoryId", category.id.toString())
                                put("name", category.name)
                                put("order", category.order)
                            },
                        )
                    }
                },
            )
            put(
                "manga",
                buildJsonArray {
                    library.manga.forEach { manga ->
                        add(
                            buildJsonObject {
                                put("mangaId", manga.id.toString())
                                put("title", manga.title)
                                put("author", manga.author)
                                put("sourceId", manga.sourceId.toString())
                                put("sourceName", manga.sourceName)
                                put("unread", manga.unread)
                                put("downloaded", manga.downloaded)
                                put("categoryIds", strings(manga.categoryIds.filter { it != 0L }.map(Long::toString)))
                                put("coverVersion", manga.coverVersion.toString())
                                put("lastRead", manga.lastRead)
                                put("dateAdded", manga.dateAdded)
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun cover(id: Long, params: JsonObject) {
        val mangaId = params.id("mangaId")
        val maxWidth = (params["maxWidth"]?.jsonInt() ?: WebLibraryWire.DEFAULT_COVER_WIDTH)
            .coerceIn(WebLibraryWire.MIN_COVER_WIDTH, WebLibraryWire.MAX_COVER_WIDTH)
        val bytes = backend.cover(mangaId, maxWidth)
            ?.takeIf { it.isNotEmpty() }
            ?: throw WebLibraryException(WebLibraryException.NOT_FOUND, "No cover for $mangaId")
        sendBinary(id, bytes)
    }

    private suspend fun details(params: JsonObject): JsonObject {
        val mangaId = params.id("mangaId")
        val details = backend.details(mangaId)
            ?: throw WebLibraryException(WebLibraryException.NOT_FOUND, "Manga $mangaId isn't in the library")
        return buildJsonObject {
            put("mangaId", details.id.toString())
            put("title", details.title)
            put("author", details.author)
            put("artist", details.artist)
            put("description", details.description)
            put("status", details.status)
            put("genres", strings(details.genres))
            put("sourceName", details.sourceName)
            put("url", details.url)
            put("readingMode", details.readingMode)
            put(
                "chapters",
                buildJsonArray {
                    details.chapters.forEach { chapter ->
                        add(
                            buildJsonObject {
                                put("chapterId", chapter.id.toString())
                                put("name", chapter.name)
                                put("number", if (chapter.number >= 0) chapter.number else -1.0)
                                put("scanlator", chapter.scanlator)
                                put("read", chapter.read)
                                put("bookmark", chapter.bookmark)
                                put("lastPageRead", chapter.lastPageRead)
                                put("downloaded", chapter.downloaded)
                                put("dateUpload", chapter.dateUpload.coerceAtLeast(0))
                            },
                        )
                    }
                },
            )
        }
    }

    private suspend fun chapterPages(params: JsonObject): JsonObject {
        val chapterId = params.id("chapterId")
        val chapter = opened(chapterId)
        return buildJsonObject {
            put("chapterId", chapterId.toString())
            put("pageCount", chapter.pageCount)
            put("downloaded", chapter.downloaded)
        }
    }

    private suspend fun pageImage(id: Long, params: JsonObject) {
        val chapterId = params.id("chapterId")
        val index = params["index"]?.jsonInt()
            ?: throw WebLibraryException(WebLibraryException.BAD_REQUEST, "Missing index")
        val chapter = opened(chapterId)
        if (index !in 0..<chapter.pageCount) {
            throw WebLibraryException(WebLibraryException.NOT_FOUND, "Page $index isn't in chapter $chapterId")
        }
        sendBinary(id, chapter.image(index))
    }

    private suspend fun progress(params: JsonObject): JsonObject {
        val chapterId = params.id("chapterId")
        val lastPageRead = params["lastPageRead"]?.jsonInt()?.takeIf { it >= 0 }
            ?: throw WebLibraryException(WebLibraryException.BAD_REQUEST, "Missing lastPageRead")
        val read = (params["read"] as? JsonPrimitive)?.booleanOrNull ?: false
        val target = backend.progressTarget(chapterId)
            ?: throw WebLibraryException(WebLibraryException.NOT_FOUND, "Chapter $chapterId isn't in the library")
        // Incognito (the app's or the title's source): nothing is recorded, like the reader.
        if (!target.incognito) {
            backend.saveProgress(chapterId, lastPageRead, read = target.read || read)
            if (read && !target.read) backend.chapterFinished(chapterId, openedPageCount(chapterId))
        }
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun sendBinary(id: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) throw WebLibraryException(WebLibraryException.NOT_FOUND, "Empty image")
        val chunks = WebLibraryWire.chunkCount(bytes.size)
        send(
            id,
            result(
                buildJsonObject {
                    put("contentType", WebLibraryWire.sniff(bytes))
                    put("size", bytes.size)
                    put("chunks", chunks)
                },
            ),
        )
        for (index in 0..<chunks) sink.sendBinary(WebLibraryWire.frame(id, index, bytes))
    }

    /** The chapter, opened once per connection and shared by its requests; the oldest of [MAX_OPEN] is closed. */
    private suspend fun opened(chapterId: Long): WebOpenedChapter {
        var owner = false
        val deferred = chapterLock.withLock {
            chapters.remove(chapterId)?.also { chapters[chapterId] = it }
                ?: CompletableDeferred<WebOpenedChapter?>().also { created ->
                    owner = true
                    chapters[chapterId] = created
                    while (chapters.size > MAX_OPEN) {
                        val oldest = chapters.keys.first()
                        chapters.remove(oldest)?.let(::closeQuietly)
                    }
                }
        }
        if (owner) {
            try {
                deferred.complete(backend.openChapter(chapterId))
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
            }
        }
        val chapter = try {
            deferred.await()
        } catch (error: CancellationException) {
            // The request that was opening it went away; this one is still running.
            currentCoroutineContext().ensureActive()
            forget(chapterId, deferred)
            throw WebLibraryException(WebLibraryException.SOURCE_ERROR, "Loading the chapter was interrupted")
        } catch (error: Exception) {
            forget(chapterId, deferred)
            if (error is WebLibraryException) throw error
            throw WebLibraryException(WebLibraryException.SOURCE_ERROR, error.message ?: error.javaClass.simpleName)
        }
        if (chapter == null) {
            forget(chapterId, deferred)
            throw WebLibraryException(WebLibraryException.NOT_FOUND, "Chapter $chapterId isn't in the library")
        }
        return chapter
    }

    private suspend fun openedPageCount(chapterId: Long): Int? = chapterLock.withLock {
        chapters[chapterId]?.takeIf { it.isCompleted && !it.isCancelled }?.let { deferred ->
            runCatching { deferred.getCompleted()?.pageCount }.getOrNull()
        }
    }

    private suspend fun forget(chapterId: Long, deferred: Deferred<WebOpenedChapter?>) {
        chapterLock.withLock { if (chapters[chapterId] === deferred) chapters.remove(chapterId) }
    }

    private fun closeQuietly(deferred: Deferred<WebOpenedChapter?>) {
        if (deferred.isCompleted && !deferred.isCancelled) {
            runCatching { deferred.getCompleted()?.close() }
        } else {
            deferred.invokeOnCompletion { cause ->
                if (cause == null) runCatching { deferred.getCompleted()?.close() }
            }
        }
    }

    private fun parse(text: String): Triple<Long, String?, JsonObject>? {
        val message = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val id = (message["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?.takeIf { it in 1..MAX_ID } ?: return null
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return Triple(id, method, params)
    }

    private companion object {
        const val MAX_OPEN = 3
        const val MAX_ID = 0xFFFF_FFFFL

        fun result(value: JsonObject) = JsonObject(mapOf("result" to value))

        fun error(code: String, message: String) = JsonObject(
            mapOf(
                "error" to buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            ),
        )

        fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

        fun JsonElement.jsonInt(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { primitive ->
            primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
        }

        /** A string ID (numbers are accepted too); bad_request otherwise. */
        fun JsonObject.id(name: String): Long =
            (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.toLongOrNull()
                ?: throw WebLibraryException(WebLibraryException.BAD_REQUEST, "Missing $name")
    }
}

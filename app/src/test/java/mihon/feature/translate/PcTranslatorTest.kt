package mihon.feature.translate

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.ConnectException

class PcTranslatorTest {
    private val address = PcAddress("192.168.1.20")
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)

    /** A fake translator: [reply] answers each request (null = connection refused, as when the program is closed). */
    private fun translator(seen: MutableList<Request> = mutableListOf(), reply: (Request) -> Pair<Int, Any>?) =
        PcTranslator(
            OkHttpClient.Builder().addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    seen += request
                    val (code, body) = reply(request) ?: throw ConnectException("Connection refused")
                    val responseBody = when (body) {
                        is ByteArray -> body.toResponseBody("image/png".toMediaType())
                        else -> body.toString().toResponseBody("application/json".toMediaType())
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                        .body(responseBody).build()
                },
            ).build(),
        )

    private fun bodyOf(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    @Test
    fun `pairing sends the code and device name and returns the token and PC name`() = runTest {
        val seen = mutableListOf<Request>()
        val result = translator(seen) { 200 to """{"token":"tok_abc","name":"BERTI-PC"}""" }
            .pair(address, "123456", "Galaxy A35")
        assertEquals(PcPairResult.Paired("tok_abc", "BERTI-PC"), result)
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("http://192.168.1.20:47824/pair", request.url.toString())
        assertTrue(bodyOf(request).contains("\"code\":\"123456\""))
        assertTrue(bodyOf(request).contains("\"device\":\"Galaxy A35\""))
    }

    @Test
    fun `a wrong code and a locked translator are told apart`() = runTest {
        assertEquals(
            PcPairResult.BadCode,
            translator { 403 to """{"error":"bad_code"}""" }.pair(address, "000000", "Phone"),
        )
        assertEquals(
            PcPairResult.Locked,
            translator { 429 to """{"error":"locked"}""" }.pair(address, "000000", "Phone"),
        )
        assertThrows<TranslationException.Failed> {
            translator { 403 to """{"error":"forbidden_origin"}""" }.pair(address, "000000", "Phone")
        }
        assertThrows<TranslationException.Failed> {
            translator { 400 to """{"error":"bad_device"}""" }.pair(address, "000000", "Phone")
        }
    }

    @Test
    fun `device names are cleaned to what the translator accepts`() {
        assertEquals("Galaxy A35", PcTranslator.cleanDeviceName(" Galaxy\u0000 A35\n"))
        assertEquals("Android phone", PcTranslator.cleanDeviceName("\t"))
        assertEquals(60, PcTranslator.cleanDeviceName("x".repeat(80)).length)
    }

    @Test
    fun `unpair accepts 204 with no body`() = runTest {
        assertTrue(translator { 204 to "" }.unpair(address, "tok"))
        assertFalse(translator { 401 to """{"error":"unpaired"}""" }.unpair(address, "tok"))
    }

    @Test
    fun `pairing with a closed translator says it is unreachable`() = runTest {
        val error = assertThrows<TranslationException.Unavailable> {
            translator { null }.pair(address, "123456", "Phone")
        }
        assertEquals(PcTranslator.UNREACHABLE, error.code)
    }

    @Test
    fun `health reads ready, loading with progress, and whether the token is still paired`() = runTest {
        val seen = mutableListOf<Request>()
        val ready = translator(seen) {
            200 to """{"name":"mihon-together-translator","api":1,"version":"0.2.0","state":"ready","progress":null,
                "error":null,"device":"cuda","models":{},"queue":0,"lastPageMs":4300,"paired":true}"""
        }.health(address, "tok")
        assertTrue(ready.ready)
        assertEquals(true, ready.paired)
        assertEquals("0.2.0", ready.version)
        assertEquals("Bearer tok", seen.single().header("Authorization"))

        val loading = translator {
            200 to """{"state":"downloading","progress":{"label":"Downloading gemma4-12b-it","done":1,"total":9},
                "paired":false}"""
        }.health(address, "tok")
        assertFalse(loading.ready)
        assertTrue(loading.loading)
        assertEquals("Downloading gemma4-12b-it", loading.progressLabel)
        assertEquals(false, loading.paired)

        val seenAnon = mutableListOf<Request>()
        val anonymous = translator(seenAnon) { 200 to """{"state":"loading"}""" }.health(address)
        assertNull(anonymous.paired)
        assertNull(seenAnon.single().header("Authorization"))
    }

    @Test
    fun `translate posts the page with the token and target and returns the PNG`() = runTest {
        val seen = mutableListOf<Request>()
        val result = translator(seen) { 200 to png }.translate(address, "tok", jpeg, "image/jpeg", "en")
        assertArrayEquals(png, result)
        val request = seen.single()
        assertEquals("http://192.168.1.20:47824/translate?target=en-US", request.url.toString())
        assertEquals("Bearer tok", request.header("Authorization"))
        assertEquals("image/jpeg", request.body!!.contentType().toString())
        assertArrayEquals(jpeg, Buffer().also { request.body!!.writeTo(it) }.readByteArray())
    }

    @Test
    fun `translate errors map to the reader's problems`() = runTest {
        assertThrows<TranslationException.InvalidKey> {
            translator { 401 to """{"error":"unpaired"}""" }.translate(address, "tok", jpeg, "image/jpeg", "en")
        }
        val loading = assertThrows<TranslationException.Unavailable> {
            translator { 503 to """{"error":"not_ready","message":"loading"}""" }
                .translate(address, "tok", jpeg, "image/jpeg", "en")
        }
        assertEquals(PcTranslator.NOT_READY, loading.code)
        val closed = assertThrows<TranslationException.Unavailable> {
            translator { null }.translate(address, "tok", jpeg, "image/jpeg", "en")
        }
        assertEquals(PcTranslator.UNREACHABLE, closed.code)
        for (code in listOf(400, 413, 500)) {
            assertThrows<TranslationException.Failed> {
                translator { code to """{"error":"x","message":"nope"}""" }
                    .translate(address, "tok", jpeg, "image/jpeg", "en")
            }
        }
    }

    @Test
    fun `unpair asks the PC to forget the phone, and a closed PC is not an error`() = runTest {
        val seen = mutableListOf<Request>()
        assertTrue(translator(seen) { 200 to "{}" }.unpair(address, "tok"))
        assertEquals("DELETE", seen.single().method)
        assertEquals("Bearer tok", seen.single().header("Authorization"))
        assertFalse(translator { null }.unpair(address, "tok"))
    }

    @Test
    fun `the engine finds a moved PC by name and remembers its new address`() = runTest {
        val moved = PcAddress("192.168.1.42")
        var remembered: PcAddress? = null
        val seen = mutableListOf<Request>()
        val engine = PcTranslationEngine(
            translator(seen) { if (it.url.host == "192.168.1.42") 200 to png else null },
            address,
            "tok",
            "BERTI-PC",
            relocate = { name -> moved.takeIf { name == "BERTI-PC" } },
            onRelocated = { remembered = it },
        )
        assertArrayEquals(png, engine.renderPage(jpeg, "en"))
        assertEquals(moved, remembered)
        assertEquals(listOf("192.168.1.20", "192.168.1.42"), seen.map { it.url.host })
        assertEquals("image/jpeg", seen.last().body!!.contentType().toString())

        val lost = PcTranslationEngine(translator { null }, address, "tok", "BERTI-PC", { null }, {})
        assertThrows<TranslationException.Unavailable> { lost.renderPage(jpeg, "en") }
    }

    @Test
    fun `AVIF pages go to the PC as PNG, and unreadable ones fail without a request`() = runTest {
        val avif = "\u0000\u0000\u0000\u001cftypavif".toByteArray()
        val seen = mutableListOf<Request>()
        val engine = PcTranslationEngine(
            translator(seen) { 200 to png },
            address,
            "tok",
            "BERTI-PC",
            relocate = { null },
            onRelocated = {},
            toPng = { png },
        )
        assertArrayEquals(png, engine.renderPage(avif, "en"))
        assertEquals("image/png", seen.single().body!!.contentType().toString())
        assertArrayEquals(png, Buffer().also { seen.single().body!!.writeTo(it) }.readByteArray())

        val unreadable = PcTranslationEngine(translator(seen) { 200 to png }, address, "tok", "", { null }, {})
        assertThrows<TranslationException.Failed> { unreadable.renderPage(avif, "en") }
        assertEquals(1, seen.size)
    }

    @Test
    fun `manual addresses accept IP or IP and port, and nothing else`() {
        assertEquals(PcAddress("192.168.1.20", 47824), PcAddress.parse("192.168.1.20"))
        assertEquals(PcAddress("192.168.1.20", 5000), PcAddress.parse(" 192.168.1.20:5000 "))
        assertEquals(PcAddress("10.0.2.2", 47824), PcAddress.parse("http://10.0.2.2:47824/"))
        assertEquals(PcAddress("berti-pc.local", 47824), PcAddress.parse("Berti-PC.local"))
        assertEquals(PcAddress("fe80::1", 47824), PcAddress.parse("[fe80::1]:47824"))
        assertEquals("192.168.1.20:47824", PcAddress("192.168.1.20").toString())
        listOf(
            "", "192.168.1", "192.168.1.256", "192.168.1.20:0", "192.168.1.20:70000", "192.168.1.20:abc",
            "https://192.168.1.20", "192.168.1.20/translate", "a b", "1:2:3", "user@192.168.1.20", "-bad.local",
        ).forEach { assertNull(PcAddress.parse(it), it) }
    }

    @Test
    fun `the page type is sniffed from its bytes`() {
        assertEquals("image/jpeg", PcTranslator.contentType(jpeg))
        assertEquals("image/png", PcTranslator.contentType(png))
        assertEquals("image/webp", PcTranslator.contentType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals("image/gif", PcTranslator.contentType("GIF89a".toByteArray()))
        assertEquals("image/avif", PcTranslator.contentType("\u0000\u0000\u0000\u001cftypavif".toByteArray()))
        assertEquals("application/octet-stream", PcTranslator.contentType(byteArrayOf(1, 2)))
    }

    @Test
    fun `a dropped connection counts as unreachable`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor(
            Interceptor { throw IOException("reset") },
        ).build()
        val error = assertThrows<TranslationException.Unavailable> {
            PcTranslator(client).translate(address, "tok", jpeg, "image/jpeg", "en")
        }
        assertEquals(PcTranslator.UNREACHABLE, error.code)
    }
}

package mihon.feature.translate

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GeminiTranslationEngineTest {
    // Shape of a real Interactions reply (tools/translate-spike), trimmed.
    private val reply = """
        {"status":"completed","usage":{"total_tokens":2500},"steps":[
          {"signature":"abc","type":"thought"},
          {"content":[{"text":"{\"sourceLanguage\":\"ko\",\"blocks\":[{\"box_2d\":[146,680,156,825],\"kind\":\"dialogue\",\"vertical\":false,\"lines\":1,\"source\":\"어서 오세요~\",\"translation\":\"Welcome~\"},{\"box_2d\":[198,45,215,169],\"kind\":\"sfx\",\"vertical\":false,\"lines\":1,\"source\":\"끼익~\",\"translation\":\"Creeeak~\"},{\"box_2d\":[1,2,3],\"kind\":\"other\",\"vertical\":false,\"lines\":1,\"source\":\"x\",\"translation\":\"y\"}]}","type":"text"}],"type":"model_output"}
        ],"object":"interaction","model":"gemini-3.1-flash-lite"}
    """.trimIndent()

    private fun client(code: Int, body: String, seen: MutableList<okhttp3.Request> = mutableListOf()) =
        OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                seen += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            },
        ).build()

    private val request = TranslationRequest("en", "ko", context = listOf("Hello there"))

    @Test
    fun `parses the model output step into blocks, dropping invalid boxes`() {
        val result = GeminiTranslationEngine.parseResponse(reply)
        assertEquals("ko", result.sourceLanguage)
        assertEquals(listOf("Welcome~", "Creeeak~"), result.blocks.map { it.translation })
        assertEquals(NormBox(0.146f, 0.68f, 0.156f, 0.825f), result.blocks[0].box)
        assertTrue(result.blocks[1].isSfx)
    }

    @Test
    fun `request matches the Interactions API shape and sends the key only as a header`() = runTest {
        val seen = mutableListOf<okhttp3.Request>()
        val engine = GeminiTranslationEngine(client(200, reply, seen), "secret-key", "gemini-3.1-flash-lite")
        engine.translate(byteArrayOf(1, 2, 3), "image/jpeg", request)

        val sent = seen.single()
        assertEquals(GeminiTranslationEngine.ENDPOINT, sent.url.toString())
        assertEquals("secret-key", sent.header("x-goog-api-key"))
        val body = Buffer().also { sent.body!!.writeTo(it) }.readUtf8()
        assertFalse("secret-key" in body)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals("gemini-3.1-flash-lite", json["model"]!!.jsonPrimitive.content)
        assertEquals("false", json["store"]!!.jsonPrimitive.content)
        val input = json["input"]!!.jsonArray
        assertTrue("Hello there" in input[0].jsonObject["text"]!!.jsonPrimitive.content) // context lines
        assertEquals("AQID", input[1].jsonObject["data"]!!.jsonPrimitive.content) // base64 of 1,2,3
        assertEquals("application/json", json["response_format"]!!.jsonObject["mime_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `http errors map to actionable kinds without retrying`() = runTest {
        val seen = mutableListOf<okhttp3.Request>()
        val quota = """{"error":{"code":429,"message":"Rate limit exceeded (limit: 20 requests per day)"}}"""
        val engine = GeminiTranslationEngine(client(429, quota, seen), "k", "gemini-3.8-flash")
        val error = assertThrows<TranslationException.QuotaExceeded> {
            engine.translate(byteArrayOf(1), "image/jpeg", request)
        }
        assertTrue("20 requests per day" in error.message!!)
        assertEquals(1, seen.size)

        assertTrue(
            GeminiTranslationEngine.httpError(
                400,
                """{"error":{"message":"API key not valid."}}""",
            ) is TranslationException.InvalidKey,
        )
        assertTrue(GeminiTranslationEngine.httpError(403, "") is TranslationException.InvalidKey)
        assertTrue(GeminiTranslationEngine.httpError(503, "busy") is TranslationException.Unavailable)
        assertTrue(GeminiTranslationEngine.httpError(400, "bad") is TranslationException.Failed)
    }

    @Test
    fun `missing key fails before any request`() = runTest {
        val seen = mutableListOf<okhttp3.Request>()
        assertThrows<TranslationException.InvalidKey> {
            GeminiTranslationEngine(client(200, reply, seen), " ", "m").translate(byteArrayOf(1), "image/jpeg", request)
        }
        assertTrue(seen.isEmpty())
    }
}

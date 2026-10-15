package mihon.feature.translate

import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/** What the reader asks an engine for one image (a page or a slice of one). */
data class TranslationRequest(
    val targetLanguage: String,
    /** ISO 639-1 hint, or null to let the model detect it. */
    val sourceLanguage: String?,
    /** Recent translated lines from earlier pages/slices, oldest first, so names and tone stay consistent. */
    val context: List<String> = emptyList(),
)

/** Blocks normalized to the image that was sent. */
data class EngineResult(val blocks: List<TranslatedBlock>, val sourceLanguage: String?)

interface TranslationEngine {
    /** Identifies engine + model in the cache key: a different model must not reuse another's results. */
    val id: String

    /**
     * How the engine wants its images: Gemini's default keeps uploads small (cost and tokens); on-device OCR wants
     * full resolution, because ML Kit needs about 16 px per character.
     */
    val imageFormat: ImageFormat get() = ImageFormat.UPLOAD

    suspend fun translate(image: ByteArray, mimeType: String, request: TranslationRequest): EngineResult
}

/**
 * An engine that draws the finished page itself (the PC translator): it gets the whole original page and returns the
 * translated image. There are no text blocks, so nothing can be edited or redrawn with a glossary.
 */
interface PageRenderingEngine : TranslationEngine {
    suspend fun renderPage(image: ByteArray, targetLanguage: String): ByteArray

    override suspend fun translate(image: ByteArray, mimeType: String, request: TranslationRequest): EngineResult =
        throw UnsupportedOperationException("$id renders whole pages")
}

/**
 * Gemini vision via the Interactions REST API with the reader's own key: one request per image returns every text
 * region with its box and translation as structured JSON (validated in tools/translate-spike). Requests use
 * `store: false`; nothing is retried here, because on the free tier retries burn the daily quota.
 */
class GeminiTranslationEngine(
    private val client: OkHttpClient,
    private val apiKey: String,
    val model: String,
    private val endpoint: String = ENDPOINT,
) : TranslationEngine {
    override val id = "gemini:$model:v$PROMPT_VERSION"

    override suspend fun translate(image: ByteArray, mimeType: String, request: TranslationRequest): EngineResult {
        if (apiKey.isBlank()) throw TranslationException.InvalidKey("No Gemini API key")
        val body = requestBody(image, mimeType, request).toString().toRequestBody(JSON_MEDIA)
        val call = client.newCall(
            Request.Builder().url(endpoint).header("x-goog-api-key", apiKey).post(body).build(),
        )
        val response = try {
            call.await()
        } catch (error: IOException) {
            throw TranslationException.Failed("Network error: ${error.message}", error)
        }
        val text = response.use { it.body.string() }
        if (!response.isSuccessful) throw httpError(response.code, text)
        return parseResponse(text)
    }

    fun requestBody(image: ByteArray, mimeType: String, request: TranslationRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("store", false)
        put("system_instruction", instruction(request))
        putJsonArray("input") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", userPrompt(request))
                },
            )
            add(
                buildJsonObject {
                    put("type", "image")
                    put("data", Base64.getEncoder().encodeToString(image))
                    put("mime_type", mimeType)
                    put("resolution", "high")
                },
            )
        }
        putJsonObject("response_format") {
            put("type", "text")
            put("mime_type", "application/json")
            put("schema", SCHEMA)
        }
        putJsonObject("generation_config") { put("temperature", 0.1) }
    }

    companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"

        /** Bump when the prompt or schema changes, so cached pages are re-translated. */
        const val PROMPT_VERSION = 1

        private val JSON_MEDIA = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        private fun instruction(request: TranslationRequest) = buildString {
            append("You translate raw manga, manhwa and manhua pages into natural ")
            append(languageName(request.targetLanguage))
            append(" for comic readers. Find every text region: speech and thought bubbles, captions or narration, ")
            append("signs, and sound effects. Return one block per bubble or caption (merge the lines of one bubble), ")
            append("in the page's reading order (Japanese: right-to-left, top-to-bottom; webtoons: top-to-bottom). ")
            append("box_2d must tightly enclose that block's text as [ymin, xmin, ymax, xmax] normalized to 0-1000. ")
            append("lines is how many lines the original text occupies. Mark sound effects drawn into the art as ")
            append("kind \"sfx\". Translate for meaning and tone, keep names consistent with the earlier lines you ")
            append("are given, and keep honorifics only when they carry meaning. If a region is unreadable, return ")
            append("its box with empty source and translation.")
        }

        private fun userPrompt(request: TranslationRequest) = buildString {
            append("Translate this image")
            request.sourceLanguage?.let { append(" (source language: ${languageName(it)})") }
            append(".")
            if (request.context.isNotEmpty()) {
                append("\nEarlier lines, for consistency only (do not translate these again):\n")
                request.context.takeLast(MAX_CONTEXT_LINES).forEach { append("- ").append(it).append('\n') }
            }
        }

        const val MAX_CONTEXT_LINES = 10

        fun languageName(code: String) = when (code) {
            "en" -> "English"
            "ko" -> "Korean"
            "ja" -> "Japanese"
            "zh" -> "Chinese"
            else -> code
        }

        private val SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("sourceLanguage") { put("type", "string") }
                putJsonObject("blocks") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("box_2d") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "integer") }
                            }
                            putJsonObject("kind") {
                                put("type", "string")
                                put("enum", strings("dialogue", "narration", "sfx", "sign", "other"))
                            }
                            putJsonObject("vertical") { put("type", "boolean") }
                            putJsonObject("lines") { put("type", "integer") }
                            putJsonObject("source") { put("type", "string") }
                            putJsonObject("translation") { put("type", "string") }
                        }
                        put("required", strings("box_2d", "kind", "vertical", "lines", "source", "translation"))
                    }
                }
            }
            put("required", strings("sourceLanguage", "blocks"))
        }

        private fun strings(vararg values: String) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

        /** Reads the model's JSON out of an Interactions reply (the text of its `model_output` step). */
        fun parseResponse(body: String): EngineResult {
            val output = try {
                json.parseToJsonElement(body).jsonObject["steps"]?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .lastOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "model_output" }
                    ?.get("content")?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("")
            } catch (error: IllegalArgumentException) {
                throw TranslationException.Failed("Unreadable reply", error)
            }
            if (output.isBlank()) throw TranslationException.Failed("Empty reply")
            val result = try {
                json.parseToJsonElement(output).jsonObject
            } catch (error: IllegalArgumentException) {
                throw TranslationException.Failed("Reply wasn't JSON", error)
            }
            val blocks = (result["blocks"] as? JsonArray).orEmpty().mapNotNull { element ->
                val block = element as? JsonObject ?: return@mapNotNull null
                val box = (block["box_2d"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull }
                    ?.let(NormBox::fromBox2d) ?: return@mapNotNull null
                TranslatedBlock(
                    box = box,
                    kind = block["kind"]?.jsonPrimitive?.contentOrNull ?: "other",
                    vertical = block["vertical"]?.jsonPrimitive?.booleanOrNull ?: false,
                    source = block["source"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    translation = block["translation"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    lines = (block["lines"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 30),
                )
            }
            return EngineResult(blocks, result["sourceLanguage"]?.jsonPrimitive?.contentOrNull)
        }

        /** Maps Google API errors to what the reader can act on; never echoes the key. */
        fun httpError(code: Int, body: String): TranslationException {
            val message = try {
                json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")
                    ?.jsonPrimitive?.contentOrNull
            } catch (_: IllegalArgumentException) {
                null
            } ?: "HTTP $code"
            return when {
                code == 429 -> TranslationException.QuotaExceeded(message)
                code == 401 || code == 403 -> TranslationException.InvalidKey(message)
                code == 400 && "API key" in message -> TranslationException.InvalidKey(message)
                code >= 500 -> TranslationException.Unavailable(message)
                else -> TranslationException.Failed(message)
            }
        }
    }
}

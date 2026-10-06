package mihon.feature.translate

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

private val JSON_MEDIA = "application/json".toMediaType()
private val json = Json { ignoreUnknownKeys = true }

private suspend fun OkHttpClient.send(request: Request): Pair<Response, String> {
    val response = try {
        newCall(request).await()
    } catch (error: IOException) {
        throw TranslationException.Failed("Network error: ${error.message}", error)
    }
    return response to response.use { it.body.string() }
}

/**
 * DeepL with the reader's own key. The DeepL API Free plan translates 500,000 characters a month at no cost (about
 * a few hundred chapters); free keys end in ":fx" and use their own host. One request per page.
 */
class DeepLTranslator(private val client: OkHttpClient, private val key: String) : TextTranslator {
    override val id = "deepl"

    override suspend fun translate(
        texts: List<String>,
        source: String,
        target: String,
        context: List<String>,
    ): List<String> {
        if (key.isBlank()) throw TranslationException.InvalidKey("No DeepL key")
        val request = Request.Builder()
            .url(endpoint(key))
            .header("Authorization", "DeepL-Auth-Key $key")
            .post(body(texts, source, target, context).toString().toRequestBody(JSON_MEDIA))
            .build()
        val (response, text) = client.send(request)
        if (!response.isSuccessful) throw error(response.code, text)
        return parse(text, texts.size)
    }

    companion object {
        fun endpoint(key: String) = if (key.endsWith(":fx")) {
            "https://api-free.deepl.com/v2/translate"
        } else {
            "https://api.deepl.com/v2/translate"
        }

        fun body(texts: List<String>, source: String, target: String, context: List<String>): JsonObject =
            buildJsonObject {
                putJsonArray("text") { texts.forEach { add(JsonPrimitive(it)) } }
                put("source_lang", source.uppercase())
                put("target_lang", if (target == "en") "EN-US" else target.uppercase())
                // Context isn't translated or billed; it keeps names and tone consistent across pages.
                if (context.isNotEmpty()) put("context", context.joinToString("\n"))
            }

        fun parse(body: String, expected: Int): List<String> {
            val translations = try {
                json.parseToJsonElement(body).jsonObject["translations"]?.jsonArray.orEmpty()
                    .map { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            } catch (error: IllegalArgumentException) {
                throw TranslationException.Failed("Unreadable DeepL reply", error)
            }
            if (translations.size != expected) throw TranslationException.Failed("DeepL returned the wrong count")
            return translations
        }

        fun error(code: Int, body: String): TranslationException {
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "HTTP $code"
            return when (code) {
                401, 403 -> TranslationException.InvalidKey(message)
                456 -> TranslationException.QuotaExceeded("DeepL monthly character limit reached")
                429 -> TranslationException.Unavailable("DeepL is busy, try again shortly")
                in 500..599 -> TranslationException.Unavailable(message)
                else -> TranslationException.Failed(message)
            }
        }
    }
}

/**
 * Naver Papago through Naver Cloud Platform with the reader's own Client ID and Secret. Papago is strongest for
 * Korean, but Naver Cloud bills it per character (there is no free plan since 2024). One request per bubble.
 */
class PapagoTranslator(
    private val client: OkHttpClient,
    private val clientId: String,
    private val clientSecret: String,
) : TextTranslator {
    override val id = "papago"

    override suspend fun translate(
        texts: List<String>,
        source: String,
        target: String,
        context: List<String>,
    ): List<String> {
        if (clientId.isBlank() || clientSecret.isBlank()) throw TranslationException.InvalidKey("No Papago key")
        val permits = Semaphore(3)
        return coroutineScope {
            texts.map { text ->
                async {
                    permits.withPermit {
                        val request = Request.Builder()
                            .url(ENDPOINT)
                            .header("X-NCP-APIGW-API-KEY-ID", clientId)
                            .header("X-NCP-APIGW-API-KEY", clientSecret)
                            .post(body(text, source, target).toString().toRequestBody(JSON_MEDIA))
                            .build()
                        val (response, body) = client.send(request)
                        if (!response.isSuccessful) throw error(response.code, body)
                        parse(body)
                    }
                }
            }.awaitAll()
        }
    }

    companion object {
        const val ENDPOINT = "https://papago.apigw.ntruss.com/nmt/v1/translation"

        fun language(code: String) = if (code == "zh") "zh-CN" else code

        fun body(text: String, source: String, target: String): JsonObject = buildJsonObject {
            put("source", language(source))
            put("target", language(target))
            put("text", text)
        }

        fun parse(body: String): String = try {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonObject?.get("result")?.jsonObject
                ?.get("translatedText")?.jsonPrimitive?.contentOrNull
        } catch (error: IllegalArgumentException) {
            throw TranslationException.Failed("Unreadable Papago reply", error)
        } ?: throw TranslationException.Failed("Papago returned no translation")

        fun error(code: Int, body: String): TranslationException {
            val message = runCatching {
                val root = json.parseToJsonElement(body).jsonObject
                (root["error"]?.jsonObject ?: root)["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "HTTP $code"
            return when (code) {
                401, 403 -> TranslationException.InvalidKey(message)
                429 -> TranslationException.QuotaExceeded(message)
                in 500..599 -> TranslationException.Unavailable(message)
                else -> TranslationException.Failed(message)
            }
        }
    }
}

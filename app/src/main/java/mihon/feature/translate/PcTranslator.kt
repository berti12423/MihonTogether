package mihon.feature.translate

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Where the Mihon Together Translator's phone port listens on the home network. */
data class PcAddress(val host: String, val port: Int = DEFAULT_PORT) {
    /** "192.168.1.20:47824", the form users type and the app stores. */
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    fun url(path: String): HttpUrl.Builder = HttpUrl.Builder().scheme("http").host(host).port(port).encodedPath(path)

    companion object {
        const val DEFAULT_PORT = 47824

        /**
         * Reads what the user typed: "IP" or "IP:port" (IPv4, a host name, or "[IPv6]:port"), with an optional
         * "http://" in front. Returns null for anything else.
         */
        fun parse(input: String): PcAddress? {
            val text = input.trim().removePrefix("http://").removePrefix("HTTP://").trimEnd('/')
            if (text.isEmpty() || text.any { it.isWhitespace() || it == '/' || it == '@' }) return null
            val host: String
            val portText: String?
            if (text.startsWith("[")) {
                val end = text.indexOf(']')
                if (end < 0) return null
                host = text.substring(1, end)
                val rest = text.substring(end + 1)
                portText = when {
                    rest.isEmpty() -> null
                    rest.startsWith(":") -> rest.drop(1)
                    else -> return null
                }
                if (!isIpv6(host)) return null
            } else {
                val parts = text.split(':')
                if (parts.size > 2) return null
                host = parts[0]
                portText = parts.getOrNull(1)
                if (!isIpv4OrHostName(host)) return null
            }
            val port = if (portText == null) DEFAULT_PORT else portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return PcAddress(host.lowercase(), port)
        }

        private fun isIpv4OrHostName(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            if (host.all { it.isDigit() || it == '.' }) {
                val octets = host.split('.')
                return octets.size == 4 && octets.all { it.isNotEmpty() && it.length <= 3 && it.toInt() in 0..255 }
            }
            return host.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
                    label.all { (it.isLetterOrDigit() && it.code < 128) || it == '-' }
            }
        }

        private fun isIpv6(host: String): Boolean =
            host.count { it == ':' } >= 2 && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }
}

/** `GET /health` of the translator (docs/KOHARU_TRANSLATION_PLAN.md, Translator API v1, phone port). */
data class PcHealth(
    val state: String,
    val version: String? = null,
    val progressLabel: String? = null,
    val error: String? = null,
    /** True when the token sent was valid, false when it wasn't, null when no token was sent. */
    val paired: Boolean? = null,
) {
    val ready: Boolean get() = state == "ready"

    /** Still starting, downloading or loading its models. */
    val loading: Boolean get() = state == "starting" || state == "downloading" || state == "loading"
}

sealed interface PcPairResult {
    data class Paired(val token: String, val name: String) : PcPairResult

    /** The 6-digit code was wrong (403 bad_code). */
    data object BadCode : PcPairResult

    /** Too many wrong codes: the translator refuses pairing for a minute (429 locked). */
    data object Locked : PcPairResult
}

/**
 * Talks to the Mihon Together Translator on the user's own PC over the home Wi-Fi (plain HTTP on the LAN, the token is
 * a bearer secret for that PC only). Failures come back as [TranslationException]s: [UNREACHABLE] and [NOT_READY]
 * as [TranslationException.Unavailable] codes, a forgotten token as [TranslationException.InvalidKey], and everything
 * else as [TranslationException.Failed].
 */
class PcTranslator(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun health(address: PcAddress, token: String? = null): PcHealth {
        val request = Request.Builder().url(address.url("/health").build()).get()
        token?.takeIf { it.isNotBlank() }?.let { request.header("Authorization", "Bearer $it") }
        return execute(request.build()) { response, text ->
            if (!response.isSuccessful) throw httpError(response.code, text)
            val body = parse(text) ?: throw TranslationException.Failed("The translator sent an unreadable reply")
            PcHealth(
                state = body.string("state") ?: "error",
                version = body.string("version"),
                progressLabel = (body["progress"] as? JsonObject)?.string("label"),
                error = body.string("error"),
                paired = runCatching { body["paired"]?.jsonPrimitive?.booleanOrNull }.getOrNull(),
            )
        }
    }

    suspend fun pair(address: PcAddress, code: String, deviceName: String): PcPairResult {
        val body = buildJsonObject {
            put("code", code)
            put("device", cleanDeviceName(deviceName))
        }.toString().toRequestBody(JSON_MEDIA)
        val request = Request.Builder().url(address.url("/pair").build()).post(body).build()
        return execute(request) { response, text ->
            when (response.code) {
                200 -> {
                    val reply = parse(text)
                    val token = reply?.string("token")?.takeIf { it.isNotBlank() }
                        ?: throw TranslationException.Failed("The translator sent no token")
                    PcPairResult.Paired(token, reply.string("name")?.takeIf { it.isNotBlank() } ?: address.host)
                }
                // 403 is also how the phone port refuses browsers (forbidden_origin), which isn't a wrong code.
                403 -> if (parse(text)?.string("error") == "forbidden_origin") {
                    throw httpError(response.code, text)
                } else {
                    PcPairResult.BadCode
                }
                429 -> PcPairResult.Locked
                else -> throw httpError(response.code, text)
            }
        }
    }

    /** The translated page as PNG bytes, at the original's pixel size. */
    suspend fun translate(
        address: PcAddress,
        token: String,
        image: ByteArray,
        contentType: String,
        target: String,
    ): ByteArray {
        if (token.isBlank()) throw TranslationException.InvalidKey("Pair again")
        val request = Request.Builder()
            .url(address.url("/translate").addQueryParameter("target", bridgeTarget(target)).build())
            .header("Authorization", "Bearer $token")
            .post(image.toRequestBody(contentType.toMediaType()))
            .build()
        val call = client.newCall(request)
        val response = try {
            call.await()
        } catch (error: IOException) {
            throw unreachable(error)
        }
        return response.use {
            if (it.isSuccessful) {
                try {
                    it.body.bytes()
                } catch (error: IOException) {
                    throw unreachable(error)
                }
            } else {
                throw httpError(it.code, runCatching { it.body.string() }.getOrDefault(""))
            }
        }
    }

    /** Asks the PC to forget this phone. False when the PC couldn't be reached (the phone forgets it anyway). */
    suspend fun unpair(address: PcAddress, token: String): Boolean = try {
        val request = Request.Builder().url(address.url("/pair").build())
            .header("Authorization", "Bearer $token").delete().build()
        execute(request) { response, _ -> response.isSuccessful }
    } catch (error: CancellationException) {
        throw error
    } catch (_: TranslationException) {
        false
    }

    private suspend fun <T> execute(request: Request, read: (Response, String) -> T): T {
        val response = try {
            client.newCall(request).await()
        } catch (error: IOException) {
            throw unreachable(error)
        }
        val text = try {
            response.use { it.body.string() }
        } catch (error: IOException) {
            throw unreachable(error)
        }
        return read(response, text)
    }

    private fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun JsonObject.string(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun httpError(code: Int, text: String): TranslationException {
        val reply = parse(text)
        val error = reply?.string("error")
        val message = reply?.string("message") ?: error ?: "HTTP $code"
        return when {
            code == 401 -> TranslationException.InvalidKey("Pair again")
            code == 503 || error == NOT_READY ->
                TranslationException.Unavailable("Your PC is still loading the models", NOT_READY)
            else -> TranslationException.Failed("PC translator: $message (HTTP $code)")
        }
    }

    private fun unreachable(error: IOException) = TranslationException.Unavailable(
        "Your PC translator isn't reachable. Start it on your PC (same Wi-Fi). (${error.message})",
        UNREACHABLE,
    )

    companion object {
        const val UNREACHABLE = "unreachable"
        const val NOT_READY = "not_ready"
        private const val MAX_DEVICE_NAME = 60
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * A client for the home network, built from the app's client (its interceptors and data meter): no proxy, no
         * DNS over HTTPS (it can't resolve LAN names), no HTTP cache, a quick connect timeout and a long read timeout
         * (a cold PC takes a while for the first page).
         */
        fun lanClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns(Dns.SYSTEM)
            .cache(null)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

        /** The translator wants 1 to 60 characters without control characters (else 400 bad_device). */
        fun cleanDeviceName(name: String): String =
            name.filterNot { it.isISOControl() }.trim().take(MAX_DEVICE_NAME).trim().ifEmpty { "Android phone" }

        /** The translator takes BCP 47 tags such as "en-US"; the app stores "en". */
        fun bridgeTarget(language: String): String = when (language.lowercase()) {
            "", "en" -> "en-US"
            else -> language
        }

        /** The image type from its first bytes (the translator accepts JPEG, PNG, WebP, GIF and AVIF). */
        fun contentType(image: ByteArray): String {
            fun at(offset: Int, vararg bytes: Int) =
                image.size >= offset + bytes.size && bytes.indices.all { image[offset + it] == bytes[it].toByte() }
            return when {
                at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
                at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
                at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
                at(0, 0x47, 0x49, 0x46, 0x38) -> "image/gif"
                at(4, 0x66, 0x74, 0x79, 0x70) && (at(8, 0x61, 0x76, 0x69, 0x66) || at(8, 0x61, 0x76, 0x69, 0x73)) ->
                    "image/avif"
                else -> "application/octet-stream"
            }
        }
    }
}

/** The engine for "My PC (Koharu)": the whole page goes to the paired PC, a finished PNG comes back. */
class PcTranslationEngine(
    private val translator: PcTranslator,
    address: PcAddress,
    private val token: String,
    private val pcName: String,
    /** Finds the paired PC again by its name on this Wi-Fi (its IP may have changed); null when not found. */
    private val relocate: suspend (name: String) -> PcAddress?,
    /** Remembers the new address [relocate] found. */
    private val onRelocated: (PcAddress) -> Unit,
    /** Re-encodes a page the translator can't decode (AVIF, unknown) as PNG; null when it can't be read either. */
    private val toPng: (ByteArray) -> ByteArray? = { null },
) : PageRenderingEngine {
    override val id = ID

    @Volatile
    private var address = address

    override suspend fun renderPage(image: ByteArray, targetLanguage: String): ByteArray {
        var page = image
        var type = PcTranslator.contentType(image)
        if (type !in DECODED_BY_TRANSLATOR) {
            page = toPng(image) ?: throw TranslationException.Failed("This page's image format can't be sent")
            type = "image/png"
        }
        return send(page, type, targetLanguage)
    }

    private suspend fun send(image: ByteArray, type: String, targetLanguage: String): ByteArray {
        return try {
            translator.translate(address, token, image, type, targetLanguage)
        } catch (error: TranslationException.Unavailable) {
            if (error.code != PcTranslator.UNREACHABLE || pcName.isBlank()) throw error
            val moved = relocate(pcName)?.takeIf { it != address } ?: throw error
            address = moved
            onRelocated(moved)
            translator.translate(moved, token, image, type, targetLanguage)
        }
    }

    companion object {
        const val ID = "pc-koharu"

        /** The translator has no AVIF decoder (docs/IMPLEMENTATION_CHECKPOINT.md), so AVIF goes as PNG. */
        private val DECODED_BY_TRANSLATOR = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}

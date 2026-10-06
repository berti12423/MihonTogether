package mihon.feature.social

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.math.BigInteger

internal class SocialCallableFailure(val status: String) : IOException("Social callable failed: $status")

/**
 * Firebase's https.onCall wire format, including the reserved protobuf integer wrappers.
 * Protocol: https://firebase.google.com/docs/functions/callable-reference
 */
internal object SocialCallableProtocol {
    private const val INT64 = "type.googleapis.com/google.protobuf.Int64Value"
    private const val UINT64 = "type.googleapis.com/google.protobuf.UInt64Value"
    private val maximumUnsigned = BigInteger("18446744073709551615")
    private val errorStatuses = setOf(
        "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", "DEADLINE_EXCEEDED", "NOT_FOUND", "ALREADY_EXISTS",
        "PERMISSION_DENIED", "RESOURCE_EXHAUSTED", "FAILED_PRECONDITION", "ABORTED", "OUT_OF_RANGE",
        "UNIMPLEMENTED", "INTERNAL", "UNAVAILABLE", "DATA_LOSS", "UNAUTHENTICATED",
    )

    fun encodeRequest(data: Map<String, Any?>): String = JsonObject(mapOf("data" to encode(data))).toString()

    fun decodeResponse(httpStatus: Int, body: String): Map<String, Any?> {
        val envelope = try {
            Json.parseToJsonElement(body) as? JsonObject ?: throw SocialCallableFailure("INTERNAL")
        } catch (_: IllegalArgumentException) {
            throw SocialCallableFailure("INTERNAL")
        }
        if ("error" in envelope) {
            val error = envelope["error"] as? JsonObject
            val status = (error?.get("status") as? JsonPrimitive)?.content
            throw SocialCallableFailure(status?.takeIf { it in errorStatuses } ?: "INTERNAL")
        }
        if (httpStatus !in 200..299) throw SocialCallableFailure("INTERNAL")
        val result = envelope["result"] ?: envelope["data"] ?: throw SocialCallableFailure("INTERNAL")
        val decoded = decode(result)
        @Suppress("UNCHECKED_CAST")
        return decoded as? Map<String, Any?> ?: throw SocialCallableFailure("INTERNAL")
    }

    private fun encode(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Byte -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonObject(mapOf("@type" to JsonPrimitive(INT64), "value" to JsonPrimitive(value.toString())))
        is Float -> {
            require(value.isFinite())
            JsonPrimitive(value)
        }
        is Double -> {
            require(value.isFinite())
            JsonPrimitive(value)
        }
        is Map<*, *> -> JsonObject(
            value.entries.associate { (key, item) ->
                require(key is String && key != "@type")
                key to encode(item)
            },
        )
        is Iterable<*> -> JsonArray(value.map(::encode))
        else -> throw IllegalArgumentException("Unsupported callable value type")
    }

    private fun decode(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonArray -> value.map(::decode)
        is JsonObject -> when ((value["@type"] as? JsonPrimitive)?.content) {
            INT64 -> (value["value"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: throw SocialCallableFailure("INTERNAL")
            UINT64 -> {
                val integer = (value["value"] as? JsonPrimitive)?.content?.toBigIntegerOrNull()
                    ?: throw SocialCallableFailure("INTERNAL")
                if (integer.signum() < 0 || integer > maximumUnsigned) throw SocialCallableFailure("INTERNAL")
                if (integer.bitLength() <= 63) integer.toLong() else integer
            }
            else -> value.mapValues { decode(it.value) }
        }
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.booleanOrNull
            value.longOrNull != null -> value.longOrNull
            value.doubleOrNull?.isFinite() == true -> value.doubleOrNull
            else -> throw SocialCallableFailure("INTERNAL")
        }
    }
}

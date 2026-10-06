package mihon.feature.social

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger

class SocialCallableProtocolTest {
    @Test
    fun `request keeps nested values and wraps longs without precision loss`() {
        val encoded = Json.parseToJsonElement(
            SocialCallableProtocol.encodeRequest(
                mapOf(
                    "sequence" to Long.MAX_VALUE,
                    "generation" to Long.MIN_VALUE,
                    "content" to mapOf("title" to "A \"quoted\" title", "missing" to null),
                    "members" to listOf("friend", null, true, mapOf("page" to 3, "visible" to false)),
                ),
            ),
        ).jsonObject

        assertEquals(setOf("data"), encoded.keys, "Callable request permits only its data envelope")
        assertEquals(
            Json.parseToJsonElement(
                """
                {
                  "sequence": {
                    "@type": "type.googleapis.com/google.protobuf.Int64Value",
                    "value": "9223372036854775807"
                  },
                  "generation": {
                    "@type": "type.googleapis.com/google.protobuf.Int64Value",
                    "value": "-9223372036854775808"
                  },
                  "content": {"title": "A \"quoted\" title", "missing": null},
                  "members": ["friend", null, true, {"page": 3, "visible": false}]
                }
                """.trimIndent(),
            ),
            encoded["data"],
        )
    }

    @Test
    fun `response retains signed and unsigned 64 bit integers in nested values`() {
        val decoded = SocialCallableProtocol.decodeResponse(
            200,
            """
            {"result": {
              "signed": {"@type": "type.googleapis.com/google.protobuf.Int64Value", "value": "-9223372036854775808"},
              "unsigned": {"@type": "type.googleapis.com/google.protobuf.UInt64Value", "value": "18446744073709551615"},
              "nested": [{"@type": "type.googleapis.com/google.protobuf.UInt64Value", "value": "9223372036854775807"}]
            }}
            """.trimIndent(),
        )

        assertEquals(Long.MIN_VALUE, decoded["signed"])
        assertEquals(BigInteger("18446744073709551615"), decoded["unsigned"])
        assertEquals(listOf(Long.MAX_VALUE), decoded["nested"])
    }

    @Test
    fun `result and legacy data envelopes preserve nested null booleans and maps`() {
        val payload = """{"active":true,"missing":null,"items":[false,null,{"title":"reader"}]}"""
        val expected = mapOf(
            "active" to true,
            "missing" to null,
            "items" to listOf(false, null, mapOf("title" to "reader")),
        )

        assertEquals(expected, SocialCallableProtocol.decodeResponse(200, "{\"result\":$payload}"))
        assertEquals(expected, SocialCallableProtocol.decodeResponse(200, "{\"data\":$payload}"))
    }

    @Test
    fun `unknown typed maps remain maps for forward compatible responses`() {
        val decoded = SocialCallableProtocol.decodeResponse(
            200,
            """{"result":{"future":{"@type":"example.test/NewType","value":"opaque"}}}""",
        )
        assertEquals(mapOf("@type" to "example.test/NewType", "value" to "opaque"), decoded["future"])
    }

    @Test
    fun `error wins over success payload even for http 200`() {
        val failure = assertThrows(SocialCallableFailure::class.java) {
            SocialCallableProtocol.decodeResponse(
                200,
                """{"result":{"ok":true},"error":{"status":"PERMISSION_DENIED","message":"private"}}""",
            )
        }

        assertEquals("PERMISSION_DENIED", failure.status)
    }

    @Test
    fun `missing unknown and ok error statuses still report failure`() {
        listOf("{}", """{"status":"NEW_STATUS"}""", """{"status":"OK"}""")
            .forEach { error ->
                val failure = assertThrows(SocialCallableFailure::class.java) {
                    SocialCallableProtocol.decodeResponse(200, "{\"result\":{},\"error\":$error}")
                }
                assertEquals("INTERNAL", failure.status)
            }
    }

    @Test
    fun `non success http response cannot be treated as a successful result`() {
        assertThrows(SocialCallableFailure::class.java) {
            SocialCallableProtocol.decodeResponse(503, """{"result":{"ok":true}}""")
        }
    }

    @Test
    fun `malformed missing and non map results fail closed`() {
        listOf("not json", "[]", "{}", """{"unrelated":true}""", """{"result":null}""", """{"result":[]} """)
            .forEach { body ->
                val failure = assertThrows(SocialCallableFailure::class.java) {
                    SocialCallableProtocol.decodeResponse(200, body)
                }
                assertEquals("INTERNAL", failure.status, body)
            }
    }

    @Test
    fun `typed integers with invalid values or exceeded bounds fail closed`() {
        listOf(
            """{"@type":"type.googleapis.com/google.protobuf.Int64Value","value":"not an integer"}""",
            """{"@type":"type.googleapis.com/google.protobuf.Int64Value","value":"9223372036854775808"}""",
            """{"@type":"type.googleapis.com/google.protobuf.UInt64Value","value":"-1"}""",
            """{"@type":"type.googleapis.com/google.protobuf.UInt64Value","value":"18446744073709551616"}""",
            """{"@type":"type.googleapis.com/google.protobuf.UInt64Value"}""",
        ).forEach { integer ->
            val failure = assertThrows(SocialCallableFailure::class.java) {
                SocialCallableProtocol.decodeResponse(200, "{\"result\":{\"sequence\":$integer}}")
            }
            assertEquals("INTERNAL", failure.status)
        }
    }

    @Test
    fun `request rejects reserved wrapper keys non string keys and unsupported runtime values`() {
        listOf<Map<String, Any?>>(
            mapOf("entry" to mapOf("@type" to "type.googleapis.com/google.protobuf.Int64Value", "value" to "1")),
            mapOf("entry" to mapOf(1 to "invalid key")),
            mapOf("entry" to Any()),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                SocialCallableProtocol.encodeRequest(invalid)
            }
        }
    }

    @Test
    fun `request rejects non finite float and double values`() {
        listOf<Number>(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                SocialCallableProtocol.encodeRequest(mapOf("position" to listOf(mapOf("offset" to invalid))))
            }
        }
    }
}

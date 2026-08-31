package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderHeadersTest {
    @Test
    fun emptyObjectParsesToEmptyMap() {
        assertEquals(emptyMap<String, String>(), ProviderHeaders.parse("{}"))
    }

    @Test
    fun namesAreLowercasedAndValuesTrimmed() {
        assertEquals(mapOf("x-org" to "acme"), ProviderHeaders.parse("""{"X-Org":"  acme  "}"""))
    }

    @Test
    fun storageEncodingIsCanonicalAndRoundTrips() {
        val headers = mapOf("x-zeta" to "z", "x-alpha" to "a b")
        val encoded = ProviderHeaders.toStorageString(headers)
        assertEquals("""{"x-alpha":"a b","x-zeta":"z"}""", encoded)
        assertEquals(headers, ProviderHeaders.parse(encoded))
        // Deterministic.
        assertEquals(encoded, ProviderHeaders.toStorageString(headers))
    }

    @Test
    fun parseRejectsMalformedInput() {
        val bad =
            listOf(
                "", // blank
                "   ",
                "[{}]", // not an object
                "\"x\"", // not an object
                "1", // not an object
                "{", // truncated
                """{"a":1}""", // value not a string
                """{"a":null}""",
                """{"a":"x","b":true}""",
            )
        bad.forEach { raw ->
            assertThrows<IllegalArgumentException>("headers parsed but must be rejected: $raw") {
                ProviderHeaders.parse(raw)
            }
        }
    }

    @Test
    fun parseRejectsCredentialLikeHeaderNames() {
        val names =
            listOf(
                "Authorization",
                "authorization",
                "X-API-KEY",
                "Cookie",
                "Set-Cookie",
                "x-token",
                "X-Credential",
                "Password",
                "X-Secret-Key",
                "WWW-Authenticate",
                // Documented conservative false positive: substring "key" over-approximates.
                "X-Keyboard-Layout",
            )
        names.forEach { name ->
            assertThrows<IllegalArgumentException>("credential-like header accepted: $name") {
                ProviderHeaders.parse("""{"$name":"v"}""")
            }
        }
    }

    @Test
    fun parseRejectsTransportManagedAndProxyHeaders() {
        val names =
            listOf(
                "Host",
                "Connection",
                "Upgrade",
                "Content-Length",
                "Transfer-Encoding",
                "Expect",
                "TE",
                "Trailer",
                "Proxy-Authorization",
                "Proxy-Authenticate",
                "proxy-anything",
            )
        names.forEach { name ->
            assertThrows<IllegalArgumentException>("transport header accepted: $name") {
                ProviderHeaders.parse("""{"$name":"v"}""")
            }
        }
    }

    @Test
    fun parseRejectsUnsafeNamesAndValues() {
        val cases =
            listOf(
                """{"X Bad":"v"}""", // space in name
                """{"":"v"}""", // empty name
                """{"${"X-".padEnd(130, 'a')}":"v"}""", // name too long
                """{"X-A":""}""", // empty value
                """{"X-A":"   "}""", // blank value
                """{"X-A":"${"x".repeat(ProviderHeaders.MAX_VALUE_LENGTH + 1)}"}""", // value too long
                """{"X-A":"x\r\nX-Evil: 1"}""", // CRLF injection
                """{"X-A":"x\ny"}""",
                """{"X-A":"a\u0001b"}""", // control character
                """{"X-A":"a\u007fb"}""", // DEL
                """{"X-A":"v","x-a":"w"}""", // case-insensitive duplicate
            )
        cases.forEach { raw ->
            assertThrows<IllegalArgumentException>("headers parsed but must be rejected: $raw") {
                ProviderHeaders.parse(raw)
            }
        }
    }

    @Test
    fun parseEnforcesCountAndTotalSizeCaps() {
        fun headers(n: Int): String = (1..n).joinToString(",") { "\"x-h$it\":\"v\"" }
        assertThrows<IllegalArgumentException>("too many headers accepted") {
            ProviderHeaders.parse("{${headers(ProviderHeaders.MAX_HEADERS + 1)}}")
        }
        // Exactly the cap is legal.
        assertEquals(
            ProviderHeaders.MAX_HEADERS,
            ProviderHeaders.parse("{${headers(ProviderHeaders.MAX_HEADERS)}}").size,
        )

        // Total size cap: 8 max-length values exceed 4096 chars, 7 do not.
        fun sized(n: Int): String =
            (1..n).joinToString(",") { "\"x-$it\":\"${"v".repeat(ProviderHeaders.MAX_VALUE_LENGTH)}\"" }
        assertThrows<IllegalArgumentException>("total size cap not enforced") {
            ProviderHeaders.parse("{${sized(8)}}")
        }
        assertEquals(7, ProviderHeaders.parse("{${sized(7)}}").size)
    }
}

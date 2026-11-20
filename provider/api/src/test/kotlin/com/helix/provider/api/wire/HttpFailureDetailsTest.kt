package com.helix.provider.api.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpFailureDetailsTest {
    @Test fun keepsActionableFieldsAndHonorsLongCooldown() {
        val result =
            httpFailureDetails(
                429,
                mapOf("X-Request-ID" to listOf("req_123"), "Retry-After" to listOf("900")),
                """{"detail":"Daily quota exhausted","audio":"not retained"}""",
            )
        assertEquals("Daily quota exhausted", result.detail)
        assertEquals("req_123", result.requestId)
        assertEquals(900000L, result.retryAfterMillis)
    }

    @Test fun rejectsHeaderInjectionAndIgnoresHtml() {
        val result = httpFailureDetails(403, mapOf("x-request-id" to listOf("bad\nvalue")), "<html>secret</html>")
        assertNull(result.requestId)
        assertNull(result.detail)
    }

    @Test fun diagnosticHeadersPreserveLogicalRequestAndNeverAddForwardingIdentity() {
        val first = RequestDiagnostics.headers(mapOf("Authorization" to "Bearer test"))
        val retry = RequestDiagnostics.headers(first)
        assertEquals(first["X-Client-Request-ID"], retry["X-Client-Request-ID"])
        assertNotEquals(first["X-Client-Request-ID"], RequestDiagnostics.headers(emptyMap())["X-Client-Request-ID"])
        assertEquals("Bearer test", first["Authorization"])
        assertFalse(first.keys.any { it.startsWith("CF-", true) || it.equals("X-Forwarded-For", true) })
    }
}

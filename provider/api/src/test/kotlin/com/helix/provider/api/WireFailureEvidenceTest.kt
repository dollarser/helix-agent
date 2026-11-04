package com.helix.provider.api

import com.helix.core.model.ModelFailureDiagnostic.ErrorBodyState
import com.helix.core.model.ModelFailureDiagnostic.Reason
import com.helix.core.model.ModelFailureDiagnostic.ServerError
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class WireFailureEvidenceTest {
    @Test fun selectedErrorFieldsAreSanitizedWithoutRetainingTheOtherBodyFields() =
        runBlocking {
            val evidence = WireFailureEvidence()
            evidence.requestHeaders(mapOf("x-api-key" to "custom-secret"))
            evidence.response =
                response(
                    """{"error":{"type":"BadRequestError","param":"max_tokens",
                        "message":"Maximum context length is 32768; custom-secret",
                        "debug":"UNSELECTED_BODY_SENTINEL"}}""",
                )
            val failure = evidence.httpError()
            assertEquals("max_tokens", failure.diagnostic?.errorParameter)
            assertTrue(
                failure.diagnostic
                    ?.serverMessage
                    .orEmpty()
                    .contains("32768"),
            )
            assertFalse(failure.toString().contains("custom-secret"))
            assertFalse(failure.toString().contains("UNSELECTED_BODY_SENTINEL"))
        }

    private fun response(text: String) =
        WireResponse(
            400,
            mapOf(
                "Content-Type" to listOf("application/json; charset=utf-8"),
                "X-Request-Id" to listOf("PRIVATE_HEADER_SENTINEL"),
                "Set-Cookie" to listOf("PRIVATE_COOKIE"),
            ),
            object : WireBody {
                override suspend fun bytes() = error("Must read a bounded prefix")

                override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                    onChunk(text.toByteArray())
                }

                override fun close() = Unit
            },
        )

    @Test fun keepsKnownErrorCategoryAndCountersWithoutContentOrHeaders() =
        runBlocking {
            val evidence = WireFailureEvidence()
            val text = """{"error":{"code":"context_length_exceeded","message":"PRIVATE_BODY_SENTINEL"}}"""
            evidence.response = response(text)
            val result = evidence.httpFailure(false)
            assertEquals(400, result.httpStatus)
            assertEquals(ServerError.CONTEXT_LENGTH_EXCEEDED, result.serverError)
            assertEquals(ErrorBodyState.JSON, result.errorBodyState)
            assertEquals(text.toByteArray().size.toLong(), result.receivedBytes)
            assertEquals(64, result.requestIdHash!!.length)
            assertFalse(result.toString().contains("PRIVATE"))
            assertFalse(result.toString().contains("Set-Cookie"))
        }

    @Test fun hugeErrorStopsAtPrefixAndUnknownCodeNeverBecomesLogText() =
        runBlocking {
            val evidence = WireFailureEvidence()
            evidence.response = response("x".repeat(100_000))
            assertEquals(ErrorBodyState.TRUNCATED, evidence.httpFailure(false).errorBodyState)
            evidence.response = response("""{"error":{"code":"PRIVATE_BODY_SENTINEL"}}""")
            val result = evidence.httpFailure(false)
            assertEquals(ServerError.UNKNOWN, result.serverError)
            assertFalse(result.toString().contains("PRIVATE_BODY_SENTINEL"))
        }

    @Test fun missingTerminalMalformedJsonAndNetworkClassesStayDistinct() {
        assertEquals(Reason.MISSING_TERMINAL, WireFailureEvidence.protocolReason("missing terminal"))
        assertEquals(Reason.INVALID_JSON, WireFailureEvidence.protocolReason("json: SerializationException"))
        assertEquals(Reason.INVALID_SSE, WireFailureEvidence.protocolReason("sse: invalid encoding"))
        assertEquals(Reason.DNS, WireFailureEvidence.reason(UnknownHostException("PRIVATE_HOST")))
        assertEquals(Reason.TLS, WireFailureEvidence.reason(SSLHandshakeException("PRIVATE_CERT")))
    }

    @Test fun retryAfterIsParsedAndBoundedWithoutLoggingItsRawValue() =
        runBlocking {
            val evidence = WireFailureEvidence()
            evidence.response = WireResponse(429, mapOf("Retry-After" to listOf("30")), response("").body)
            assertEquals(30_000L, evidence.httpFailure(true).retryAfterMillis)
            evidence.response = WireResponse(429, mapOf("retry-after" to listOf("999999999")), response("").body)
            assertEquals(300_000L, evidence.httpFailure(true).retryAfterMillis)
        }

    @Test fun diagnosticReadFailureDoesNotReplaceTheOriginalHttpStatus() =
        runBlocking {
            val evidence = WireFailureEvidence()
            evidence.response =
                WireResponse(
                    401,
                    emptyMap(),
                    object : WireBody {
                        override suspend fun bytes(): ByteArray = error("unused")

                        override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean): Unit =
                            throw java.io.IOException("PRIVATE_BODY")

                        override fun close() = Unit
                    },
                )
            val diagnostic = evidence.httpFailure(false)
            assertEquals(401, diagnostic.httpStatus)
            assertEquals(ErrorBodyState.READ_FAILED, diagnostic.errorBodyState)
            assertFalse(diagnostic.toString().contains("PRIVATE_BODY"))
        }

    @Test fun tlsAndIdentityFailuresAreNotTransientButDnsAndResetAre() {
        val evidence = WireFailureEvidence()
        val tls =
            evidence.transport(
                SSLHandshakeException("PRIVATE_CERT"),
                com.helix.core.model.ModelFailureDiagnostic.Stage.CONNECT,
            )
        assertFalse(tls.retryable)
        val changed =
            evidence.transport(
                com.helix.provider.api.wire.WireRequestStateException(
                    com.helix.provider.api.wire.WireRequestStateException.Reason.ACCOUNT_CHANGED,
                ),
                com.helix.core.model.ModelFailureDiagnostic.Stage.STREAM,
            )
        assertFalse(changed.retryable)
        assertEquals(Reason.ACCOUNT_CHANGED, changed.diagnostic?.reason)
        assertFalse(changed.toString().contains("PRIVATE"))
        org.junit.Assert.assertTrue(
            evidence
                .transport(
                    UnknownHostException("PRIVATE_HOST"),
                    com.helix.core.model.ModelFailureDiagnostic.Stage.CONNECT,
                ).retryable,
        )
        org.junit.Assert.assertTrue(
            evidence
                .transport(
                    java.net.SocketException("reset"),
                    com.helix.core.model.ModelFailureDiagnostic.Stage.STREAM,
                ).retryable,
        )
    }

    @Test fun quotaAndHtmlFailuresDoNotBecomeTransientNetworkRetries() =
        runBlocking {
            val evidence = WireFailureEvidence()
            evidence.response =
                WireResponse(
                    429,
                    emptyMap(),
                    response(
                        """{"error":{"code":"insufficient_quota"}}""".trimIndent(),
                    ).body,
                )
            val quota = evidence.httpError()
            assertFalse(quota.retryable)
            assertEquals(ServerError.INSUFFICIENT_QUOTA, quota.diagnostic?.serverError)
            evidence.response = WireResponse(200, mapOf("content-type" to listOf("text/html")), response("").body)
            val html =
                evidence.annotate(
                    com.helix.core.model.ModelEvent.Error(
                        com.helix.core.model.ModelErrorCode.PROTOCOL,
                        true,
                    ),
                    "missing terminal",
                )
            assertFalse(html.retryable)
            assertEquals(Reason.INVALID_SCHEMA, html.diagnostic?.reason)
        }
}

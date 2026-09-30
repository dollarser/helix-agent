package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

class AntigravityProtocolTest {
    private val client = AntigravityClientConfig("fixture-client", "fixture-parameter")

    @Test fun missingClientCannotStartAuthorization() {
        for (config in listOf(AntigravityClientConfig("", ""), AntigravityClientConfig("id", ""))) {
            assertThrows(AntigravityClientNotConfigured::class.java) {
                AntigravityOAuthProtocol.attempt(51121, client = config)
            }
        }
        assertFalse(client.toString().contains("fixture-parameter"))
    }

    @Test fun loginUsesDistinctStatePkceAndLoopbackRedirect() {
        val attempt = AntigravityOAuthProtocol.attempt(51121, client = client)
        val url = attempt.authorizeUrl.toHttpUrl()
        assertEquals("accounts.google.com", url.host)
        assertEquals("fixture-client", url.queryParameter("client_id"))
        assertEquals("http://127.0.0.1:51121/oauth-callback", url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        val expected =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(attempt.verifier.toByteArray(Charsets.US_ASCII)),
            )
        assertEquals(expected, url.queryParameter("code_challenge"))
        assertNotEquals(attempt.state, AntigravityOAuthProtocol.attempt(51121, client = client).state)
    }

    @Test fun callbackRejectsMissingWrongOrDuplicateStateAndCode() {
        assertEquals(
            CodexCallbackResult.Code("value"),
            AntigravityOAuthProtocol.callback("/oauth-callback?state=expected&code=value", "expected"),
        )
        listOf(
            "/oauth-callback?state=wrong&code=value",
            "/other?state=expected&code=value",
            "/oauth-callback?state=expected&state=expected&code=value",
        ).forEach {
            assertEquals(CodexCallbackResult.Ignored, AntigravityOAuthProtocol.callback(it, "expected"))
        }
        assertTrue(
            AntigravityOAuthProtocol.callback(
                "/oauth-callback?state=expected&code=a&code=b",
                "expected",
            ) is CodexCallbackResult.Rejected,
        )
    }

    @Test fun refreshPreservesProjectAndRequiresAnExpiry() {
        val old = CliSubscriptionSession("old", "refresh", null, 1000, "project")
        val next = AntigravityOAuthProtocol.session(json("""{"access_token":"new","expires_in":60}"""), 100, old)
        assertEquals("project", next.accountId)
        assertEquals("refresh", next.refreshToken)
        assertEquals(60100L, next.expiresAtEpochMillis)
        assertThrows(IllegalArgumentException::class.java) {
            AntigravityOAuthProtocol.session(json("""{"access_token":"new"}"""), 100, old)
        }
    }

    @Test fun projectDiscoveryDoesNotGuessOrEnrollAnIneligibleAccount() {
        assertEquals("p", AntigravityOAuthProtocol.project(json("""{"cloudaicompanionProject":"p"}""")))
        assertEquals("p", AntigravityOAuthProtocol.project(json("""{"cloudaicompanionProject":{"id":"p"}}""")))
        assertThrows(AntigravityOnboardingRequired::class.java) { AntigravityOAuthProtocol.project(json("{}")) }
    }

    @Test fun catalogUnknownCapabilitiesAndLimitsStayUnknown() {
        val result =
            AntigravitySubscriptionModel.decodeCatalog(
                json("""{"models":{"a":{},"b":{"inputTokenLimit":8192}}}"""),
            )
        assertNull(result.models.first().contextWindow)
        assertNull(result.models.first().vision)
        assertEquals(8192L, result.models.last().contextWindow)
        assertThrows(IllegalArgumentException::class.java) {
            AntigravitySubscriptionModel.decodeCatalog(json("""{"models":{}}"""))
        }
    }

    @Test fun unknownFinishOrTruncationNeverPublishesToolCalls() {
        val parts = """[{"functionCall":{"name":"unexpected","args":{}}}]"""
        listOf("MAX_TOKENS", "UNKNOWN").forEach { reason ->
            val result = AntigravityResponse.decode(response(parts, reason), emptyMap())
            assertFalse(result.events.any { it is ModelEvent.ToolCallStarted })
            val terminal = result.events.last()
            assertTrue(terminal is ModelEvent.Error || terminal is ModelEvent.Completed)
        }
    }

    @Test fun unofferedToolNamesAndMalformedArgumentsAreRejected() {
        listOf(
            """[{"functionCall":{"name":"unknown","args":{}}}]""",
            """[{"functionCall":{"name":"allowed","args":[]}}]""",
        ).forEach { parts ->
            val decoded = AntigravityResponse.decode(response(parts), mapOf("allowed" to "files.read"))
            assertTrue(decoded.events.last() is ModelEvent.Error)
            assertFalse(decoded.events.any { it is ModelEvent.ToolCallStarted })
        }
    }

    @Test fun signedPartsSurviveReopenButCannotCrossAccountsOrModels() {
        val root = Files.createTempDirectory("antigravity-replay").toFile()
        try {
            val wireName = AntigravityRequest.wireName("files.read")
            val parts = """[{"thoughtSignature":"opaque","functionCall":{"name":"$wireName","args":{}}}]"""
            val decoded = AntigravityResponse.decode(response(parts), mapOf(wireName to "files.read"))
            val message = requireNotNull(decoded.assistant)
            val original = requireNotNull(decoded.originalParts)
            AntigravityReplayStore(root).save("model-a", "login-a", message, original)
            val reopened = AntigravityReplayStore(root)
            assertEquals(original, reopened.read("model-a", "login-a", message))
            assertThrows(IllegalArgumentException::class.java) { reopened.read("model-b", "login-a", message) }
            assertThrows(IllegalArgumentException::class.java) { reopened.read("model-a", "login-b", message) }
            assertFalse(original.toString().contains("skip_thought_signature_validator"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun toolResultsUseAnObjectEnvelopeAndPreserveExactName() {
        val call =
            com.helix.core.model.AssistantToolCall(
                com.helix.core.model
                    .ToolCallId("call-1"),
                com.helix.core.model
                    .ToolName("files.read"),
                "{}",
            )
        val request =
            ModelRequest(
                "model",
                listOf(
                    ModelMessage(ModelRole.USER, "Read"),
                    ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)),
                    ModelMessage(ModelRole.TOOL, "[1,2]", toolCallId = call.id, toolName = call.name),
                ),
            )
        val encoded = AntigravityRequest(request, emptyList()) { null }.encode("project")
        val part =
            encoded
                .getValue("request")
                .jsonObject
                .getValue("contents")
                .jsonArray
                .last()
                .jsonObject
                .getValue("parts")
                .jsonArray
                .single()
                .jsonObject
                .getValue("functionResponse")
                .jsonObject
        assertEquals(AntigravityRequest.wireName("files.read"), part.getValue("name").jsonPrimitive.content)
        assertEquals(
            "[1,2]",
            part
                .getValue("response")
                .jsonObject
                .getValue("output")
                .toString(),
        )
    }

    private fun response(
        parts: String,
        finish: String = "STOP",
    ): JsonObject =
        json(
            """{"response":{"candidates":[{"content":{"parts":$parts},"finishReason":"$finish"}]}}""",
        )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}

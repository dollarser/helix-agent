package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CancellationException

/** The real OkHttp request path with an in-process interceptor; no network or real account. */
class AntigravityTransportTest {
    private val client = AntigravityClientConfig("fixture-client", "fixture-parameter")

    @Test fun unconfiguredClientDoesNotSendTokensOrStartRefresh() {
        var requests = 0
        http {
            requests++
            200 to "{}"
        }.use { transport ->
            val auth = AntigravityAuth(transport, AntigravityClientConfig("", ""))
            assertThrows(AntigravityClientNotConfigured::class.java) {
                auth.exchange(AntigravityOAuthProtocol.attempt(51121, client = client), "fixture-code")
            }
            assertThrows(AntigravityClientNotConfigured::class.java) { auth.current(vault()) }
            assertEquals(0, requests)
        }
    }

    @Test fun authorizationFailureIsNotReplayedOrSentToAnotherEndpoint() {
        val requests = mutableListOf<Request>()
        fixture(action = { _, http, model ->
            val result = model.run(ModelRequest("selected-b", listOf(ModelMessage(ModelRole.USER, "test"))))
            assertEquals(ModelErrorCode.AUTH, (result.events.single() as ModelEvent.Error).code)
            http.close()
        }, transport = { request ->
            requests += request
            401 to "{}"
        })
        assertEquals(1, requests.size)
        assertEquals("/v1internal:streamGenerateContent", requests.single().url.encodedPath)
        assertEquals("sse", requests.single().url.queryParameter("alt"))
        assertEquals("Bearer fixture-access", requests.single().header("Authorization"))
    }

    @Test fun cancelledTransportDoesNotStartALateRequest() {
        var requests = 0
        val http =
            http {
                requests++
                200 to "{}"
            }
        http.close()
        assertThrows(CancellationException::class.java) {
            http.call("loadCodeAssist", Json.parseToJsonElement("{}").jsonObject, "fixture")
        }
        assertEquals(0, requests)
    }

    @Test fun selectedModelToolRoundAndSignedHistoryUseOneTarget() {
        val bodies = mutableListOf<String>()
        val name = AntigravityRequest.wireName("files.read")
        fixture(action = { _, _, model ->
            val first =
                ModelRequest(
                    "selected-b",
                    listOf(ModelMessage(ModelRole.USER, "read")),
                    tools = listOf(ModelToolSchema(ToolName("files.read"), "Read a fixture", """{"type":"object"}""")),
                )
            val result = model.run(first)
            val started = result.events.filterIsInstance<ModelEvent.ToolCallStarted>().single()
            val call =
                com.helix.core.model
                    .AssistantToolCall(started.id, ToolName(started.name), "{}")
            val second =
                first.copy(
                    messages =
                        first.messages +
                            listOf(
                                // ChatHistoryBuilder persists visible text separately from its tool step.
                                ModelMessage(ModelRole.ASSISTANT, "Reading now."),
                                ModelMessage(ModelRole.ASSISTANT, "", toolCalls = listOf(call)),
                                ModelMessage(ModelRole.TOOL, "[1,2]", toolCallId = call.id, toolName = call.name),
                            ),
                )
            assertTrue(model.run(second).events.last() is ModelEvent.Completed)
        }, transport = { request ->
            bodies += body(request)
            val part =
                if (bodies.size == 1) {
                    """{"text":"Reading now."},${signedToolPart(name)}"""
                } else {
                    """{"text":"done"}"""
                }
            val data = """{"response":{"candidates":[{"content":{"parts":[$part]},"finishReason":"STOP"}]}}"""
            200 to "data: $data\n\n"
        })
        assertSignedRoundTrip(bodies)
    }

    private fun signedToolPart(name: String): String =
        buildJsonObject {
            put(
                "functionCall",
                buildJsonObject {
                    put("name", name)
                    put("args", buildJsonObject {})
                    put("id", "server-call-1")
                },
            )
            put("thoughtSignature", "server-signature")
        }.toString()

    private fun assertSignedRoundTrip(bodies: List<String>) {
        assertEquals(2, bodies.size)
        bodies.forEach {
            assertEquals(
                "selected-b",
                Json
                    .parseToJsonElement(it)
                    .jsonObject["model"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }
        val second =
            Json
                .parseToJsonElement(bodies.last())
                .jsonObject
                .getValue("request")
                .jsonObject
        assertTrue(
            second
                .getValue("contents")
                .jsonArray[1]
                .toString()
                .contains("server-signature"),
        )
        assertEquals(3, second.getValue("contents").jsonArray.size)
        assertEquals(1, Regex("Reading now\\.").findAll(bodies.last()).count())
        assertFalse(bodies.last().contains("skip_thought_signature_validator"))
        val result =
            second
                .getValue("contents")
                .jsonArray
                .last()
                .jsonObject
                .getValue("parts")
                .jsonArray
                .single()
        assertEquals(
            "server-call-1",
            result.jsonObject
                .getValue("functionResponse")
                .jsonObject["id"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test fun oauthExchangeDiscoversProjectButNeverAutoOnboards() {
        val paths = mutableListOf<String>()
        val http =
            http { request ->
                paths += request.url.encodedPath
                if (paths.size == 1) {
                    200 to """{"access_token":"a","refresh_token":"r","expires_in":3600}"""
                } else {
                    200 to """{"cloudaicompanionProject":{"id":"fixture-project"}}"""
                }
            }
        http.use {
            val session =
                AntigravityAuth(
                    it,
                    client,
                ).exchange(AntigravityOAuthProtocol.attempt(51121, client = client), "synthetic-code")
            assertEquals("fixture-project", session.accountId)
        }
        assertEquals(listOf("/token", "/v1internal:loadCodeAssist"), paths)
    }

    @Test fun lateTokenRefreshCannotOverwriteNewLogin() {
        val vault = vault()
        vault.save(CliSubscriptionProvider.ANTIGRAVITY, CliSubscriptionSession("old", "r", null, 1, "old-project"))
        http { _ ->
            vault.save(
                CliSubscriptionProvider.ANTIGRAVITY,
                CliSubscriptionSession("new-account", "new-refresh", null, Long.MAX_VALUE, "new-project"),
            )
            200 to """{"access_token":"stale-refresh","expires_in":3600}"""
        }.use {
            assertThrows(IllegalStateException::class.java) { AntigravityAuth(it, client).current(vault) }
        }
        assertEquals("new-account", vault.load(CliSubscriptionProvider.ANTIGRAVITY).accessToken)
    }

    private fun fixture(
        action: (CliSubscriptionCredentialVault, AntigravityHttp, AntigravitySubscriptionModel) -> Unit,
        transport: (Request) -> Pair<Int, String>,
    ) {
        val directory = Files.createTempDirectory("antigravity-transport").toFile()
        val vault = vault()
        vault.save(
            CliSubscriptionProvider.ANTIGRAVITY,
            CliSubscriptionSession("fixture-access", "fixture-refresh", null, Long.MAX_VALUE, "fixture-project"),
        )
        try {
            http(transport).use { http ->
                AntigravitySubscriptionModel(
                    vault,
                    AntigravityReplayStore(directory),
                    http = http,
                    client = client,
                ).use {
                    action(vault, http, it)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun http(transport: (Request) -> Pair<Int, String>): AntigravityHttp {
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val (code, text) = transport(chain.request())
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("fixture")
                        .body(text.toResponseBody(CodexSubscriptionModel.JSON))
                        .build()
                }.build()
        return AntigravityHttp(client, "https://example.test", "https://example.test/token")
    }

    private fun body(request: Request): String = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()

    private fun vault() =
        CliSubscriptionCredentialVault(
            object : CliSecretStore {
                private val values = mutableMapOf<String, String>()

                override fun put(
                    name: String,
                    value: String,
                ) {
                    values[name] = value
                }

                override fun get(name: String): String = values.getValue(name)

                override fun delete(name: String) {
                    values.remove(name)
                }

                override fun contains(name: String): Boolean = name in values
            },
        )
}

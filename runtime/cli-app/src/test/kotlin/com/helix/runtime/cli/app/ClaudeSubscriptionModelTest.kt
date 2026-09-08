package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSubscriptionModelTest {
    private val request =
        ModelRequest("claude-test", listOf(ModelMessage(ModelRole.USER, "hello")), maxOutputTokens = 8)

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

                override fun get(name: String) = values.getValue(name)

                override fun delete(name: String) {
                    values.remove(name)
                }

                override fun contains(name: String) = name in values
            },
        )

    private fun session(token: String) = CliSubscriptionSession(token, "fixture-refresh", null, Long.MAX_VALUE)

    @Test fun sendsOnlyClaudeCredentialAndMapsStream() {
        val vault =
            vault().also {
                it.save(CliSubscriptionProvider.CLAUDE, session("claude-fixture"))
                it.save(CliSubscriptionProvider.CODEX, session("codex-fixture"))
            }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    assertEquals(ClaudeSubscriptionModel.URL, chain.request().url.toString())
                    assertEquals("Bearer claude-fixture", chain.request().header("Authorization"))
                    assertNull(chain.request().header("x-api-key"))
                    val buffer = okio.Buffer()
                    chain.request().body!!.writeTo(buffer)
                    assertTrue(buffer.readUtf8().contains("\"max_tokens\":8"))
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(STREAM.toResponseBody())
                        .build()
                }.build()
        ClaudeSubscriptionModel(vault, { error("refresh not expected") }, client).use {
            val events = it.run(request).events
            assertTrue(events.contains(ModelEvent.TextDelta("OK")))
            assertTrue(events.last() is ModelEvent.Completed)
        }
    }

    @Test fun authenticationAndQuotaFailuresAreRedactedAndNeverReplay() {
        for ((status, code) in listOf(
            401 to ModelErrorCode.AUTH,
            403 to ModelErrorCode.AUTH,
            429 to ModelErrorCode.RATE_LIMITED,
            503 to ModelErrorCode.SERVER_ERROR,
        )) {
            var calls = 0
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        calls++
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(status)
                            .message("failure")
                            .body("private-server-detail".toResponseBody())
                            .build()
                    }.build()
            ClaudeSubscriptionModel(
                vault().also {
                    it.save(CliSubscriptionProvider.CLAUDE, session("fixture"))
                },
                { error("no replay") },
                client,
            ).use {
                val result = it.run(request).events.single() as ModelEvent.Error
                assertEquals(code, result.code)
                assertFalse(result.toString().contains("private-server-detail"))
            }
            assertEquals(1, calls)
        }
    }

    @Test fun missingClaudeDoesNotUseCodexAndTruncatedStreamFails() {
        val vault = vault().also { it.save(CliSubscriptionProvider.CODEX, session("codex-only")) }
        val client = OkHttpClient.Builder().addInterceptor { error("must not send") }.build()
        ClaudeSubscriptionModel(vault, {}, client).use {
            assertEquals(ModelErrorCode.AUTH, (it.run(request).events.single() as ModelEvent.Error).code)
        }
        val response =
            Response
                .Builder()
                .request(
                    okhttp3.Request
                        .Builder()
                        .url(ClaudeSubscriptionModel.URL)
                        .build(),
                ).protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody())
                .build()
        response.use {
            assertTrue(
                readSubscriptionEvents(
                    it,
                    com.helix.provider.anthropic
                        .AnthropicStreamDecoder(),
                ).last() is ModelEvent.Error,
            )
        }
    }

    companion object {
        private val STREAM =
            """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"OK"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

            event: message_stop
            data: {"type":"message_stop"}

            """.trimIndent() + "\n\n"
    }
}

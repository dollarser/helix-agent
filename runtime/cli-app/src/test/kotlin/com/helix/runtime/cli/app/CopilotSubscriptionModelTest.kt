package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotSubscriptionModelTest {
    private val request = ModelRequest("auto", listOf(ModelMessage(ModelRole.USER, "hello")), maxOutputTokens = 8)

    private fun vault() =
        CliSubscriptionCredentialVault(
            object : CliSecretStore {
                val data = mutableMapOf<String, String>()

                override fun put(
                    name: String,
                    value: String,
                ) {
                    data[name] = value
                }

                override fun get(name: String) = data.getValue(name)

                override fun delete(name: String) {
                    data.remove(name)
                }

                override fun contains(name: String) = name in data
            },
        )

    private fun session(expiry: Long = Long.MAX_VALUE) =
        CliSubscriptionSession("copilot-fixture", "github-fixture", null, expiry)

    @Test fun bodyPreservesOutputBudget() {
        val body = Json.parseToJsonElement(CopilotSubscriptionModel.encodeRequest(request)).jsonObject
        assertEquals(8, body.getValue("max_completion_tokens").jsonPrimitive.int)
        assertFalse(body.containsKey("max_tokens"))
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
        assertFalse(body.containsKey("tools"))
    }

    @Test fun refreshBeforePostUsesOnlyCopilotAccessToken() {
        val vault = vault().also { it.save(CliSubscriptionProvider.COPILOT, session(1)) }
        var refreshed = false
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    assertTrue(refreshed)
                    assertEquals(CopilotSubscriptionModel.URL, chain.request().url.toString())
                    assertEquals("Bearer copilot-fixture", chain.request().header("Authorization"))
                    Response
                        .Builder()
                        .request(chain.request())
                        .code(200)
                        .message("OK")
                        .protocol(Protocol.HTTP_1_1)
                        .body(
                            (
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"HELIX_OK\"},\"fini" +
                                    "sh_reason\":null}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"fini" +
                                    "sh_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                            ).toResponseBody(),
                        ).build()
                }.build()
        CopilotSubscriptionModel(vault, {
            refreshed = true
            vault.save(CliSubscriptionProvider.COPILOT, session())
        }, client).use {
            assertTrue(it.run(request).events.last() is ModelEvent.Completed)
        }
    }

    @Test fun missingCredentialDoesNotBorrowAnotherPlatform() {
        val vault = vault().also { it.save(CliSubscriptionProvider.CODEX, session()) }
        CopilotSubscriptionModel(vault, {
            error("must not refresh")
        }, OkHttpClient.Builder().addInterceptor { error("must not send") }.build()).use {
            assertEquals(ModelErrorCode.AUTH, (it.run(request).events.single() as ModelEvent.Error).code)
        }
    }

    @Test fun authQuotaAndTruncatedStreamsNeverSucceedOrReplay() {
        for (status in listOf(401, 403, 429, 200)) {
            var calls = 0
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        calls++
                        Response
                            .Builder()
                            .request(chain.request())
                            .code(status)
                            .message("response")
                            .protocol(Protocol.HTTP_1_1)
                            .body("private incomplete response".toResponseBody())
                            .build()
                    }.build()
            CopilotSubscriptionModel(
                vault().also { it.save(CliSubscriptionProvider.COPILOT, session()) },
                {},
                client,
            ).use {
                val events = it.run(request).events
                assertTrue(events.last() is ModelEvent.Error)
                assertFalse(events.any { event -> event is ModelEvent.Completed })
            }
            assertEquals(1, calls)
        }
    }
}

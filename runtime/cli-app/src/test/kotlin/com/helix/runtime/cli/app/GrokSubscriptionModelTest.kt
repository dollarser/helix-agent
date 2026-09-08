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
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokSubscriptionModelTest {
    private val request = ModelRequest("grok-4", listOf(ModelMessage(ModelRole.USER, "hello")), maxOutputTokens = 8)

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
        CliSubscriptionSession("grok-fixture", "refresh-fixture", null, expiry)

    @Test fun boundedResponsesBodyPreservesBudgetAndDisablesStorage() {
        val body = Json.parseToJsonElement(GrokSubscriptionModel.encodeRequest(request)).jsonObject
        assertEquals(false, body.getValue("store").jsonPrimitive.boolean)
        assertEquals(8, body.getValue("max_output_tokens").jsonPrimitive.int)
        assertEquals(true, body.getValue("stream").jsonPrimitive.boolean)
    }

    @Test fun refreshesGrokBeforePostingAndDecodesTerminal() {
        val vault = vault().also { it.save(CliSubscriptionProvider.GROK, session(1)) }
        var refreshed = false
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    assertTrue(refreshed)
                    assertEquals(GrokSubscriptionModel.URL, chain.request().url.toString())
                    assertEquals("Bearer grok-fixture", chain.request().header("Authorization"))
                    Response
                        .Builder()
                        .request(chain.request())
                        .code(200)
                        .message("OK")
                        .protocol(Protocol.HTTP_1_1)
                        .body(
                            (
                                "event: response.completed\ndata: {\"type\":\"response.completed\",\"seq" +
                                    "uence_number\":0,\"response\":{\"id\":\"r\",\"status\":\"completed\",\"usage\"" +
                                    ":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                            ).toResponseBody(),
                        ).build()
                }.build()
        GrokSubscriptionModel(vault, {
            refreshed = true
            vault.save(CliSubscriptionProvider.GROK, session())
        }, client).use {
            assertTrue(it.run(request).events.last() is ModelEvent.Completed)
        }
    }

    @Test fun missingCredentialAndQuotaAreNotSuccess() {
        val empty = vault().also { it.save(CliSubscriptionProvider.CLAUDE, session()) }
        GrokSubscriptionModel(empty, {
            error("must not refresh")
        }, OkHttpClient.Builder().addInterceptor { error("must not send") }.build()).use {
            assertEquals(ModelErrorCode.AUTH, (it.run(request).events.single() as ModelEvent.Error).code)
        }
        var calls = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    calls++
                    Response
                        .Builder()
                        .request(
                            chain.request(),
                        ).code(429)
                        .message("quota")
                        .protocol(Protocol.HTTP_1_1)
                        .body("private".toResponseBody())
                        .build()
                }.build()
        GrokSubscriptionModel(vault().also { it.save(CliSubscriptionProvider.GROK, session()) }, {}, client).use {
            assertEquals(ModelErrorCode.RATE_LIMITED, (it.run(request).events.single() as ModelEvent.Error).code)
        }
        assertEquals(1, calls)
    }
}

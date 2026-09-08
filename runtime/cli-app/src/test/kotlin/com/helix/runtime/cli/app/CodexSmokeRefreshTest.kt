package com.helix.runtime.cli.app

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class CodexSmokeRefreshTest {
    @Test fun cancellationIsNotRelabeledAsProtocolFailure() {
        val error = CancellationException("cancel refresh")
        Fixture(refreshAction = { throw error }).use {
            assertSame(error, assertThrows(CancellationException::class.java) { it.smoke.run() })
            assertEquals(1, it.refreshCalls)
            assertEquals(1, it.requests.size)
        }
    }

    @Test fun protocolFailureRetainsItsType() {
        val error = IllegalArgumentException("invalid expiry")
        Fixture(refreshAction = { throw error }).use {
            assertSame(error, assertThrows(IllegalArgumentException::class.java) { it.smoke.run() })
            assertEquals(1, it.requests.size)
        }
    }

    @Test fun permanentRejectionStillDeletesCredential() {
        val error = CodexOAuthEndpointException(400, "invalid_grant")
        Fixture(refreshAction = { throw error }).use {
            assertSame(error, assertThrows(CodexOAuthEndpointException::class.java) { it.smoke.run() })
            assertFalse(it.vault.contains(CliSubscriptionProvider.CODEX))
            assertEquals(1, it.requests.size)
        }
    }

    @Test fun transientFailurePreservesCredential() {
        val error = IOException("offline")
        Fixture(refreshAction = { throw error }).use {
            val original = it.vault.load(CliSubscriptionProvider.CODEX)
            assertSame(error, assertThrows(IOException::class.java) { it.smoke.run() })
            assertEquals(original, it.vault.load(CliSubscriptionProvider.CODEX))
        }
    }

    @Test fun oneRefreshUsesRotatedSessionForCatalogAndResponse() {
        Fixture().use {
            assertEquals(CodexSmokeResult("model", "HELIX_OK"), it.smoke.run())
            assertEquals(1, it.refreshCalls)
            assertEquals(listOf("GET", "GET", "POST"), it.requests.map { request -> request.method })
            assertEquals(
                listOf("Bearer initial", "Bearer rotated", "Bearer rotated"),
                it.requests.map { request -> request.header("Authorization") },
            )
        }
    }

    @Test fun repeatedUnauthorizedDoesNotRefreshOrPostAgain() {
        Fixture(alwaysUnauthorized = true).use {
            val failure = assertThrows(CodexSmokeException::class.java) { it.smoke.run() }
            assertEquals("models", failure.stage)
            assertEquals(401, failure.httpCode)
            assertEquals(1, it.refreshCalls)
            assertEquals(listOf("GET", "GET"), it.requests.map { request -> request.method })
        }
    }

    private class Fixture(
        alwaysUnauthorized: Boolean = false,
        refreshAction: (CliSubscriptionSession) -> CliSubscriptionSession = { it.copy(accessToken = "rotated") },
    ) : AutoCloseable {
        val vault =
            CliSubscriptionCredentialVault(MemoryStore()).apply {
                save(
                    CliSubscriptionProvider.CODEX,
                    CliSubscriptionSession("initial", "refresh", "id", 10_000, "account"),
                )
            }
        var refreshCalls = 0
        val requests = mutableListOf<Request>()
        private val transport =
            object : CodexOAuthTransport {
                override fun exchange(
                    attempt: CodexOAuthAttempt,
                    code: String,
                ): CliSubscriptionSession = error("unused")

                override fun refresh(session: CliSubscriptionSession): CliSubscriptionSession {
                    refreshCalls++
                    return refreshAction(session)
                }
            }
        private val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val unauthorized = requests.size == 1 || alwaysUnauthorized
                    val body =
                        if (request.method == "POST") {
                            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"HELIX_OK\"}\n\n" +
                                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                        } else {
                            """{"models":[{"slug":"model"}]}"""
                        }
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (unauthorized) 401 else 200)
                        .message("fixture")
                        .body(body.toResponseBody())
                        .build()
                }.build()
        val smoke = CodexSubscriptionSmoke(vault, CodexLoginController(vault, transport), client)

        override fun close() = smoke.close()
    }

    private class MemoryStore : CliSecretStore {
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

        override fun contains(name: String): Boolean = values.containsKey(name)
    }
}

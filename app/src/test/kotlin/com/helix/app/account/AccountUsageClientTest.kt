package com.helix.app.account

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountUsageClientTest {
    @Test fun usesSingleSnapshotAndAccountAuthentication() =
        runBlocking {
            val requests = mutableListOf<WireRequest>()
            val client =
                AccountUsageClient(
                    testAccount(),
                    object : WireClient {
                        override suspend fun open(request: WireRequest): com.helix.provider.api.wire.WireResponse {
                            requests += request
                            return accountResponse(
                                200,
                                """{"luna":{"model":"synthetic"},"audio":{},"usage":{"today":"fixture"}}""",
                            )
                        }
                    },
                )
            val snapshot = client.read()
            assertEquals("\"synthetic\"", snapshot.luna["model"].toString())
            assertEquals("\"fixture\"", snapshot.history["today"].toString())
            assertEquals(
                AccountUsageResource.entries.map { OfficialServices.ACCOUNT_BASE_URL + it.path },
                requests.map { it.url },
            )
            requests.forEach {
                assertEquals("GET", it.method)
                assertEquals(null, it.body)
                assertEquals("Bearer fixture-access", it.headers["Authorization"])
                assertEquals(null, it.headers["X-Client-Id"])
            }
        }

    @Test fun signedOutDoesNotSendAnUnauthenticatedUsageRequest() {
        var requests = 0
        val client =
            AccountUsageClient(
                AccountSession(MemoryAccountTokens()),
                object : WireClient {
                    override suspend fun open(request: WireRequest) = accountResponse(200, "{}").also { requests++ }
                },
            )
        val error = assertThrows(AccountRequestFailure::class.java) { runBlocking { client.read() } }
        assertEquals(401, error.status)
        assertEquals(0, requests)
    }

    @Test fun quotaRejectionDoesNotAutomaticallyRetry() {
        var requests = 0
        val client =
            AccountUsageClient(
                testAccount(),
                object : WireClient {
                    override suspend fun open(request: WireRequest) = accountResponse(429).also { requests++ }
                },
            )
        val error = assertThrows(AccountRequestFailure::class.java) { runBlocking { client.read() } }
        assertEquals(429, error.status)
        assertEquals(1, requests)
    }

    @Test fun rejectsUnsupportedDocumentsWithoutExposingTheirContents() {
        val client =
            AccountUsageClient(
                testAccount(),
                object : WireClient {
                    override suspend fun open(request: WireRequest) = accountResponse(200, "[\"private fixture\"]")
                },
            )
        val error = assertThrows(AccountUsageFormatFailure::class.java) { runBlocking { client.read() } }
        assertEquals("Account usage response has an unsupported format", error.message)
    }

    @Test fun missingOrWrongNestedDocumentsNeverFallBackToLegacyRequests() {
        for (document in listOf("{}", """{"luna":{},"audio":{},"usage":[]}""")) {
            var requests = 0
            val transport =
                object : WireClient {
                    override suspend fun open(request: WireRequest): com.helix.provider.api.wire.WireResponse {
                        requests++
                        return accountResponse(200, document)
                    }
                }
            val client = AccountUsageClient(testAccount(), transport)
            assertThrows(AccountUsageFormatFailure::class.java) { runBlocking { client.read() } }
            assertEquals(1, requests)
        }
    }

    @Test fun accountChangeRejectsTheEntireSnapshot() {
        val account = testAccount()
        var requests = 0
        val client =
            AccountUsageClient(
                account,
                object : WireClient {
                    override suspend fun open(request: WireRequest): com.helix.provider.api.wire.WireResponse {
                        requests++
                        account.logout()
                        return accountResponse(200, "{}")
                    }
                },
            )
        assertThrows(java.io.IOException::class.java) { runBlocking { client.read() } }
        assertEquals(1, requests)
    }
}

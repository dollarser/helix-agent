package com.helix.app.account

import com.helix.app.provider.BuiltInModel
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInAccountWireTest {
    @Test fun persistedAccountRestoresAdmissionAndLogoutLocksCachedDirectory(): Unit =
        runBlocking {
            val account = testAccount()
            val catalog =
                com.helix.app.provider.BuiltInModelCatalogStore(
                    com.helix.app.internal
                        .InMemoryLineStore(),
                ) { account.identity }
            assertTrue(!catalog.accountAvailable())
            account.restore()
            assertTrue(catalog.accountAvailable())
            catalog.save(
                com.helix.app.provider
                    .BuiltInModelCatalog(100, listOf(BuiltInModel.MODEL_ID)),
            )
            assertEquals(listOf(BuiltInModel.MODEL_ID), catalog.read().models)
            account.logout()
            assertTrue(!catalog.accountAvailable())
            assertEquals("signed-out", account.identityState.value)
            assertTrue(catalog.read().models.isEmpty())
        }

    @Test fun signedOutAndCallerCredentialsNeverReachTransport(): Unit =
        runBlocking {
            var calls = 0
            val transport =
                object : WireClient {
                    override suspend fun open(request: WireRequest) = accountResponse().also { calls++ }
                }
            val request = WireRequest("POST", BuiltInModel.GENERATION_URL, emptyMap(), byteArrayOf())
            assertEquals(401, BuiltInAccountWire(AccountSession(MemoryAccountTokens()), transport).open(request).status)
            val signedIn = BuiltInAccountWire(testAccount(), transport)
            for (header in listOf("X-Client-Id", "Authorization", "Cookie")) {
                assertTrue(
                    runCatching {
                        signedIn.open(WireRequest(request.method, request.url, mapOf(header to "caller"), request.body))
                    }.exceptionOrNull() is IllegalArgumentException,
                )
            }
            assertEquals(0, calls)
        }

    @Test fun refreshRetainsClientIdentityAndNeverFallsBack(): Unit =
        runBlocking {
            val requests = mutableListOf<WireRequest>()
            val transport =
                object : WireClient {
                    override suspend fun open(request: WireRequest) = accountResponse(401).also { requests += request }
                }
            val response =
                BuiltInAccountWire(testAccount(), transport).open(
                    WireRequest("POST", BuiltInModel.GENERATION_URL, emptyMap(), byteArrayOf()),
                )
            assertEquals(401, response.status)
            assertEquals(2, requests.size)
            assertEquals(
                listOf("Bearer fixture-access", "Bearer fixture-renewed"),
                requests.map { it.headers["Authorization"] },
            )
            assertTrue(requests.all { it.headers["X-Client-Id"] == BuiltInModel.CLIENT_ID })
            assertTrue(requests.all { it.url == BuiltInModel.GENERATION_URL })
        }
}

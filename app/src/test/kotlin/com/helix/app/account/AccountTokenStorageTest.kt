package com.helix.app.account

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic credentials only. The account service, not its deployment hostname, owns token validity. */
class AccountTokenStorageTest {
    private val tokens = AccountTokens("synthetic-access", "synthetic-refresh", 1000000, 2000000, "fixture")

    @Test fun storedRecordHasOnlyCredentialLifetimeAndSessionIdentity() {
        assertEquals(
            setOf("access_token", "refresh_token", "expires_at", "refresh_expires_at", "identity"),
            Json.parseToJsonElement(tokens.encoded()).jsonObject.keys,
        )
        val restored = AccountTokens.stored(tokens.encoded())
        assertEquals(tokens.access, restored.access)
        assertEquals(tokens.refresh, restored.refresh)
        assertEquals(tokens.expires, restored.expires)
        assertEquals(tokens.refreshExpires, restored.refreshExpires)
        assertEquals(tokens.identity, restored.identity)
    }

    @Test fun restoredSessionUsesCurrentServiceWithoutLoginOrCredentialRewrite() =
        runBlocking {
            val storage = MemoryAccountTokens(AccountTokens.stored(tokens.encoded()))
            val account =
                AccountSession(
                    storage,
                    object : WireClient {
                        override suspend fun open(request: WireRequest) = error("No login or refresh expected")
                    },
                    { 0L },
                )
            account.restore()
            assertTrue(account.signedIn.value)
            assertEquals(tokens.identity, account.identity)
            val url = "${OfficialServices.INFERENCE_BASE_URL}/chat/completions"
            val delegate =
                object : WireClient {
                    override suspend fun open(request: WireRequest) =
                        accountResponse(200).also {
                            assertEquals(url, request.url)
                            assertEquals("Bearer ${tokens.access}", request.headers["Authorization"])
                        }
                }
            account
                .wire(url, delegate)
                .open(WireRequest("POST", url, emptyMap(), "{}".toByteArray()))
                .body
                .close()
            assertEquals(tokens.encoded(), storage.value?.encoded())
        }

    @Test fun restoredSessionRefreshAndLogoutUseCurrentAccountService() =
        runBlocking {
            val stale = AccountTokens(tokens.access, tokens.refresh, 1, 2000000, tokens.identity)
            val storage = MemoryAccountTokens(AccountTokens.stored(stale.encoded()))
            val operations = mutableListOf<String>()
            val account =
                AccountSession(
                    storage,
                    object : WireClient {
                        override suspend fun open(request: WireRequest) =
                            accountResponse(
                                200,
                                """{"access_token":"new-access","refresh_token":"new-refresh",
                                    "expires_in":600,"refresh_expires_in":1200}""",
                            ).also {
                                operations += request.url
                                val refresh = if (request.url.endsWith("/refresh")) tokens.refresh else "new-refresh"
                                assertEquals(
                                    "{\"refresh_token\":\"$refresh\"}",
                                    requireNotNull(request.body).toString(Charsets.UTF_8),
                                )
                            }
                    },
                    { 100L },
                )
            account.restore()
            assertEquals("new-access", account.authorization(expectedRevision = account.revision)?.access)
            assertEquals(tokens.identity, account.identity)
            account.logout()
            assertEquals(
                listOf("refresh", "logout").map { "${OfficialServices.ACCOUNT_BASE_URL}/auth/$it" },
                operations,
            )
            assertNull(storage.value)
            assertEquals(AccountStatus.SIGNED_OUT, account.status.value)
        }
}

package com.helix.app.account

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AccountSessionTest {
    private val url = "https://api.sunlingzhang.com/v1/chat/completions"
    private val payload =
        """
        {"access_token":"next-access","refresh_token":"next-refresh",
         "expires_in":600,"refresh_expires_in":1200}
        """.trimIndent()

    private fun wire(action: suspend (WireRequest) -> WireResponse) =
        object : WireClient {
            override suspend fun open(request: WireRequest) = action(request)
        }

    private fun request(target: String = url) = WireRequest("POST", target, emptyMap(), "{}".toByteArray())

    @Test fun restoreDistinguishesExpiredRefreshFromRefreshableAccess() =
        runBlocking {
            val expired = AccountSession(MemoryAccountTokens(AccountTokens("a", "r", 1, 2, "id")), now = { 100L })
            expired.restore()
            assertEquals(AccountStatus.REAUTH_REQUIRED, expired.status.value)
            assertFalse(expired.signedIn.value)
            val refreshable =
                AccountSession(MemoryAccountTokens(AccountTokens("a", "r", 1, 200000, "id")), now = { 100L })
            refreshable.restore()
            assertEquals(AccountStatus.SIGNED_IN, refreshable.status.value)
        }

    @Test fun refreshFailureRequiresSignInButPreservesHttpClassification() =
        runBlocking {
            val stale =
                AccountSession(
                    MemoryAccountTokens(AccountTokens("a", "r", 1, 200000, "id")),
                    wire { accountResponse(503) },
                    { 100L },
                )
            assertEquals(503, stale.wire(url).open(request()).status)
            assertEquals(AccountStatus.REAUTH_REQUIRED, stale.status.value)
            val valid = testAccount()
            assertThrows(IOException::class.java) {
                runBlocking { valid.wire(url, wire { throw IOException("synthetic offline") }).open(request()) }
            }
            assertEquals(AccountStatus.SIGNED_IN, valid.status.value)
        }

    @Test fun gatewayFailureOnRefreshDoesNotSendModelAndCanRecover() =
        runBlocking {
            var available = false
            var sends = 0
            val account =
                AccountSession(
                    MemoryAccountTokens(AccountTokens("a", "r", 1, 200000, "id")),
                    wire {
                        if (available) accountResponse(200, payload) else accountResponse(530, "sensitive response")
                    },
                    { 100L },
                )
            val model =
                account.wire(
                    url,
                    wire {
                        sends++
                        accountResponse(200)
                    },
                )
            val failed = model.open(request())
            assertEquals(530, failed.status)
            assertTrue(failed.body.bytes().isEmpty())
            assertEquals(0, sends)
            assertEquals(AccountStatus.REAUTH_REQUIRED, account.status.value)
            available = true
            account.login("synthetic@example.test", "synthetic-password")
            val recovered =
                account.wire(
                    url,
                    wire {
                        sends++
                        accountResponse(200)
                    },
                )
            assertEquals(200, recovered.open(request()).status)
            assertEquals(1, sends)
        }

    @Test fun explicitLogoutHasNoExpiredLabelEvenWhenRevocationFails() =
        runBlocking {
            val account =
                AccountSession(
                    MemoryAccountTokens(AccountTokens("a", "r", 100000, 200000, "id")),
                    wire { accountResponse(503) },
                    { 100L },
                )
            account.restore()
            assertThrows(IOException::class.java) { runBlocking { account.logout() } }
            assertEquals(AccountStatus.SIGNED_OUT, account.status.value)
            assertFalse(account.signedIn.value)
        }

    @Test fun unreadableStorageDoesNotPretendAccountExpired() =
        runBlocking {
            val store =
                object : AccountTokenStorage {
                    override fun load(): AccountTokens? = throw IOException("synthetic unavailable")

                    override fun save(tokens: AccountTokens) = Unit

                    override fun clear() = Unit
                }
            val account = AccountSession(store)
            assertThrows(IOException::class.java) { runBlocking { account.restore() } }
            assertEquals(AccountStatus.UNAVAILABLE, account.status.value)
            assertFalse(account.signedIn.value)
        }

    @Test fun signedOutNeverCallsGenerationOrFallsBackToClientId() =
        runBlocking {
            val account = AccountSession(MemoryAccountTokens())
            val response = account.wire(url, wire { error("No model request") }).open(request())
            assertEquals(401, response.status)
            response.body.close()
        }

    @Test fun loginPersistsBeforePublishingAndDoesNotPersistPassword() =
        runBlocking {
            val store = MemoryAccountTokens()
            val account =
                AccountSession(
                    store,
                    wire {
                        assertEquals("https://accounts.sunlingzhang.com/auth/login", it.url)
                        assertEquals(AccountSession.CLIENT_ID, it.headers["X-Client-Id"])
                        assertTrue(it.body!!.toString(Charsets.UTF_8).contains("\"username\":\"user@example.test\""))
                        accountResponse(200, payload)
                    },
                    { 100L },
                )
            account.login("user@example.test", "synthetic-password")
            assertTrue(account.signedIn.value)
            assertFalse(store.value!!.encoded().contains("synthetic-password"))
            assertEquals(600100L, store.value!!.expires)
            assertEquals("next-refresh", store.value!!.refresh)
            assertEquals(account.identity, account.identityState.value)
        }

    @Test fun allowedHttpsOnlyAndExactTargetNoCredentialOverride(): Unit =
        runBlocking {
            val account = testAccount()
            for (target in listOf(
                "http://api.sunlingzhang.com/v1",
                "https://evil.test/v1",
                "https://api.sunlingzhang.com:444/v1",
                "https://user:pass@api.sunlingzhang.com/v1",
                "$url?token=x",
            )) {
                assertThrows(IllegalArgumentException::class.java) { account.wire(target) }
            }
            val service =
                account.wire(
                    url,
                    wire {
                        assertEquals("Bearer fixture-access", it.headers["Authorization"])
                        assertEquals(AccountSession.CLIENT_ID, it.headers["X-Client-Id"])
                        accountResponse()
                    },
                )
            service.open(request()).body.close()
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service.open(request("https://tts.sunlingzhang.com/v1/audio/speech")) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    service.open(
                        WireRequest(
                            "POST",
                            url,
                            mapOf("Authorization" to "secret"),
                            null,
                        ),
                    )
                }
            }
        }

    @Test fun expiredAccessRefreshesOnceAcrossConcurrentRequests() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val rotations = AtomicInteger()
            val store = MemoryAccountTokens(AccountTokens("old", "refresh", 1, 2000000, "same-user"))
            val account =
                AccountSession(
                    store,
                    wire {
                        rotations.incrementAndGet()
                        entered.complete(Unit)
                        release.await()
                        accountResponse(200, payload)
                    },
                    { 100L },
                )
            val service =
                account.wire(
                    url,
                    wire {
                        assertEquals("Bearer next-access", it.headers["Authorization"])
                        accountResponse()
                    },
                )
            val jobs = (1..5).map { async { service.open(request()).body.close() } }
            entered.await()
            release.complete(Unit)
            jobs.awaitAll()
            assertEquals(1, rotations.get())
            assertEquals("same-user", store.value!!.identity)
        }

    @Test fun unauthorizedResponseClosesThenRefreshesAndRetriesOnlyOnce() =
        runBlocking {
            val count = AtomicInteger()
            val account = testAccount()
            val service =
                account.wire(
                    url,
                    wire {
                        count.incrementAndGet()
                        accountResponse(401)
                    },
                )
            assertEquals(401, service.open(request()).status)
            assertEquals(2, count.get())
        }

    @Test fun indeterminateRefreshClearsAndNeverRetriesConsumedRefreshToken() =
        runBlocking {
            val count = AtomicInteger()
            val store = MemoryAccountTokens(AccountTokens("old", "refresh", 1, 2000000, "user"))
            val account =
                AccountSession(
                    store,
                    wire {
                        count.incrementAndGet()
                        throw IOException("Synthetic transport failure")
                    },
                    { 100L },
                )
            val service = account.wire(url, wire { error("No generation") })
            assertThrows(IOException::class.java) { runBlocking { service.open(request()) } }
            assertNull(store.value)
            assertEquals(401, service.open(request()).status)
            assertEquals(1, count.get())
        }

    @Test fun logoutClearsBeforeFailedRemoteRevokeAndDropsLateBodies(): Unit =
        runBlocking {
            val store = MemoryAccountTokens(AccountTokens("old", "refresh", 1000000, 2000000, "user"))
            val account = AccountSession(store, wire { accountResponse(503, "sensitive server details") }, { 100L })
            val response = account.wire(url, wire { accountResponse(200, "late answer") }).open(request())
            assertThrows(IOException::class.java) { runBlocking { account.logout() } }
            assertNull(store.value)
            assertFalse(account.signedIn.value)
            assertThrows(IOException::class.java) { runBlocking { response.body.bytes() } }
        }

    @Test fun storageFailureNeverPublishesSuccessfulLogin() =
        runBlocking {
            val broken =
                object : AccountTokenStorage {
                    override fun load(): AccountTokens? = null

                    override fun save(tokens: AccountTokens) = throw IOException("Synthetic write failure")

                    override fun clear() = Unit
                }
            val account = AccountSession(broken, wire { accountResponse(200, payload) }, { 100L })
            assertThrows(
                IOException::class.java,
            ) { runBlocking { account.login("user@example.test", "synthetic-password") } }
            assertFalse(account.signedIn.value)
        }

    @Test fun switchingAccountsCannotRetargetAnExistingInvocationOrDeliverOldBody(): Unit =
        runBlocking {
            val store = MemoryAccountTokens(AccountTokens("old", "refresh", 1000000, 2000000, "old-session"))
            val account = AccountSession(store, wire { accountResponse(200, payload) }, { 100L })
            val calls = AtomicInteger()
            val delegate =
                wire {
                    calls.incrementAndGet()
                    accountResponse(200, "synthetic answer")
                }
            val existing = account.wire(url, delegate)
            val notYetStarted = account.wire(url, delegate)
            val response = existing.open(request())
            account.logout()
            account.login("synthetic@example.test", "synthetic-password")
            assertThrows(IOException::class.java) { runBlocking { response.body.bytes() } }
            assertThrows(IOException::class.java) { runBlocking { existing.open(request()) } }
            assertThrows(IOException::class.java) { runBlocking { notYetStarted.open(request()) } }
            assertEquals(1, calls.get())
            account
                .wire(url, delegate)
                .open(request())
                .body
                .close()
            assertEquals(2, calls.get())
        }

    @Test fun expiredRefreshAndInvalidTokenResponsesNeverGrantAccess(): Unit =
        runBlocking {
            val store = MemoryAccountTokens(AccountTokens("old", "expired", 1, 2, "session"))
            val account = AccountSession(store, wire { error("Expired refresh must not be sent") }, { 100L })
            assertEquals(401, account.wire(url, wire { error("No model call") }).open(request()).status)
            assertNull(store.value)
            val malformed = AccountSession(MemoryAccountTokens(), wire { accountResponse(200, "{}") }, { 100L })
            assertThrows(IOException::class.java) {
                runBlocking { malformed.login("synthetic@example.test", "synthetic-password") }
            }
            assertFalse(malformed.signedIn.value)
            val longLived = AccountTokens.response(payload.replace("1200", "315360000").toByteArray(), 100L)
            assertTrue(longLived.refreshExpires > 315360000000L)
            assertThrows(ArithmeticException::class.java) {
                AccountTokens.response(payload.replace("1200", Long.MAX_VALUE.toString()).toByteArray(), 100L)
            }
        }
}

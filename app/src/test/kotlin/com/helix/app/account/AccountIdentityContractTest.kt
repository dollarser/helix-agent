package com.helix.app.account

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import com.helix.provider.api.wire.readHttpFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class AccountIdentityContractTest {
    @Test fun delayedProfileReadCannotOverwriteNewBindingAcrossClients() =
        runBlocking {
            val account = AccountSession(MemoryAccountTokens(), wire { accountResponse(200, login) }, { 100L })
            account.login("original-login", "password")
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            var writes = 0
            val transport =
                wire { request ->
                    val email =
                        if (request.method == "GET") {
                            readStarted.complete(Unit)
                            releaseRead.await()
                            "old@example.test"
                        } else {
                            writes++
                            "new@example.test"
                        }
                    accountResponse(200, """{"user":{"id":12,"username":"original-login","email":"$email"}}""")
                }
            val refresh = async { AccountProfileClient(account, transport).refreshUser() }
            readStarted.await()
            val binding =
                async(start = CoroutineStart.UNDISPATCHED) {
                    AccountProfileClient(account, transport).bindEmail("new@example.test", "012345")
                }
            assertEquals(0, writes)
            releaseRead.complete(Unit)
            refresh.await()
            binding.await()
            assertEquals(1, writes)
            assertEquals("new@example.test", account.user.value!!.email)
        }

    @Test fun stalePasswordReauthenticationCannotReplaceNewerLogin() =
        runBlocking {
            var sends = 0
            val account =
                AccountSession(
                    MemoryAccountTokens(),
                    wire {
                        sends++
                        accountResponse(200, login)
                    },
                    { 100L },
                )
            account.login("original-login", "password")
            val revision = account.revision
            account.login("other-login", "password")
            val error =
                runCatching {
                    account.login("original-login", "new-password", expectedRevision = revision)
                }.exceptionOrNull()
            assertTrue(error is AccountDataFailure)
            assertEquals(2, sends)
            assertTrue(account.signedIn.value)
        }

    @Test fun queuedIdentityRequestsCannotRetargetAfterAccountSwitch() =
        runBlocking {
            for (operation in listOf(
                "read",
                "code",
                "bind",
                "password",
                "notice-read",
                "notice-count",
                "notice-mark",
            )) {
                val account = AccountSession(MemoryAccountTokens(), wire { accountResponse(200, login) }, { 100L })
                account.login("original-login", "password")
                var sends = 0
                var held: Runnable? = null
                val dispatcher =
                    object : CoroutineDispatcher() {
                        override fun dispatch(
                            context: CoroutineContext,
                            block: Runnable,
                        ) {
                            if (held == null) held = block else Dispatchers.IO.dispatch(context, block)
                        }
                    }
                val transport =
                    wire {
                        sends++
                        accountResponse(200, "{}")
                    }
                val client =
                    AccountProfileClient(
                        account,
                        transport,
                        dispatcher,
                    )
                val notices = AccountNoticesClient(account, transport, dispatcher)
                val pending =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching {
                            dispatchIdentityOperation(operation, client, notices, account.revision)
                        }.exceptionOrNull()
                    }
                account.login("other-login", "other-password")
                Dispatchers.IO.dispatch(EmptyCoroutineContext, requireNotNull(held))
                val error = pending.await()
                assertTrue(
                    "$operation must reject stale account",
                    error is com.helix.provider.api.wire.WireRequestStateException,
                )
                assertEquals("$operation must not reach transport", 0, sends)
                assertTrue(account.signedIn.value)
            }
        }

    private suspend fun dispatchIdentityOperation(
        operation: String,
        client: AccountProfileClient,
        notices: AccountNoticesClient,
        revision: Long,
    ) {
        when (operation) {
            "read" -> {
                client.refreshUser()
            }

            "code" -> {
                client.sendBindingCode(
                    "new@example.test",
                    AccountCaptchaProof(AccountCaptchaAction.EMAIL_BINDING, "synthetic"),
                    revision,
                )
            }

            "bind" -> {
                client.bindEmail("new@example.test", "012345")
            }

            "notice-read" -> {
                notices.list(1, AccountNoticeState.ALL)
            }

            "notice-count" -> {
                notices.unreadCount()
            }

            "notice-mark" -> {
                notices.markRead(listOf("a".repeat(48)))
            }

            else -> {
                client.changePassword("password", "new-password")
            }
        }
    }

    private fun wire(action: suspend (WireRequest) -> WireResponse) =
        object : WireClient {
            override suspend fun open(request: WireRequest) = action(request)
        }

    private val login =
        """
{"access_token":"a","refresh_token":"r",
        "expires_in":600,"refresh_expires_in":1200,
        "user":{"id":12,"username":"original-login",
                "email":"contact@example.test",
        "must_change":1,"email_binding_required":true}}
        """.trimIndent()

    @Test fun userAndNumericMustChangeSurviveSecurePersistenceAndRefresh() =
        runBlocking {
            val storage = MemoryAccountTokens()
            val account = AccountSession(storage, wire { accountResponse(200, login) }, { 100L })
            account.login("original-login", "fixture-password")
            assertTrue(account.user.value!!.mustChange)
            assertEquals("12", account.noticeAccountKey)
            val restored = AccountTokens.stored(storage.value!!.encoded())
            assertEquals("original-login", restored.user!!.username)
            assertEquals("12", AccountTokens.response(login.toByteArray(), 100L).user!!.id)
            val withoutUser =
                """
{"access_token":"b","refresh_token":"s",
        "expires_in":600,"refresh_expires_in":1200}
                """.trimIndent()
            val renewed = AccountTokens.response(withoutUser.toByteArray(), 100L, restored.identity, restored.user)
            assertEquals(restored.user, renewed.user)
        }

    @Test fun registrationPreservesLeadingZeroAndRequiresValidCode() =
        runBlocking {
            val requests = mutableListOf<WireRequest>()
            val transport =
                wire {
                    requests += it
                    accountResponse(201, "{}")
                }
            val account = AccountSession(MemoryAccountTokens(), transport)
            AccountProfileClient(account).register("email@example.test", "password", "012345")
            assertTrue(
                requests
                    .single()
                    .body!!
                    .toString(Charsets.UTF_8)
                    .contains("\"code\":\"012345\""),
            )
            assertFalse(requests.single().headers.containsKey("X-Client-Id"))
        }

    @Test fun bindingUpdatesContactButKeepsOriginalLoginName() =
        runBlocking {
            val account = AccountSession(MemoryAccountTokens(), wire { accountResponse(200, login) }, { 100L })
            account.login("original-login", "password")
            val requests = mutableListOf<WireRequest>()
            val client =
                AccountProfileClient(
                    account,
                    wire {
                        requests += it
                        accountResponse(
                            200,
                            """
{"user":{"id":12,"username":"original-login",
                "email":"new@example.test",
                "must_change":0,"email_binding_required":false}}
                            """.trimIndent(),
                        )
                    },
                )
            client.bindEmail("new@example.test", "012345")
            assertEquals("original-login", account.user.value!!.username)
            assertEquals("new@example.test", account.user.value!!.email)
            assertFalse(account.user.value!!.emailBindingRequired)
            assertFalse(account.user.value!!.mustChange)
            assertTrue(
                requests
                    .single()
                    .body!!
                    .toString(Charsets.UTF_8)
                    .contains("012345"),
            )
            assertFalse(requests.single().headers.containsKey("X-Client-Id"))
        }

    @Test fun providerAuthenticationFailureNeverRefreshesOrClearsLogin() =
        runBlocking {
            var refreshes = 0
            val account =
                AccountSession(
                    MemoryAccountTokens(AccountTokens("a", "r", 100000, 200000, "id")),
                    wire {
                        refreshes++
                        accountResponse(401)
                    },
                    { 100L },
                )
            val endpoint = "https://api.hilex.me/v1/models?output_modalities=all"
            val response =
                account
                    .wire(
                        endpoint,
                        wire {
                            accountResponse(
                                401,
                                """
{"error_code":"provider_auth_failed","retryable":false,
                "detail":"Upstream authentication failed"}
                                """.trimIndent(),
                            )
                        },
                    ).open(WireRequest("GET", endpoint, emptyMap(), null))
            val failure = readHttpFailure(response)
            assertEquals("provider_auth_failed", failure.errorCode)
            assertEquals(false, failure.retryable)
            assertEquals(0, refreshes)
            assertTrue(account.signedIn.value)
        }

    @Test fun submittedWriteNeverAutomaticallyReplaysAfter401() =
        runBlocking {
            var sends = 0
            val store = MemoryAccountTokens(AccountTokens("a", "r", 100000, 200000, "id"))
            val account = AccountSession(store, wire { accountResponse(200, login) }, { 100L })
            val endpoint = "https://api.hilex.me/v1/chat/completions"
            val response =
                account
                    .wire(
                        endpoint,
                        wire {
                            sends++
                            accountResponse(401)
                        },
                    ).open(WireRequest("POST", endpoint, emptyMap(), "{}".toByteArray()))
            assertEquals(401, response.status)
            assertEquals(1, sends)
            assertTrue(account.signedIn.value)
        }

    @Test fun concurrentRejectedReadsRotateOnce() =
        runBlocking {
            val rotations = AtomicInteger()
            val account =
                AccountSession(
                    MemoryAccountTokens(AccountTokens("old", "r", 100000, 200000, "id")),
                    wire {
                        rotations.incrementAndGet()
                        accountResponse(200, login)
                    },
                    { 100L },
                )
            val endpoint = "https://accounts.hilex.me/account/me"
            val service =
                account.wire(
                    endpoint,
                    wire {
                        accountResponse(if (it.headers["Authorization"] == "Bearer old") 401 else 200)
                    },
                )
            (1..5)
                .map {
                    async {
                        service.open(WireRequest("GET", endpoint, emptyMap(), null)).body.close()
                    }
                }.awaitAll()
            assertEquals(1, rotations.get())
        }
}

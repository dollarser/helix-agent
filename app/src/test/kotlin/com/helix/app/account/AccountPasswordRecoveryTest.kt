package com.helix.app.account

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AccountPasswordRecoveryTest {
    @Test fun anonymousSendUsesSingleProofAndCooldownWithoutLoginOrHeaders() =
        runBlocking {
            val requests = mutableListOf<WireRequest>()
            val account =
                AccountSession(
                    MemoryAccountTokens(),
                    wire { request ->
                        requests += request
                        accountResponse(200, """{"retry_after":75,"expires_in":600}""")
                    },
                )
            val client = AccountPasswordRecoveryClient(account)
            val proof = proof()
            assertEquals(75L, client.sendCode(" OWNER@Example.test ", proof))
            val request = requests.single()
            assertEquals("https://accounts.hilex.me/auth/password-reset/code", request.url)
            assertEquals(setOf("Content-Type"), request.headers.keys)
            assertEquals("owner@example.test", body(request).getValue("email").jsonPrimitive.content)
            assertEquals("synthetic-proof", body(request).getValue("captcha_token").jsonPrimitive.content)
            assertTrue(client.remainingCooldownMillis() > 0)
            assertTrue(
                runCatching {
                    client.sendCode(
                        "owner@example.test",
                        proof,
                    )
                }.exceptionOrNull() is IllegalArgumentException,
            )
            assertTrue(
                runCatching {
                    client.sendCode(
                        "owner@example.test",
                        proof(),
                    )
                }.exceptionOrNull() is AccountRequestFailure,
            )
            assertEquals(1, requests.size)
        }

    @Test fun resetPreservesCodeAndPasswordAndBooleanAndClearsOnlyMatchingAccount() =
        runBlocking {
            for (matching in listOf(true, false)) {
                val store = MemoryAccountTokens(tokens(if (matching) "owner@example.test" else "other@example.test"))
                val requests = mutableListOf<WireRequest>()
                val account =
                    AccountSession(
                        store,
                        wire { request ->
                            requests += request
                            accountResponse(200, """{"ok":true,"api_keys_revoked":false}""")
                        },
                        { 100L },
                    )
                account.restore()
                val revision = account.revision
                AccountPasswordRecoveryClient(account).reset("OWNER@example.test", "012345", " secret ", " secret ")
                val fields = body(requests.single())
                assertEquals(setOf("email", "code", "new_password", "revoke_api_keys"), fields.keys)
                assertEquals("012345", fields.getValue("code").jsonPrimitive.content)
                assertEquals(" secret ", fields.getValue("new_password").jsonPrimitive.content)
                assertFalse(fields.getValue("revoke_api_keys").jsonPrimitive.isString)
                assertEquals("false", fields.getValue("revoke_api_keys").jsonPrimitive.content)
                assertEquals(!matching, account.signedIn.value)
                assertEquals(matching, store.value == null)
                assertEquals(if (matching) revision + 1 else revision, account.revision)
            }
        }

    @Test fun invalidFieldsNeverTransmit() =
        runBlocking {
            var sends = 0
            val client =
                AccountPasswordRecoveryClient(
                    AccountSession(
                        MemoryAccountTokens(),
                        wire {
                            sends++
                            accountResponse()
                        },
                    ),
                )
            for ((
                code,
                password,
                confirmation,
            ) in listOf(
                Triple(
                    "１２３４５６",
                    "abcdef",
                    "abcdef",
                ),
                Triple(
                    "012345",
                    "short",
                    "short",
                ),
                Triple(
                    "012345",
                    "abcdef",
                    "different",
                ),
            )) {
                assertTrue(
                    runCatching {
                        client.reset(
                            "owner@example.test",
                            code,
                            password,
                            confirmation,
                        )
                    }.exceptionOrNull() is IllegalArgumentException,
                )
            }
            assertEquals(0, sends)
        }

    @Test fun failedOrUncertainResetDoesNotReplayOrClearOtherState() =
        runBlocking {
            for (status in listOf(400, 429, 503, -1)) {
                var sends = 0
                val store = MemoryAccountTokens(tokens("owner@example.test"))
                val account =
                    AccountSession(
                        store,
                        wire {
                            sends++
                            if (status == -1) throw IOException("synthetic timeout")
                            accountResponse(
                                status,
                                """{"error_code":"password_reset_invalid","request_id":"synthetic"}""",
                            )
                        },
                        { 100L },
                    )
                account.restore()
                val failure =
                    runCatching {
                        AccountPasswordRecoveryClient(account).reset(
                            "owner@example.test",
                            "012345",
                            "abcdef",
                            "abcdef",
                        )
                    }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals(1, sends)
                assertTrue(account.signedIn.value)
            }
        }

    @Test fun proofIsActionBoundExpiresAndIsConsumedEvenOnFailure() =
        runBlocking {
            var clock = 0L
            val expired = AccountCaptchaProof(AccountCaptchaAction.PASSWORD_RESET, "proof") { clock }
            clock = 300000L
            assertTrue(runCatching { expired.consume(AccountCaptchaAction.PASSWORD_RESET) }.isFailure)
            val wrong = proof()
            assertTrue(runCatching { wrong.consume(AccountCaptchaAction.REGISTRATION) }.isFailure)
            assertTrue(runCatching { wrong.consume(AccountCaptchaAction.PASSWORD_RESET) }.isFailure)
            val value = proof()
            var calls = 0
            val client =
                AccountPasswordRecoveryClient(
                    AccountSession(
                        MemoryAccountTokens(),
                        wire {
                            calls++
                            throw IOException("lost")
                        },
                    ),
                )
            assertTrue(runCatching { client.sendCode("a@example.test", value) }.isFailure)
            assertTrue(runCatching { client.sendCode("a@example.test", value) }.isFailure)
            assertEquals(1, calls)
            assertFalse(value.toString().contains("synthetic-proof"))
        }

    @Test fun resetSerializesWithNewLoginAndDoesNotEraseItAfterCompletion() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = MemoryAccountTokens(tokens("owner@example.test"))
            val account =
                AccountSession(
                    store,
                    wire { request ->
                        if (request.url.endsWith("password-reset")) {
                            started.complete(Unit)
                            release.await()
                            accountResponse(200, """{"ok":true}""")
                        } else {
                            accountResponse(
                                200,
                                """
{"access_token":"new","refresh_token":"new-r",
"expires_in":600,
"refresh_expires_in":1200,
"user":{"id":"other","display_name":"other","email":"other@example.test"}}""",
                            )
                        }
                    },
                    { 100L },
                )
            account.restore()
            val reset =
                async {
                    AccountPasswordRecoveryClient(account).reset(
                        "owner@example.test",
                        "012345",
                        "abcdef",
                        "abcdef",
                    )
                }
            started.await()
            val login = async(start = CoroutineStart.UNDISPATCHED) { account.login("other", "secret") }
            release.complete(Unit)
            reset.await()
            login.await()
            assertEquals("other", account.user.value!!.id)
            assertTrue(account.signedIn.value)
        }

    @Test fun registrationAndBindingUseDistinctProofAndOldBindingRevisionNeverSends() =
        runBlocking {
            val requests = mutableListOf<WireRequest>()
            val account =
                AccountSession(
                    MemoryAccountTokens(tokens("owner@example.test")),
                    wire { request ->
                        requests += request
                        accountResponse(
                            200,
                            "{}",
                        )
                    },
                    { 100L },
                )
            account.restore()
            val client = AccountProfileClient(account)
            client.sendRegistrationCode(
                "a@example.test",
                AccountCaptchaProof(
                    AccountCaptchaAction.REGISTRATION,
                    "registration",
                ),
            )
            client.sendBindingCode(
                "a@example.test",
                AccountCaptchaProof(
                    AccountCaptchaAction.EMAIL_BINDING,
                    "binding",
                ),
                account.revision,
            )
            assertEquals("registration", body(requests[0]).getValue("captcha_token").jsonPrimitive.content)
            assertEquals("binding", body(requests[1]).getValue("captcha_token").jsonPrimitive.content)
            assertFalse(requests[0].headers.containsKey("Authorization"))
            assertTrue(requests[1].headers.containsKey("Authorization"))
            assertTrue(
                runCatching {
                    client.sendBindingCode(
                        "a@example.test",
                        AccountCaptchaProof(
                            AccountCaptchaAction.EMAIL_BINDING,
                            "stale",
                        ),
                        account.revision - 1,
                    )
                }.isFailure,
            )
            assertEquals(2, requests.size)
        }

    @Test fun lateRefreshFinishesBeforeResetAndCannotRestoreClearedTokens() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store = MemoryAccountTokens(tokens("owner@example.test", expires = 1L))
            val paths = mutableListOf<String>()
            val account =
                AccountSession(
                    store,
                    wire { request ->
                        paths += request.url
                        if (request.url.endsWith("refresh")) {
                            started.complete(Unit)
                            release.await()
                            accountResponse(
                                200,
                                """
{"access_token":"renewed","refresh_token":"new-r",
"expires_in":600,
"refresh_expires_in":1200}""",
                            )
                        } else {
                            accountResponse(200, """{"ok":true}""")
                        }
                    },
                    { 100L },
                )
            account.restore()
            val refresh = async { account.authorization(expectedRevision = account.revision) }
            started.await()
            val reset =
                async {
                    AccountPasswordRecoveryClient(account).reset(
                        "owner@example.test",
                        "012345",
                        "abcdef",
                        "abcdef",
                    )
                }
            release.complete(Unit)
            refresh.await()
            reset.await()
            assertFalse(account.signedIn.value)
            assertNull(store.value)
            assertEquals(2, paths.size)
            val restored = AccountSession(store, wire { error("No request expected") }, { 100L })
            restored.restore()
            assertFalse(restored.signedIn.value)
        }

    @Test fun cancelledSendNeverReusesProofOrRetries() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val blocked = CompletableDeferred<Unit>()
            var sends = 0
            val client =
                AccountPasswordRecoveryClient(
                    AccountSession(
                        MemoryAccountTokens(),
                        wire {
                            sends++
                            started.complete(Unit)
                            blocked.await()
                            accountResponse(200, "{}")
                        },
                    ),
                )
            val proof = proof()
            val sending = async { client.sendCode("owner@example.test", proof) }
            started.await()
            sending.cancelAndJoin()
            assertTrue(runCatching { client.sendCode("owner@example.test", proof) }.isFailure)
            assertEquals(1, sends)
        }

    private fun proof() = AccountCaptchaProof(AccountCaptchaAction.PASSWORD_RESET, "synthetic-proof")

    private fun tokens(
        email: String,
        expires: Long = 1000000L,
    ) = AccountTokens(
        "access",
        "refresh",
        expires,
        2000000L,
        "identity",
        AccountUser(
            "owner",
            "original-name",
            email,
            false,
            false,
        ),
    )

    private fun body(request: WireRequest) = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject

    private fun wire(block: suspend (WireRequest) -> com.helix.provider.api.wire.WireResponse) =
        object : WireClient {
            override suspend fun open(request: WireRequest) = block(request)
        }
}

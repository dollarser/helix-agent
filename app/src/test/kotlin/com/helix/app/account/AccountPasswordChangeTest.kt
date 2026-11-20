package com.helix.app.account

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AccountPasswordChangeTest {
    @Test fun passwordMutationPreservesSpacesAndReauthenticatesEmail() =
        runBlocking {
            val calls = mutableListOf<WireRequest>()
            val transport =
                wire { request ->
                    calls += request
                    accountResponse(200, if (request.url.endsWith("/auth/login")) login else "{}")
                }
            val account = AccountSession(MemoryAccountTokens(), transport, { 100L })
            account.login("contact@example.test", "old-password")
            val revision = account.revision
            AccountProfileClient(account).changePassword(" old password ", " new password ", revision)
            assertEquals(3, calls.size)
            val mutation = Json.parseToJsonElement(calls[1].body!!.decodeToString()).jsonObject
            assertEquals(setOf("current_password", "new_password"), mutation.keys)
            assertEquals(" old password ", mutation.getValue("current_password").jsonPrimitive.content)
            assertEquals(" new password ", mutation.getValue("new_password").jsonPrimitive.content)
            val reauth = Json.parseToJsonElement(calls[2].body!!.decodeToString()).jsonObject
            assertEquals("contact@example.test", reauth.getValue("email").jsonPrimitive.content)
            assertEquals(" new password ", reauth.getValue("password").jsonPrimitive.content)
            assertTrue(account.revision > revision)
        }

    @Test fun staleOpenFormAndInvalidInputsNeverReachTransport() =
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
            account.login("contact@example.test", "old-password")
            val old = account.revision
            account.login("contact@example.test", "new-session-password")
            val client = AccountProfileClient(account)
            val stale = runCatching { client.changePassword("old", "new-password", old) }.exceptionOrNull()
            assertTrue(stale is AccountDataFailure)
            for ((current, next) in listOf(
                "" to "123456",
                "old" to "12345",
                "old" to "x".repeat(129),
                "old" to "      ",
            )) {
                val invalid = runCatching { client.changePassword(current, next) }.exceptionOrNull()
                assertTrue(invalid is IllegalArgumentException)
            }
            assertEquals(2, sends)
        }

    @Test fun rejectedOrUnknownMutationNeverRetriesOrAttemptsLogin() =
        runBlocking {
            for (unknown in listOf(false, true)) {
                var sends = 0
                val account = AccountSession(MemoryAccountTokens(), wire { accountResponse(200, login) }, { 100L })
                account.login("contact@example.test", "old-password")
                val revision = account.revision
                val client =
                    AccountProfileClient(
                        account,
                        wire {
                            sends++
                            if (unknown) throw IOException("synthetic transport interrupted")
                            accountResponse(400, """{"error_code":"invalid_current_password"}""")
                        },
                    )
                assertTrue(runCatching { client.changePassword("old-password", "new-password") }.isFailure)
                assertEquals(1, sends)
                assertEquals(revision, account.revision)
            }
        }

    @Test fun confirmedChangeWithFailedLoginClearsOnlyItsSessionAndReportsDistinctOutcome() =
        runBlocking {
            var logins = 0
            val storage = MemoryAccountTokens()
            val account =
                AccountSession(
                    storage,
                    wire {
                        logins++
                        if (logins > 1) throw IOException("synthetic login unavailable")
                        accountResponse(200, login)
                    },
                    { 100L },
                )
            account.login("contact@example.test", "old-password")
            val client = AccountProfileClient(account, wire { accountResponse(200, "{}") })
            val error = runCatching { client.changePassword("old-password", "new-password") }.exceptionOrNull()
            assertTrue(error is AccountPasswordChangedSignInRequired)
            assertEquals(false, account.signedIn.value)
            assertEquals(null, storage.load())
            assertEquals(2, logins)
        }

    @Test fun cleanupAfterConfirmedChangeCannotClearNewerLogin() =
        runBlocking {
            val account = AccountSession(MemoryAccountTokens(), wire { accountResponse(200, login) }, { 100L })
            account.login("contact@example.test", "old-password")
            val old = account.revision
            account.login("contact@example.test", "newer-session")
            account.invalidateChangedPasswordSession(old)
            assertTrue(account.signedIn.value)
        }

    private fun wire(answer: suspend (WireRequest) -> WireResponse) =
        object : WireClient {
            override suspend fun open(request: WireRequest): WireResponse = answer(request)
        }

    private val login =
        """
        {"access_token":"test-access","refresh_token":"test-refresh","expires_in":3600,"refresh_expires_in":7200,
        "user":{"id":12,"display_name":"original-name","email":"contact@example.test","must_change":false}}
        """.trimIndent()
}

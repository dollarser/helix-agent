package com.helix.app.account

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDisplayNameTest {
    @Test fun normalizationMatchesUnicodeContract() {
        assertEquals("é", normalizedDisplayName(" e\u0301 "))
        assertEquals("😀".repeat(80), normalizedDisplayName("😀".repeat(80)))
        for (invalid in listOf("", "  ", "😀".repeat(81), "a\nb", "a\u200Db", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { normalizedDisplayName(invalid) }
        }
    }

    @Test fun renameUsesPatchAndPreservesSessionAndStableOwnership() =
        runBlocking {
            val calls = mutableListOf<WireRequest>()
            val storage = MemoryAccountTokens()
            val transport =
                wire {
                    calls += it
                    accountResponse(200, if (it.url.endsWith("/auth/login")) login else renamed)
                }
            val account = AccountSession(storage, transport, { 100L })
            account.login("mail@example.test", "synthetic")
            val revision = account.revision
            val identity = account.identityState.value
            val before = AccountTokens.stored(storage.value!!.encoded())
            AccountProfileClient(account).changeDisplayName(" e\u0301 ", revision)
            assertEquals(2, calls.size)
            assertEquals("PATCH", calls.last().method)
            val payload = Json.parseToJsonElement(calls.last().body!!.decodeToString()).jsonObject
            assertEquals(setOf("display_name"), payload.keys)
            assertEquals("é", payload.getValue("display_name").jsonPrimitive.content)
            assertEquals(revision, account.revision)
            assertEquals(identity, account.identityState.value)
            assertEquals("12", account.noticeAccountKey)
            assertEquals("é", account.user.value!!.displayName)
            val after = AccountTokens.stored(storage.value!!.encoded())
            assertEquals(before.access, after.access)
            assertEquals(123L, after.user!!.displayNameUpdatedAt)
            assertFalse(
                calls
                    .last()
                    .headers.keys
                    .any { it.equals("X-Client-ID", true) },
            )
            val loginFields = Json.parseToJsonElement(calls.first().body!!.decodeToString()).jsonObject
            assertEquals(setOf("email", "password"), loginFields.keys)
        }

    @Test fun rejectedRenameDoesNotChangeIdentityOrRetry() =
        runBlocking {
            for (status in listOf(403, 429, 422)) {
                val account = AccountSession(MemoryAccountTokens(), wire { accountResponse(200, login) }, { 100L })
                account.login("mail@example.test", "synthetic")
                val before = account.user.value
                var calls = 0
                val client =
                    AccountProfileClient(
                        account,
                        wire {
                            calls++
                            accountResponse(status, """{"error_code":"display_name_change_limited"}""")
                        },
                    )
                val failure = runCatching { client.changeDisplayName("New") }.exceptionOrNull()
                assertTrue(failure is AccountRequestFailure)
                assertEquals(1, calls)
                assertEquals(before, account.user.value)
            }
        }

    @Test fun staleFormDoesNotReachTransport() =
        runBlocking {
            val account = AccountSession(MemoryAccountTokens(), wire { accountResponse(200, login) }, { 100L })
            account.login("mail@example.test", "synthetic")
            val revision = account.revision
            account.login("mail@example.test", "synthetic")
            val client = AccountProfileClient(account, wire { error("Stale call must not execute") })
            val failure = runCatching { client.changeDisplayName("New", revision) }.exceptionOrNull()
            assertTrue(failure is AccountDataFailure)
        }

    @Test fun differentIdCannotReplaceCurrentUserEvenWithSameName() =
        runBlocking {
            val account = AccountSession(MemoryAccountTokens(), wire { accountResponse(200, login) }, { 100L })
            account.login("mail@example.test", "synthetic")
            val before = account.user.value
            val client =
                AccountProfileClient(
                    account,
                    wire {
                        accountResponse(200, renamed.replace("\"id\":12", "\"id\":13"))
                    },
                )
            val failure = runCatching { client.changeDisplayName("é") }.exceptionOrNull()
            assertTrue(failure is AccountDataFailure)
            assertEquals(before, account.user.value)
        }

    @Test fun registrationOmitsBlankNameAndNormalizesExplicitName() =
        runBlocking {
            val calls = mutableListOf<WireRequest>()
            val account =
                AccountSession(
                    MemoryAccountTokens(),
                    wire {
                        calls += it
                        accountResponse(201, "{}")
                    },
                )
            val client = AccountProfileClient(account)
            client.register("mail@example.test", "synthetic", "012345", "  ")
            client.register("mail@example.test", "synthetic", "012345", " e\u0301 ")
            val first = Json.parseToJsonElement(calls.first().body!!.decodeToString()).jsonObject
            val second = Json.parseToJsonElement(calls.last().body!!.decodeToString()).jsonObject
            assertFalse("display_name" in first)
            assertEquals("é", second.getValue("display_name").jsonPrimitive.content)
        }

    private fun wire(block: suspend (WireRequest) -> WireResponse) =
        object : WireClient {
            override suspend fun open(request: WireRequest) = block(request)
        }

    private val login = """{"access_token":"a","refresh_token":"r","expires_in":600,"refresh_expires_in":1200,
        "user":{"id":12,"display_name":"Before","email":"mail@example.test"}}"""
    private val renamed = """{"user":{"id":12,"display_name":"é","email":"mail@example.test",
        "display_name_updated_at":123}}"""
}

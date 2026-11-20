package com.helix.app.account

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountNoticesClientTest {
    @Test fun listsNoticesWithAccountOnlyAuthenticationAndNoStore() =
        runBlocking {
            val requests = mutableListOf<WireRequest>()
            val client = client(requests, pageDocument)
            val result = client.list(1, AccountNoticeState.UNREAD)
            val request = requests.single()
            assertEquals("https://accounts.hilex.me/account/notices?page=1&state=unread", request.url)
            assertEquals("Bearer fixture-access", request.headers["Authorization"])
            assertFalse(request.headers.keys.any { it.equals("X-Client-ID", true) || it.equals("Cookie", true) })
            assertEquals("no-store", request.headers["Cache-Control"])
            assertEquals(1794780000L, result.items.single().publishedAt)
            assertEquals(null, result.items.single().readAt)
            assertEquals(null, result.items.single().expiresAt)
            assertEquals("important", result.items.single().priority)
            assertEquals("Hello\nWorld", result.items.single().body)
        }

    @Test fun fractionalUnixSecondsAreAccepted() =
        runBlocking {
            val document = pageDocument.replace("1794780000", "1794780000.875")
            assertEquals(
                1794780000L,
                client(mutableListOf(), document)
                    .list()
                    .items
                    .single()
                    .publishedAt,
            )
        }

    @Test fun countAndReadUseExplicitEndpointsAndServerUnread() =
        runBlocking {
            val requests = mutableListOf<WireRequest>()
            val count = client(requests, """{"unread":7}""").unreadCount()
            assertEquals(7, count)
            assertTrue(requests.single().url.endsWith("/account/notices/unread-count"))
            requests.clear()
            val read = client(requests, """{"ok":true,"unread":6}""").markRead(listOf(id))
            assertEquals(6, read)
            assertEquals("POST", requests.single().method)
            assertEquals("""{"ids":["$id"]}""", requests.single().body!!.toString(Charsets.UTF_8))
        }

    @Test fun validatesRequestBoundariesBeforeSending() {
        val requests = mutableListOf<WireRequest>()
        val client = client(requests, pageDocument)
        listOf(0, 1001).forEach { page ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.list(page) } }
        }
        listOf(emptyList(), listOf("bad"), listOf(id, id), List(101) { "%048x".format(it) }).forEach { ids ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.markRead(ids) } }
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun targetOnlyAllowsGeneratedPaginationQuery() {
        val account = testAccount()
        account.wire("https://accounts.hilex.me/account/notices?page=1000&state=all")
        listOf(
            "https://accounts.hilex.me/account/notices?page=0&state=all",
            "https://accounts.hilex.me/account/notices?page=1&state=all&token=secret",
            "https://api.hilex.me/account/notices?page=1&state=all",
            "https://accounts.hilex.me/account/notices?page=1001&state=all",
        ).forEach { target -> assertThrows(IllegalArgumentException::class.java) { account.wire(target) } }
    }

    @Test fun failuresRemainTypedAndDoNotLoop() {
        listOf(403, 404, 422, 429, 503).forEach { status ->
            val requests = mutableListOf<WireRequest>()
            val failure =
                assertThrows(AccountRequestFailure::class.java) {
                    runBlocking { client(requests, """{"detail":"fixture failure"}""", status).markRead(listOf(id)) }
                }
            assertEquals(status, failure.status)
            assertEquals(1, requests.size)
        }
    }

    @Test fun unauthorizedWriteRefreshesCredentialsWithoutAutomaticReplay() =
        runBlocking {
            val requests = mutableListOf<WireRequest>()
            val transport =
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        requests += request
                        val result = """{"ok":true,"unread":0}"""
                        return if (requests.size == 1) accountResponse(401) else accountResponse(200, result)
                    }
                }
            val client = AccountNoticesClient(testAccount(), transport)
            val failure =
                assertThrows(AccountRequestFailure::class.java) {
                    runBlocking { client.markRead(listOf(id)) }
                }
            assertEquals(401, failure.status)
            assertEquals(1, requests.size)
            // A new explicit user action may retry this idempotent write with the refreshed token.
            assertEquals(0, client.markRead(listOf(id)))
            assertEquals(2, requests.size)
            assertEquals(requests[0].body!!.toList(), requests[1].body!!.toList())
            assertEquals("Bearer fixture-renewed", requests[1].headers["Authorization"])
            assertTrue(requests[0].headers["X-Client-Request-ID"] != requests[1].headers["X-Client-Request-ID"])
        }

    @Test fun malformedResponsesDoNotLeakBodyAndFalseAckIsRejected() {
        val failure =
            assertThrows(AccountNoticesFormatFailure::class.java) {
                runBlocking { client(mutableListOf(), "private invalid content").list() }
            }
        assertFalse(failure.message!!.contains("private"))
        assertThrows(AccountNoticesFormatFailure::class.java) {
            runBlocking { client(mutableListOf(), """{"ok":false,"unread":0}""").markRead(listOf(id)) }
        }
        assertThrows(AccountNoticesFormatFailure::class.java) {
            runBlocking {
                val invalidPage = pageDocument.replace("\"page_size\":30", "\"page_size\":31")
                client(mutableListOf(), invalidPage).list()
            }
        }
    }

    @Test fun unrenderableTimestampsAreRejectedButVersionIsNotATimestamp() =
        runBlocking {
            listOf("starts_at", "published_at", "expires_at", "read_at").forEach { field ->
                val original = if (field in setOf("expires_at", "read_at")) "null" else "1794780000"
                val bad = pageDocument.replace("\"$field\":$original", "\"$field\":${Long.MAX_VALUE}")
                assertThrows(AccountNoticesFormatFailure::class.java) {
                    runBlocking { client(mutableListOf(), bad).list() }
                }
            }
            val largeVersion = pageDocument.replace("\"version\":2", "\"version\":${Long.MAX_VALUE}")
            assertEquals(
                Long.MAX_VALUE,
                client(mutableListOf(), largeVersion)
                    .list()
                    .items
                    .single()
                    .version,
            )
        }

    @Test fun signedOutAndLateAccountChangeCannotExposeNotices() =
        runBlocking {
            var called = false
            val delegate =
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        called = true
                        return accountResponse(200, pageDocument)
                    }
                }
            assertThrows(AccountRequestFailure::class.java) {
                runBlocking { AccountNoticesClient(AccountSession(MemoryAccountTokens()), delegate).list() }
            }
            assertFalse(called)
            val account = testAccount()
            val changing =
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        account.logout()
                        return accountResponse(200, pageDocument)
                    }
                }
            val client = AccountNoticesClient(account, changing)
            assertThrows(java.io.IOException::class.java) { runBlocking { client.list() } }
            Unit
        }

    private fun client(
        requests: MutableList<WireRequest>,
        body: String,
        status: Int = 200,
    ) = AccountNoticesClient(
        testAccount(),
        object : WireClient {
            override suspend fun open(request: WireRequest): WireResponse {
                requests += request
                return accountResponse(status, body)
            }
        },
    )

    private companion object {
        val id = "a".repeat(48)
        val pageDocument =
            """
            {"page":1,"page_size":30,"total":1,"unread":1,"items":[{
              "id":"$id","title":"Update","body":"Hello\nWorld","priority":"important",
              "starts_at":1794780000,"expires_at":null,"published_at":1794780000,"version":2,"read_at":null
            }]}
            """.trimIndent()
    }
}

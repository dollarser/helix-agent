package com.helix.app.account

import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountNoticeCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun newInstanceReusesFirstPageOnlyForSameLoginAndFilterWithinFiveHours() {
        val directory = temporary.newFolder()
        AccountNoticeCache(directory).write("account/login-one", AccountNoticeState.ALL, page(), 100000, 50000)
        val restarted = AccountNoticeCache(directory)
        assertEquals(
            "Body",
            restarted
                .read("account/login-one", AccountNoticeState.ALL, 100001, 50001)
                ?.page
                ?.items
                ?.single()
                ?.body,
        )
        assertNull(restarted.read("other/login-two", AccountNoticeState.ALL, 100001, 50001))
        assertNull(restarted.read("account/login-one", AccountNoticeState.UNREAD, 100001, 50001))
        assertNull(
            restarted.read(
                "account/login-one",
                AccountNoticeState.ALL,
                100000 + NOTICE_CACHE_MILLIS,
                50000 + NOTICE_CACHE_MILLIS,
            ),
        )
    }

    @Test fun rejectsClockRollbackForwardJumpRebootAndExpiredNotice() {
        val cache = AccountNoticeCache(temporary.newFolder())
        cache.write("owner", AccountNoticeState.ALL, page(), 100000, 50000)
        assertNull(cache.read("owner", AccountNoticeState.ALL, 99999, 50001))
        assertNull(cache.read("owner", AccountNoticeState.ALL, 200000, 50001))
        assertNull(cache.read("owner", AccountNoticeState.ALL, 100001, 5))
        cache.write("owner", AccountNoticeState.ALL, page(expires = 101), 100000, 50000)
        assertNull(cache.read("owner", AccountNoticeState.ALL, 101000, 51000))
    }

    @Test fun invalidationRemovesBothFiltersAndTemporaryBodies() {
        val directory = temporary.newFolder()
        val cache = AccountNoticeCache(directory)
        AccountNoticeState.entries.forEach { cache.write("owner", it, page(), 100000, 50000) }
        cache.clear()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertNull(AccountNoticeCache(directory).read("owner", AccountNoticeState.ALL, 100001, 50001))
    }

    @Test fun malformedAndOversizedCacheAreNotLoaded() {
        val directory = temporary.newFolder()
        val file = java.io.File(directory, "all.json")
        file.writeText("not json")
        assertNull(AccountNoticeCache(directory).read("owner", AccountNoticeState.ALL, 1, 1))
        file.writeText("x".repeat(2 * 1024 * 1024 + 1))
        assertNull(AccountNoticeCache(directory).read("owner", AccountNoticeState.ALL, 1, 1))
    }

    @Test fun actualSessionRestoreKeepsEarlyInitializedCacheAndLogoutClearsIt() =
        runBlocking {
            val directory = temporary.newFolder()
            val user = AccountUser("account", "name", null, false, false)
            val token = AccountTokens("access", "refresh", 1000000, 2000000, "login", user)
            val cacheDirectory = java.io.File(directory, "notice-pages")
            val wall = System.currentTimeMillis()
            AccountNoticeCache(cacheDirectory).write("account\nlogin", AccountNoticeState.ALL, page(), wall, 50000)
            val account =
                AccountSession(
                    MemoryAccountTokens(token),
                    auth =
                        object : WireClient {
                            override suspend fun open(request: WireRequest) = accountResponse()
                        },
                    now = { 0L },
                    noticeScheduleFile = java.io.File(directory, "schedule"),
                    noticeMonotonicMillis = { 50001L },
                )
            val early = account.notices
            account.restore()
            early.refresh(force = false)
            assertEquals(1, early.state.value.items.size)
            account.logout()
            assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
        }

    private fun page(expires: Long? = null) =
        AccountNoticesPage(
            1,
            30,
            1,
            1,
            listOf(AccountNotice("1".repeat(48), "Title", "Body", "normal", 1, expires, 1, 1, null)),
        )
}

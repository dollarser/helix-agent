package com.helix.app.account

import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Two independent process phases. Synthetic identities/API only; never touches the installed account. */
class AccountNoticeCacheRestartDeviceTest {
    @Suppress("LongMethod") // Keep both process phases and exact request-count assertions together.
    @Test
    fun privatePagesSurviveProcessRestartWithoutQueries() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val phase = InstrumentationRegistry.getArguments().getString("noticeCachePhase")
            assumeTrue(phase in setOf("write", "read"))
            check(instrumentation.targetContext.packageName == "com.helix.validation.settingsfinal.developer")
            val root = File(instrumentation.targetContext.noBackupFilesDir, "notice-restart-fixture")
            val pages = File(root, "pages")
            val pid = File(root, "writer-pid.txt")
            val account = syntheticAccount()
            account.restore()
            val api = CountingNotices()
            val store =
                AccountNoticesStore(
                    account,
                    api,
                    monotonicMillis = SystemClock::elapsedRealtime,
                    cache = AccountNoticeCache(pages),
                )
            if (phase == "write") {
                check(!pid.exists()) { "Use a fresh owned fixture directory" }
                store.refresh(AccountNoticeState.ALL, force = false)
                store.refresh(AccountNoticeState.UNREAD, force = false)
                store.refresh(AccountNoticeState.ALL, force = false)
                assertEquals(2, api.lists)
                pid.writeText(Process.myPid().toString())
                File(root, "write-result.txt").writeText("pid=${Process.myPid()};lists=2;filters=all,unread,all")
            } else {
                assertNotEquals(pid.readText().toInt(), Process.myPid())
                store.refresh(AccountNoticeState.ALL, force = false)
                store.refresh(AccountNoticeState.UNREAD, force = false)
                store.refresh(AccountNoticeState.ALL, force = false)
                assertEquals(0, api.lists)
                assertEquals(
                    "Synthetic persisted body",
                    store.state.value.items
                        .single()
                        .body,
                )
                store.refresh()
                assertEquals(1, api.lists)
                store.markRead(listOf("1".repeat(48)))
                assertEquals(1, api.marks)
                assertEquals(1, api.lists)
                store.refresh(force = false)
                assertEquals(2, api.lists)
                account.login("other", "synthetic-only")
                store.refresh(force = false)
                assertEquals(3, api.lists)
                account.logout()
                store.resetAccount()
                assertTrue(
                    store.state.value.items
                        .isEmpty(),
                )
                assertTrue(pages.listFiles().orEmpty().isEmpty())
                File(root, "read-result.txt").writeText(
                    "pid=${Process.myPid()};reopen_lists=0;manual_lists=1;mark_calls=1;" +
                        "after_mark_lists=2;after_identity_lists=3;logout_cache_empty=true",
                )
            }
        }

    private class CountingNotices : AccountNoticesApi {
        var lists = 0
        var marks = 0
        private var read = false

        override suspend fun list(
            page: Int,
            state: AccountNoticeState,
        ): AccountNoticesPage {
            lists++
            val notice =
                AccountNotice(
                    "1".repeat(48),
                    "Synthetic",
                    "Synthetic persisted body",
                    "normal",
                    1,
                    null,
                    1,
                    1,
                    if (read) 2 else null,
                )
            return AccountNoticesPage(
                1,
                30,
                1,
                if (read) 0 else 1,
                if (state == AccountNoticeState.UNREAD && read) emptyList() else listOf(notice),
            )
        }

        override suspend fun unreadCount() = if (read) 0 else 1

        override suspend fun markRead(ids: List<String>): Int {
            marks++
            read = true
            return 0
        }
    }

    private fun syntheticAccount(): AccountSession =
        AccountSession(
            object : AccountTokenStorage {
                private var value: AccountTokens? =
                    AccountTokens(
                        "synthetic-access",
                        "synthetic-refresh",
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        "notice-restart-login",
                        AccountUser("notice-restart-account", "fixture", null, false, false),
                    )

                override fun load() = value

                override fun save(tokens: AccountTokens) {
                    value = tokens
                }

                override fun clear() {
                    value = null
                }
            },
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse =
                    WireResponse(
                        200,
                        emptyMap(),
                        object : WireBody {
                            override suspend fun bytes() =
                                (
                                    """{"access_token":"synthetic-next","refresh_token":"synthetic-next-refresh",""" +
                                        """"expires_in":600,"refresh_expires_in":1200,""" +
                                        """"user":{"id":"other","username":"other"}}"""
                                ).toByteArray()

                            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                onChunk(bytes())
                            }

                            override fun close() = Unit
                        },
                    )
            },
        )
}

package com.helix.app.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AccountNoticesStoreTest {
    @Test fun refreshKeepsVisibleItemsUntilReplacementAndOnNetworkFailure() =
        runBlocking {
            val api = FakeNotices()
            val store = store(api)
            store.refresh()
            val original = store.state.value.items
            val release = CompletableDeferred<Unit>()
            api.beforeList = {
                release.await()
                throw IOException("offline")
            }
            val refresh = launch(start = CoroutineStart.UNDISPATCHED) { store.refresh() }
            assertTrue(store.state.value.loading)
            assertEquals(original, store.state.value.items)
            release.complete(Unit)
            refresh.join()
            assertFalse(store.state.value.loading)
            assertEquals(original, store.state.value.items)
            assertTrue(store.state.value.failure is IOException)
        }

    @Test fun changingFilterDoesNotShowPreviousFilterItemsWhileLoading() =
        runBlocking {
            val api = FakeNotices()
            val store = store(api)
            store.refresh()
            val release = CompletableDeferred<Unit>()
            api.beforeList = { release.await() }
            val refresh = launch(start = CoroutineStart.UNDISPATCHED) { store.refresh(AccountNoticeState.UNREAD) }
            assertTrue(store.state.value.loading)
            assertTrue(
                store.state.value.items
                    .isEmpty(),
            )
            release.complete(Unit)
            refresh.join()
        }

    @Test fun pageChangesRestartAtFirstPageAndDoNotSkipNewNotices() =
        runBlocking {
            val api = FakeNotices()
            val first = (1..30).map(::notice)
            api.document = page(first, total = 60)
            val store = store(api)
            store.refresh()
            assertTrue(store.state.value.hasMore)
            api.document = page(listOf(notice(99)) + first.take(29), total = 61)
            store.loadMore()
            assertEquals(listOf(1, 1), api.pages)
            assertEquals(
                99.toString().padStart(48, '0'),
                store.state.value.items
                    .first()
                    .id,
            )
            assertEquals(1, store.state.value.page)
        }

    @Test fun stableHeadAllowsNextPageAndDeduplicatesIds() =
        runBlocking {
            val api = FakeNotices()
            api.document = page((1..30).map(::notice), total = 60)
            api.next = page((30..59).map(::notice), total = 60, number = 2)
            val store = store(api)
            store.refresh()
            store.loadMore()
            assertEquals(listOf(1, 1, 2), api.pages)
            assertEquals(59, store.state.value.items.size)
            assertEquals(2, store.state.value.page)
        }

    @Test fun unreadFilterUsesUnreadForPaginationButKeepsGlobalTotal() =
        runBlocking {
            val api = FakeNotices()
            api.document = page(listOf(notice(1)), total = 100, unread = 1)
            val store = store(api)
            store.refresh(AccountNoticeState.UNREAD)
            assertEquals(AccountNoticeState.UNREAD, api.filters.single())
            assertEquals(100, store.state.value.total)
            assertEquals(1, store.state.value.unread)
            assertFalse(store.state.value.hasMore)
        }

    @Test fun markingReadUpdatesCardAndBadgeWithoutAnotherRead() =
        runBlocking {
            val api = FakeNotices()
            api.onRead = { api.document = page(listOf(notice(1).copy(readAt = 123L)), unread = 0) }
            val store = store(api)
            store.refresh()
            store.markRead(listOf(notice(1).id, notice(1).id))
            store.markRead(listOf(notice(1).id))
            assertEquals(listOf(listOf(notice(1).id)), api.reads)
            assertTrue(
                store.state.value.items
                    .single()
                    .isRead,
            )
            assertEquals(
                null,
                store.state.value.items
                    .single()
                    .readAt,
            )
            assertEquals(listOf(1), api.pages)
            assertEquals(0, store.state.value.unread)
        }

    @Test fun staleNotice404RefetchesEffectiveListWithoutRetryingWrite() =
        runBlocking {
            val api = FakeNotices()
            api.onRead = {
                api.document = page(emptyList(), total = 0, unread = 0)
                throw AccountRequestFailure(404)
            }
            val store = store(api)
            store.refresh()
            store.markRead(listOf(notice(1).id))
            assertEquals(1, api.reads.size)
            assertTrue(
                store.state.value.items
                    .isEmpty(),
            )
            assertTrue(store.state.value.invalidated)
            assertEquals(null, store.state.value.failure)
        }

    @Test fun networkFailureNeverReplaysWriteOrInventsReadTimestamp() =
        runBlocking {
            val api = FakeNotices()
            api.onRead = { throw IOException("network") }
            val store = store(api)
            store.refresh()
            store.markRead(listOf(notice(1).id))
            assertEquals(1, api.reads.size)
            assertEquals(
                null,
                store.state.value.items
                    .single()
                    .readAt,
            )
            assertTrue(store.state.value.failure is IOException)
            assertTrue(
                store.state.value.markingRead
                    .isEmpty(),
            )
        }

    @Test fun countPollingIsThrottledAndSignedOutNeverRequests() =
        runBlocking {
            val api = FakeNotices()
            val account = testAccount().also { it.restore() }
            var clock = 0L
            val store = AccountNoticesStore(account, api, { clock })
            store.refreshUnread()
            store.refreshUnread()
            clock = AccountNoticeSchedule.INTERVAL_MILLIS
            store.refreshUnread()
            assertEquals(2, api.counts)
            account.logout()
            store.refreshUnread()
            assertEquals(2, api.counts)
            assertEquals(null, store.state.value.unread)
        }

    @Test fun manualListIsImmediateAndPreventsAutomaticFollowup() =
        runBlocking {
            val api = FakeNotices()
            val store = store(api)
            store.refreshUnread()
            store.refresh()
            store.refresh()
            store.refreshUnread()
            assertEquals(1, api.counts)
            assertEquals(listOf(1, 1), api.pages)
        }

    @Test fun lateResultsAfterLogoutCannotRestoreNoticeState() =
        runBlocking {
            val api = FakeNotices()
            val release = CompletableDeferred<Unit>()
            api.beforeList = { release.await() }
            val account = testAccount().also { it.restore() }
            val store = AccountNoticesStore(account, api)
            val request = launch(start = CoroutineStart.UNDISPATCHED) { store.refresh() }
            account.logout()
            release.complete(Unit)
            request.join()
            assertTrue(
                store.state.value.items
                    .isEmpty(),
            )
            assertEquals(null, store.state.value.unread)
            assertFalse(store.state.value.loading)
        }

    @Test fun readRequestWaitsForCountInsteadOfSilentlyDroppingUserAction() =
        runBlocking {
            val api = FakeNotices()
            val release = CompletableDeferred<Unit>()
            api.beforeCount = { release.await() }
            var now = 0L
            val store = AccountNoticesStore(testAccount().also { it.restore() }, api, { now })
            store.refresh()
            now = AccountNoticeSchedule.INTERVAL_MILLIS
            val count = launch(start = CoroutineStart.UNDISPATCHED) { store.refreshUnread() }
            val read = launch(start = CoroutineStart.UNDISPATCHED) { store.markRead(listOf(notice(1).id)) }
            assertTrue(api.reads.isEmpty())
            release.complete(Unit)
            count.join()
            read.join()
            assertEquals(1, api.reads.size)
        }

    @Test fun newerFilterSuppressesOldResultAndCancellationClearsQueuedLoading() =
        runBlocking {
            val api = FakeNotices()
            val release = CompletableDeferred<Unit>()
            api.beforeList = { release.await() }
            val store = store(api)
            val first = launch(start = CoroutineStart.UNDISPATCHED) { store.refresh() }
            val second = launch(start = CoroutineStart.UNDISPATCHED) { store.refresh(AccountNoticeState.UNREAD) }
            second.cancelAndJoin()
            assertFalse(store.state.value.loading)
            release.complete(Unit)
            first.join()
            assertEquals(AccountNoticeState.UNREAD, store.state.value.filter)
            assertTrue(
                store.state.value.items
                    .isEmpty(),
            )
        }

    @Test fun repeatReadClicksAreCoalescedAfterConfirmedRefresh() =
        runBlocking {
            val api = FakeNotices()
            val release = CompletableDeferred<Unit>()
            api.onRead = {
                release.await()
                api.document = page(listOf(notice(1).copy(readAt = 456L)), unread = 0)
            }
            val store = store(api)
            store.refresh()
            val first = async(start = CoroutineStart.UNDISPATCHED) { store.markRead(listOf(notice(1).id)) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { store.markRead(listOf(notice(1).id)) }
            release.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, api.reads.size)
        }

    @Test fun permissionFailureClearsPreviouslyVisibleItems() =
        runBlocking {
            val api = FakeNotices()
            val store = store(api)
            store.refresh()
            api.onRead = { throw AccountRequestFailure(403) }
            store.markRead(listOf(notice(1).id))
            assertTrue(
                store.state.value.items
                    .isEmpty(),
            )
            assertEquals(null, store.state.value.unread)
            assertEquals(403, (store.state.value.failure as AccountRequestFailure).status)
        }

    @Test fun failedCountIsThrottledAndDoesNotMasqueradeAsZeroUnread() =
        runBlocking {
            val api = FakeNotices()
            api.beforeCount = { throw IOException("network") }
            val store = store(api)
            store.refreshUnread()
            store.refreshUnread()
            assertEquals(1, api.counts)
            assertEquals(null, store.state.value.unread)
            assertTrue(store.state.value.countFailure is IOException)
        }

    @Test fun automaticOpenUsesFiveHourSuccessWindowButManualRefreshBypassesIt() =
        runBlocking {
            var now = 0L
            val api = FakeNotices()
            val store = AccountNoticesStore(testAccount().also { it.restore() }, api, monotonicMillis = { now })
            store.refresh(force = false)
            now = 18_000_000L - 1
            store.refresh(force = false)
            assertEquals(1, api.pages.size)
            now++
            store.refresh(force = false)
            assertEquals(2, api.pages.size)
            store.refresh()
            assertEquals(3, api.pages.size)
        }

    @Test fun failedOrCancelledAutomaticOpenDoesNotConsumeTheWindow() =
        runBlocking {
            val api = FakeNotices()
            val store = store(api)
            api.beforeList = { throw IOException("offline") }
            store.refresh(force = false)
            api.beforeList = {}
            store.refresh(force = false)
            assertEquals(2, api.pages.size)
            val release = CompletableDeferred<Unit>()
            api.beforeList = { release.await() }
            val pending = launch(start = CoroutineStart.UNDISPATCHED) { store.refresh() }
            store.refresh(force = false)
            assertEquals(3, api.pages.size)
            pending.cancelAndJoin()
            api.beforeList = {}
            store.refresh(force = false)
            assertEquals(4, api.pages.size)
        }

    @Test fun cachedEmptyPageIsValidButDifferentFilterAndAccountAreNotReused() =
        runBlocking {
            val account = testAccount().also { it.restore() }
            val api = FakeNotices().apply { document = page(emptyList()) }
            val store = AccountNoticesStore(account, api)
            store.refresh(force = false)
            store.refresh(force = false)
            assertEquals(1, api.pages.size)
            store.refresh(AccountNoticeState.UNREAD, force = false)
            assertEquals(2, api.pages.size)
            account.logout()
            store.refresh(force = false)
            assertTrue(
                store.state.value.items
                    .isEmpty(),
            )
            assertEquals(0, store.state.value.page)
            assertEquals(2, api.pages.size)
        }

    @Test fun restartedStoreUsesDurablePageAndManualFailureInvalidatesIt() =
        runBlocking {
            val directory =
                kotlin.io.path
                    .createTempDirectory("notice-cache-test")
                    .toFile()
            try {
                val account = testAccount().also { it.restore() }
                val api = FakeNotices()

                fun instance() =
                    AccountNoticesStore(
                        account,
                        api,
                        { 100000L },
                        monotonicMillis = { 50000L },
                        cache = AccountNoticeCache(directory),
                    )
                instance().refresh(force = false)
                val restarted = instance()
                restarted.refresh(force = false)
                assertEquals(1, api.pages.size)
                assertEquals(1, restarted.state.value.items.size)
                api.beforeList = { throw IOException("offline") }
                restarted.refresh()
                assertEquals(2, api.pages.size)
                api.beforeList = {}
                instance().refresh(force = false)
                assertEquals(3, api.pages.size)
                account.logout()
                restarted.resetAccount()
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test fun expiredMemoryNoticeIsRemovedEvenWhenRefreshFails() =
        runBlocking {
            var wall = 100000L
            val api = FakeNotices().apply { document = page(listOf(notice(1).copy(expiresAt = 101))) }
            val store = AccountNoticesStore(testAccount().also { it.restore() }, api, { wall })
            store.refresh(force = false)
            wall = 101000L
            api.beforeList = { throw IOException("offline") }
            store.refresh(force = false)
            assertEquals(2, api.pages.size)
            assertTrue(
                store.state.value.items
                    .isEmpty(),
            )
            assertTrue(store.state.value.invalidated)
        }

    @Test fun noticeStoreCreatedBeforeSessionRestoreDoesNotEraseSameLoginCache() =
        runBlocking {
            val directory =
                kotlin.io.path
                    .createTempDirectory("notice-restore-test")
                    .toFile()
            try {
                val firstAccount = testAccount().also { it.restore() }
                val api = FakeNotices()
                val first =
                    AccountNoticesStore(
                        firstAccount,
                        api,
                        { 100000L },
                        monotonicMillis = { 50000L },
                        cache = AccountNoticeCache(directory),
                    )
                first.refresh(force = false)
                val restored = testAccount()
                val early =
                    AccountNoticesStore(
                        restored,
                        api,
                        { 100001L },
                        monotonicMillis = { 50001L },
                        cache = AccountNoticeCache(directory),
                    )
                restored.restore()
                early.resetAccount()
                early.refresh(force = false)
                assertEquals(1, api.pages.size)
                assertEquals(1, early.state.value.items.size)
            } finally {
                directory.deleteRecursively()
            }
        }

    private suspend fun store(api: FakeNotices): AccountNoticesStore =
        AccountNoticesStore(testAccount().also { it.restore() }, api)

    private class FakeNotices : AccountNoticesApi {
        var document = page(listOf(notice(1)))
        var next = page(emptyList(), number = 2)
        val pages = mutableListOf<Int>()
        val filters = mutableListOf<AccountNoticeState>()
        val reads = mutableListOf<List<String>>()
        var counts = 0
        var beforeList: suspend () -> Unit = {}
        var beforeCount: suspend () -> Unit = {}
        var onRead: suspend () -> Unit = {}

        override suspend fun list(
            page: Int,
            state: AccountNoticeState,
        ): AccountNoticesPage {
            pages += page
            filters += state
            beforeList()
            return if (page == 1) document else next
        }

        override suspend fun unreadCount(): Int {
            counts++
            beforeCount()
            return document.unread
        }

        override suspend fun markRead(ids: List<String>): Int {
            reads += ids
            onRead()
            return document.unread
        }
    }

    private companion object {
        fun notice(number: Int) =
            AccountNotice(
                number.toString().padStart(48, '0'),
                "Title",
                "Body",
                "normal",
                1,
                null,
                1,
                1,
                null,
            )

        fun page(
            items: List<AccountNotice>,
            total: Int = items.size,
            unread: Int = total,
            number: Int = 1,
        ) = AccountNoticesPage(number, 30, total, unread, items)
    }
}

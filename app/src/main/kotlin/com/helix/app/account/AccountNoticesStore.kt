package com.helix.app.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Account-scoped notice state with a bounded private first-page cache. Writes never replay after failure. */
@Suppress("TooGenericExceptionCaught")
internal class AccountNoticesStore(
    private val account: AccountSession,
    private val api: AccountNoticesApi = AccountNoticesClient(account),
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val schedule: AccountNoticeSchedule = AccountNoticeSchedule(),
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val cache: AccountNoticeCache = AccountNoticeCache(),
) {
    private val lock = Any()
    private val requests = Mutex()
    private val mutableState =
        MutableStateFlow(
            AccountNoticesState(unread = account.noticeAccountKey?.let(schedule::unread)),
        )
    val state = mutableState.asStateFlow()
    private var owner = Owner(account.revision, account.identity, account.noticeAccountKey)
    private var generation = 0L
    private var firstPage: List<AccountNotice> = emptyList()
    private var refreshedAt: Long? = null

    /** Called synchronously by AccountSession on login/logout, before an old body can be displayed. */
    fun resetAccount() =
        synchronized(lock) {
            if (owner != Owner(account.revision, account.identity, account.noticeAccountKey)) {
                if (owner.identity != "signed-out" || account.identity == "signed-out") cache.clear()
                owner = Owner(account.revision, account.identity, account.noticeAccountKey)
                generation++
                firstPage = emptyList()
                refreshedAt = null
                mutableState.value =
                    AccountNoticesState(
                        unread =
                            if (account.identity !=
                                "signed-out"
                            ) {
                                account.noticeAccountKey?.let(schedule::unread)
                            } else {
                                null
                            },
                    )
            }
        }

    /** Page re-entry reuses successful data; explicit refresh always bypasses this window. */
    suspend fun refresh(
        filter: AccountNoticeState = state.value.filter,
        force: Boolean = true,
    ) {
        val ticket =
            synchronized(lock) {
                resetAccount()
                val before = mutableState.value.withoutExpired(wallClockMillis())
                mutableState.value = before
                if (!force && before.filter == filter) {
                    if (before.loading || before.hasFreshPage(refreshedAt, monotonicMillis())) return
                }
                generation++
                refreshedAt = null
                mutableState.value =
                    if (before.filter == filter) {
                        before.copy(
                            loading = account.signedIn.value,
                            loadingMore = false,
                            markingRead = emptySet(),
                            failure = null,
                            invalidated = before.invalidated,
                        )
                    } else {
                        AccountNoticesState(filter = filter, unread = before.unread, loading = account.signedIn.value)
                    }
                ticket
            }
        try {
            requests.withLock {
                perform(ticket) {
                    val cached =
                        if (force) {
                            null
                        } else {
                            cache.read(
                                ticket.owner.cacheKey,
                                filter,
                                wallClockMillis(),
                                monotonicMillis(),
                            )
                        }
                    if (cached != null) {
                        replace(cached.page, ticket, cached.ageMillis, save = false)
                    } else {
                        cache.invalidate(filter)
                        replace(api.list(1, filter), ticket)
                    }
                }
            }
        } finally {
            update(ticket) { it.copy(loading = false) }
        }
    }

    suspend fun loadMore() {
        val ticket = ticket
        if (!requests.tryLock()) return
        try {
            val before = state.value
            if (!isCurrent(ticket) || !before.hasMore || before.page !in 1..999) return
            update(ticket) { it.copy(loadingMore = true, failure = null, invalidated = false) }
            perform(ticket) {
                val head = api.list(1, before.filter)
                if (!isCurrent(ticket)) return@perform
                if (head.items != firstPage || head.total != before.total || head.unread != before.unread) {
                    replace(head, ticket)
                } else {
                    val next = api.list(before.page + 1, before.filter)
                    update(ticket) {
                        account.noticeAccountKey?.let { key -> schedule.record(key, next.unread, wallClockMillis()) }
                        val merged = (it.items + next.items).distinctBy(AccountNotice::id)
                        it.copy(
                            items = merged,
                            total = next.total,
                            unread = next.unread,
                            page = next.page,
                            hasMore = hasMore(next, merged.size, it.filter),
                        )
                    }
                }
            }
        } finally {
            requests.unlock()
        }
    }

    /** Foreground, online callers share one persisted attempt per account every 24 hours. */
    suspend fun refreshUnread() {
        val ticket = ticket
        if (!requests.tryLock()) return
        try {
            val now = wallClockMillis()
            val key = account.noticeAccountKey
            val claimed = key != null && isCurrent(ticket) && schedule.claim(key, now)
            if (!claimed) return
            try {
                val unread = api.unreadCount()
                update(ticket) {
                    account.noticeAccountKey?.let { key -> schedule.record(key, unread) }
                    it.copy(unread = unread, countFailure = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AccountRequestFailure) {
                update(ticket) {
                    if (error.status in setOf(401, 403)) cache.clear()
                    failureState(it, error).copy(countFailure = error)
                }
            } catch (error: Exception) {
                update(ticket) { failureState(it, error).copy(countFailure = error) }
            }
        } finally {
            requests.unlock()
        }
    }

    suspend fun markRead(ids: List<String>) {
        val ticket = ticket
        requests.withLock {
            if (!isCurrent(ticket)) return@withLock
            val unreadIds = ids.distinct().filter { id -> state.value.items.any { it.id == id && !it.isRead } }
            if (unreadIds.isEmpty()) return@withLock
            require(unreadIds.size <= 100)
            update(ticket) { it.copy(markingRead = unreadIds.toSet(), failure = null, invalidated = false) }
            synchronized(lock) {
                if (isCurrent(ticket)) {
                    cache.clear()
                    refreshedAt = null
                }
            }
            perform(ticket) { readAndRefresh(unreadIds, ticket) }
        }
    }

    private suspend fun readAndRefresh(
        ids: List<String>,
        ticket: Ticket,
    ) {
        try {
            val unread = api.markRead(ids)
            update(ticket) {
                account.noticeAccountKey?.let { key -> schedule.record(key, unread) }
                val marked =
                    it.items.map { notice ->
                        if (notice.id in ids) notice.copy(readConfirmed = true) else notice
                    }
                it.copy(
                    unread = unread,
                    items =
                        if (it.filter == AccountNoticeState.UNREAD) {
                            marked.filterNot { notice -> notice.isRead }
                        } else {
                            marked
                        },
                )
            }
            return
        } catch (failure: AccountRequestFailure) {
            if (failure.status != 404) throw failure
            update(ticket) { it.copy(items = emptyList(), page = 0, hasMore = false, invalidated = true) }
        }
        if (isCurrent(ticket)) replace(api.list(1, state.value.filter), ticket, invalidated = true)
    }

    private suspend fun perform(
        ticket: Ticket,
        operation: suspend () -> Unit,
    ) {
        if (!isCurrent(ticket)) return
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AccountRequestFailure) {
            update(ticket) {
                if (error.status in setOf(401, 403)) cache.clear()
                failureState(it, error)
            }
        } catch (error: Exception) {
            update(ticket) { failureState(it, error) }
        } finally {
            update(ticket) { it.copy(loading = false, loadingMore = false, markingRead = emptySet()) }
        }
    }

    private fun replace(
        page: AccountNoticesPage,
        ticket: Ticket,
        ageMillis: Long = 0,
        save: Boolean = true,
        invalidated: Boolean = false,
    ) = synchronized(lock) {
        if (!isCurrent(ticket)) return@synchronized
        account.noticeAccountKey?.let { key -> schedule.record(key, page.unread, wallClockMillis() - ageMillis) }
        if (save) cache.write(ticket.owner.cacheKey, state.value.filter, page, wallClockMillis(), monotonicMillis())
        firstPage = page.items
        refreshedAt = monotonicMillis() - ageMillis
        val items = page.items.distinctBy(AccountNotice::id)
        mutableState.value =
            mutableState.value.copy(
                items = items,
                total = page.total,
                unread = page.unread,
                page = page.page,
                hasMore = hasMore(page, items.size, state.value.filter),
                failure = null,
                countFailure = null,
                invalidated = invalidated,
            )
    }

    private val ticket: Ticket
        get() =
            synchronized(lock) {
                resetAccount()
                Ticket(owner, generation)
            }

    private fun isCurrent(ticket: Ticket): Boolean =
        synchronized(lock) {
            resetAccount()
            account.signedIn.value && ticket == Ticket(owner, generation)
        }

    private fun update(
        ticket: Ticket,
        change: (AccountNoticesState) -> AccountNoticesState,
    ) = synchronized(lock) {
        if (isCurrent(ticket)) mutableState.value = change(mutableState.value)
    }

    private data class Owner(
        val revision: Long,
        val identity: String,
        val accountKey: String?,
    ) {
        val cacheKey: String get() = "$accountKey\n$identity"
    }

    private data class Ticket(
        val owner: Owner,
        val generation: Long,
    )
}

private fun failureState(
    state: AccountNoticesState,
    error: Exception,
): AccountNoticesState =
    if (error is AccountRequestFailure && error.status in setOf(401, 403)) {
        state.copy(items = emptyList(), page = 0, total = 0, unread = null, hasMore = false, failure = error)
    } else {
        state.copy(failure = error)
    }

private fun hasMore(
    page: AccountNoticesPage,
    loaded: Int,
    filter: AccountNoticeState,
): Boolean =
    page.page < 1000 && page.items.size == page.pageSize &&
        loaded < if (filter == AccountNoticeState.ALL) page.total else page.unread

private fun AccountNoticesState.hasFreshPage(
    refreshedAt: Long?,
    now: Long,
): Boolean {
    val checked = refreshedAt ?: return false
    val eligible = page > 0 && failure == null && !invalidated
    return eligible && now - checked in 0 until NOTICE_CACHE_MILLIS
}

private fun AccountNoticesState.withoutExpired(wall: Long): AccountNoticesState {
    val active = items.filterNot { it.expiresAt != null && it.expiresAt <= wall / 1000 }
    return if (active.size == items.size) this else copy(items = active, hasMore = false, invalidated = true)
}

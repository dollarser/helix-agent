package com.helix.app.account

/** Transient account data. It is never persisted or used as an offline notice cache. */
internal data class AccountNoticesState(
    val filter: AccountNoticeState = AccountNoticeState.ALL,
    val items: List<AccountNotice> = emptyList(),
    val total: Int = 0,
    val unread: Int? = null,
    val page: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val markingRead: Set<String> = emptySet(),
    val failure: Exception? = null,
    val countFailure: Exception? = null,
    val invalidated: Boolean = false,
    val hasMore: Boolean = false,
)

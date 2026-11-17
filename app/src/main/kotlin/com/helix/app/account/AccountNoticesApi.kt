package com.helix.app.account

internal interface AccountNoticesApi {
    suspend fun list(
        page: Int = 1,
        state: AccountNoticeState = AccountNoticeState.ALL,
    ): AccountNoticesPage

    suspend fun unreadCount(): Int

    suspend fun markRead(ids: List<String>): Int
}

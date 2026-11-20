package com.helix.app.ui

import com.helix.app.account.AccountNotice
import com.helix.app.account.AccountNoticeState
import com.helix.app.account.AccountNoticesApi
import com.helix.app.account.AccountNoticesPage
import com.helix.app.account.AccountRequestFailure
import com.helix.app.account.AccountSession
import com.helix.app.account.AccountTokenStorage
import com.helix.app.account.AccountTokens
import kotlinx.coroutines.runBlocking

internal class NoticeUiFixture(
    listStatus: Int? = null,
    readStatus: Int? = null,
) {
    val notice =
        AccountNotice(
            id = "a".repeat(48),
            title = "Synthetic service update",
            body = "<b>Literal notice</b>\nNo HTML execution. https://example.invalid/not-an-action",
            priority = "important",
            startsAt = 1_794_780_000,
            expiresAt = null,
            publishedAt = 1_794_780_000,
            version = 2,
            readAt = null,
        )
    val account =
        AccountSession(
            object : AccountTokenStorage {
                private var value: AccountTokens? =
                    AccountTokens(
                        "synthetic-access",
                        "synthetic-refresh",
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        "notice-ui-fixture",
                    )

                override fun load() = value

                override fun save(tokens: AccountTokens) {
                    value = tokens
                }

                override fun clear() {
                    value = null
                }
            },
        ).also { runBlocking { it.restore() } }
    val api = NoticeUiApi(notice, listStatus, readStatus)
}

internal class NoticeUiApi(
    private val notice: AccountNotice,
    private val listStatus: Int?,
    @Volatile var readStatus: Int?,
) : AccountNoticesApi {
    @Volatile var readCalls = 0
    private var current = notice
    private var withdrawn = false

    override suspend fun list(
        page: Int,
        state: AccountNoticeState,
    ): AccountNoticesPage {
        listStatus?.let { throw AccountRequestFailure(it) }
        val items =
            if (withdrawn || (state == AccountNoticeState.UNREAD && current.readAt != null)) {
                emptyList()
            } else {
                listOf(current)
            }
        return AccountNoticesPage(page, 30, if (withdrawn) 0 else 1, unreadCount(), items)
    }

    override suspend fun unreadCount(): Int = if (withdrawn || current.readAt != null) 0 else 1

    override suspend fun markRead(ids: List<String>): Int {
        check(ids == listOf(notice.id))
        readCalls++
        readStatus?.let {
            if (it == 404) withdrawn = true
            throw AccountRequestFailure(it)
        }
        current = current.copy(readAt = 1_794_780_001)
        return 0
    }
}

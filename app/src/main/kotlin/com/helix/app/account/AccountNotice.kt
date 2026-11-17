package com.helix.app.account

/** Account-scoped server notices. Timestamps are Unix seconds, not Android milliseconds. */
internal data class AccountNotice(
    val id: String,
    val title: String,
    val body: String,
    val priority: String,
    val startsAt: Long,
    val expiresAt: Long?,
    val publishedAt: Long,
    val version: Long,
    val readAt: Long?,
    val readConfirmed: Boolean = false,
) {
    val isRead: Boolean get() = readConfirmed || readAt != null
}

internal data class AccountNoticesPage(
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val unread: Int,
    val items: List<AccountNotice>,
)

internal enum class AccountNoticeState(
    val wireValue: String,
) {
    ALL("all"),
    UNREAD("unread"),
}

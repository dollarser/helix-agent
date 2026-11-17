package com.helix.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.account.AccountNotice
import com.helix.app.account.AccountNoticeState
import com.helix.app.account.AccountNoticesStore
import com.helix.app.account.AccountRequestFailure
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Opening a detail, not listing it, acknowledges reading. Cache ownership stays in the account store. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun AccountNoticesSheet(
    store: AccountNoticesStore,
    onDismiss: () -> Unit,
) {
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selected by remember(store) { mutableStateOf<AccountNotice?>(null) }
    LaunchedEffect(store) { store.refresh(force = false) }
    LaunchedEffect(state.invalidated, state.failure) {
        val status = (state.failure as? AccountRequestFailure)?.status
        if (state.invalidated || status in setOf(401, 403)) selected = null
    }
    UserPageFrame(stringResource(R.string.account_notices_title), "account-notices-page", onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountNoticeState.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = {
                        selected = null
                        scope.launch { store.refresh(filter, force = false) }
                    },
                    label = {
                        Text(
                            stringResource(
                                if (filter ==
                                    AccountNoticeState.ALL
                                ) {
                                    R.string.account_notices_all
                                } else {
                                    R.string.account_notices_unread
                                },
                            ),
                        )
                    },
                    modifier = Modifier.testTag("account-notices-filter-${filter.name}"),
                )
            }
            TextButton({
                selected = null
                scope.launch { store.refresh() }
            }, enabled = !state.loading) {
                Text(stringResource(R.string.account_usage_refresh))
            }
        }
        AccountNoticesStatus(state)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("account-notices-list"),
        ) {
            if (state.loading && state.items.isEmpty()) {
                item { AccountNoticePlaceholders() }
            }
            items(state.items, key = { it.id }) { notice ->
                AccountNoticeRow(notice) {
                    selected = notice
                    if (!notice.isRead) scope.launch { store.markRead(listOf(notice.id)) }
                }
            }
            if (state.hasMore) {
                item {
                    OutlinedButton(
                        { scope.launch { store.loadMore() } },
                        enabled = !state.loading && !state.loadingMore,
                        modifier = Modifier.fillMaxWidth().testTag("account-notices-more"),
                    ) { Text(stringResource(R.string.account_notices_more)) }
                }
            }
        }
    }
    selected?.let { notice ->
        AccountNoticeDetail(
            notice,
            failure = state.failure,
            marking = notice.id in state.markingRead,
            onRetry = { scope.launch { store.markRead(listOf(notice.id)) } },
            onDismiss = { selected = null },
        )
    }
}

@Composable
@Suppress("FunctionName")
internal fun AccountNoticeRow(
    notice: AccountNotice,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag("notice-${notice.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(notice.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (!notice.isRead) Badge { Text(stringResource(R.string.account_notices_unread)) }
        }
        if (notice.priority ==
            "important"
        ) {
            Text(stringResource(R.string.account_notices_important), color = MaterialTheme.colorScheme.primary)
        }
        Text(noticeDate(notice.publishedAt), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
@Suppress("FunctionName")
private fun AccountNoticeDetail(
    notice: AccountNotice,
    failure: Exception?,
    marking: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    UserPageFrame(notice.title, "account-notice-detail-page", onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            failure?.let {
                AccountNoticesFailure(it)
                TextButton(onRetry, enabled = !marking, modifier = Modifier.testTag("account-notice-retry-read")) {
                    Text(stringResource(R.string.account_notices_retry_read))
                }
            }
            if (notice.priority == "important") Text(stringResource(R.string.account_notices_important))
            Text(noticeDate(notice.publishedAt), style = MaterialTheme.typography.bodySmall)
            AccountNoticeBody(notice.body)
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AccountNoticesFailure(error: Exception) {
    val details = (error as? AccountRequestFailure)?.details
    val label = if (details?.status == 403) R.string.account_notices_forbidden else accountFailureLabel(error)
    AccountFailureNotice(label, details)
}

private fun noticeDate(seconds: Long): String =
    DateTimeFormatter
        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(seconds))

@Composable
@Suppress("FunctionName")
private fun AccountNoticesStatus(state: com.helix.app.account.AccountNoticesState) {
    state.failure?.let { AccountNoticesFailure(it) }
    if (state.invalidated) Text(stringResource(R.string.account_notices_changed))
    if (state.loading || state.loadingMore) {
        AccountLoadingStatus(R.string.account_notices_loading, "account-notices-loading")
    }
    val initialLoadCompleted = !state.loading && state.page > 0
    if (initialLoadCompleted && state.items.isEmpty() && state.failure == null) {
        Text(stringResource(R.string.account_notices_empty), Modifier.testTag("account-notices-empty"))
    }
}

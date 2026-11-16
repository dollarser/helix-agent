package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.account.AccountQuota
import com.helix.app.account.AccountUsageSummary
import com.helix.app.account.AccountUsageUnit

@Composable
@Suppress("FunctionName")
internal fun AccountUsageContent(value: AccountUsageSummary) {
    value.quotas.forEach { AccountQuotaContent(it) }
    Text(stringResource(R.string.account_usage_reserved_note), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.account_usage_statistics, value.today), style = MaterialTheme.typography.titleSmall)
    value.totals.forEach { Text(stringResource(R.string.account_usage_count, it.service, it.requests)) }
    if (value.records.isNotEmpty()) {
        Text(stringResource(R.string.account_usage_recent), style = MaterialTheme.typography.titleSmall)
        value.records.forEach { record ->
            Text(
                stringResource(
                    R.string.account_usage_record,
                    record.service,
                    record.status?.toString() ?: stringResource(R.string.account_usage_pending),
                ),
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AccountQuotaContent(quota: AccountQuota) {
    val unit =
        stringResource(
            when (quota.unit) {
                AccountUsageUnit.TOKENS -> R.string.account_usage_tokens
                AccountUsageUnit.MINUTES -> R.string.account_usage_minutes
                AccountUsageUnit.CHARACTERS -> R.string.account_usage_characters
            },
        )
    Text(quota.model, style = MaterialTheme.typography.titleSmall)
    Text("${quota.period} · ${quota.timezone}", style = MaterialTheme.typography.bodySmall)
    accountQuotaResetTime(quota)?.let { reset ->
        Text(
            stringResource(R.string.account_usage_reset_at, reset, quota.timezone),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        stringResource(
            R.string.account_usage_amounts,
            quota.remaining.toPlainString(),
            quota.limit.toPlainString(),
            unit,
            quota.used.toPlainString(),
            quota.reserved.toPlainString(),
        ),
    )
}

internal fun accountQuotaResetTime(quota: AccountQuota): String? {
    val instant = quota.resetAt ?: return null
    return try {
        java.time.format.DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss", java.util.Locale.ROOT)
            .withZone(java.time.ZoneId.of(quota.timezone))
            .format(instant)
    } catch (_: java.time.DateTimeException) {
        null
    }
}

package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.account.AccountQuota
import com.helix.app.account.AccountUsageSummary
import com.helix.app.account.AccountUsageUnit

@Composable
@Suppress("FunctionName")
internal fun AccountUsageContent(value: AccountUsageSummary) {
    value.quotas.forEach { AccountQuotaContent(it) }
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
    val purpose =
        stringResource(
            when (quota.unit) {
                AccountUsageUnit.TOKENS -> R.string.user_usage_conversation
                AccountUsageUnit.MINUTES -> R.string.user_usage_transcription
                AccountUsageUnit.CHARACTERS -> R.string.user_usage_speech
            },
        )
    var expanded by remember(quota.model) { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().testTag("usage-quota-${quota.model}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(purpose, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.user_usage_remaining, accountUsageAmount(quota.remaining), unit),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.user_usage_limit, accountUsageAmount(quota.limit), unit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            accountQuotaResetTime(
                quota,
                java.time.ZoneId
                    .systemDefault()
                    .id,
            )?.let { reset ->
                Text(stringResource(R.string.user_usage_reset, reset), style = MaterialTheme.typography.bodySmall)
            }
            TextButton({ expanded = !expanded }, Modifier.testTag("usage-details-${quota.model}")) {
                Text(stringResource(if (expanded) R.string.user_usage_less else R.string.user_usage_details))
            }
            if (expanded) AccountQuotaDetails(quota, unit)
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AccountQuotaDetails(
    quota: AccountQuota,
    unit: String,
) {
    Column(Modifier.testTag("usage-details-content-${quota.model}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.user_usage_used, accountUsageAmount(quota.used), unit))
    }
}

internal fun accountQuotaResetTime(
    quota: AccountQuota,
    displayTimezone: String = quota.timezone,
): String? {
    val instant = quota.resetAt ?: return null
    return try {
        java.time.format.DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss", java.util.Locale.ROOT)
            .withZone(java.time.ZoneId.of(displayTimezone))
            .format(instant)
    } catch (_: java.time.DateTimeException) {
        null
    }
}

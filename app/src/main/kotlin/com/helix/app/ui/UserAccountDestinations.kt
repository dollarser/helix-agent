package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.account.AccountSession

@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun UserAccountDestinations(
    account: AccountSession,
    usageOpen: Boolean,
    accountOpen: Boolean,
    loading: Boolean,
    onUsageBack: () -> Unit,
    onAccountBack: () -> Unit,
    onLogout: () -> Unit,
    onFailure: (Exception) -> Unit,
    feedback: @Composable () -> Unit,
) {
    if (usageOpen) {
        UserPageFrame(stringResource(R.string.account_usage_title), "user-usage-page", onUsageBack) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                AccountUsageSection(account, onFailure)
            }
        }
    }
    if (accountOpen) UserCloudAccountPage(loading, onAccountBack, onLogout, feedback)
}

@Composable
@Suppress("FunctionName")
private fun UserCloudAccountPage(
    loading: Boolean,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    feedback: @Composable () -> Unit,
) {
    UserPageFrame(stringResource(R.string.user_profile_account_section), "user-account-page", onBack) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                stringResource(R.string.account_signed_in),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            feedback()
            OutlinedButton(onLogout, Modifier.fillMaxWidth().testTag("profile-logout"), enabled = !loading) {
                Text(stringResource(R.string.account_logout))
            }
        }
    }
}

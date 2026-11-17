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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    operationScope: kotlinx.coroutines.CoroutineScope? = null,
    onSignInRequired: () -> Unit = {},
    feedback: @Composable () -> Unit,
) {
    if (usageOpen) {
        UserPageFrame(stringResource(R.string.account_usage_title), "user-usage-page", onUsageBack) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                AccountUsageSection(account, onFailure)
            }
        }
    }
    if (accountOpen) {
        UserCloudAccountPage(
            account,
            loading,
            onAccountBack,
            onLogout,
            operationScope,
            onSignInRequired,
            feedback,
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun UserCloudAccountPage(
    account: AccountSession,
    loading: Boolean,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    operationScope: kotlinx.coroutines.CoroutineScope? = null,
    onSignInRequired: () -> Unit = {},
    feedback: @Composable () -> Unit,
) {
    var changing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    if (changing) {
        UserPageFrame(stringResource(R.string.account_password_change_title), "password-change-page", {
            if (!busy) changing = false
        }) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AccountPasswordChangeForm(
                    account,
                    remember(account) {
                        com.helix.app.account
                            .AccountProfileClient(account)
                    },
                    operationScope = operationScope,
                    onSignInRequired = {
                        changing = false
                        onSignInRequired()
                    },
                    onBusy = { busy = it },
                    onSuccess = {
                        changed = true
                        changing = false
                    },
                )
            }
        }
        return
    }
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
            if (changed) Text(stringResource(R.string.account_password_change_success))
            OutlinedButton(
                {
                    changing = true
                    changed = false
                },
                Modifier.fillMaxWidth().testTag("profile-change-password"),
                enabled = !loading,
            ) { Text(stringResource(R.string.account_password_change_title)) }
            OutlinedButton(onLogout, Modifier.fillMaxWidth().testTag("profile-logout"), enabled = !loading) {
                Text(stringResource(R.string.account_logout))
            }
        }
    }
}

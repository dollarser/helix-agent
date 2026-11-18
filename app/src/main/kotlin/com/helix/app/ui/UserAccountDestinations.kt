package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.account.AccountSession
import com.helix.app.account.LocalUserProfileStore

@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun UserAccountDestinations(
    account: AccountSession,
    readUsage: suspend () -> com.helix.app.account.AccountUsageSummary,
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
                AccountUsageSection(account, onFailure, readUsage)
            }
        }
    }
    if (accountOpen) {
        UserAccountPage(
            account,
            loading,
            onAccountBack,
            onLogout,
            operationScope,
            onSignInRequired,
            feedback = feedback,
        )
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun UserAccountPage(
    account: AccountSession,
    loading: Boolean,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    operationScope: kotlinx.coroutines.CoroutineScope? = null,
    onSignInRequired: () -> Unit = {},
    avatarStore: LocalUserProfileStore? = null,
    feedback: @Composable () -> Unit,
) {
    var changing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var profileBusy by remember { mutableStateOf(false) }
    val signedIn by account.signedIn.collectAsStateWithLifecycle()
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
                        changing = false
                    },
                )
            }
        }
        return
    }
    UserPageFrame(stringResource(R.string.user_profile_account_section), "user-account-page", {
        if (!profileBusy) onBack()
    }) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AccountProfileContent(account, avatarStore, onBusy = { profileBusy = it })
            feedback()
            if (signedIn) {
                UserAccountActions(!loading && !profileBusy, {
                    changing = true
                }, onLogout)
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AccountProfileContent(
    account: AccountSession,
    avatarStore: LocalUserProfileStore?,
    onBusy: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context, avatarStore) { avatarStore ?: LocalUserProfileStore.get(context) }
    val profile by store.state.collectAsStateWithLifecycle()
    val signedIn by account.signedIn.collectAsStateWithLifecycle()
    val user by account.user.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf(false) }
    var avatarBusy by remember { mutableStateOf(false) }
    var nameBusy by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        try {
            store.restore()
        } catch (_: java.io.IOException) {
            failed = true
        }
    }
    AccountAvatarEditor(profile, store, onBusy = {
        avatarBusy = it
        onBusy(it || nameBusy)
    })
    if (failed) Text(stringResource(R.string.user_profile_load_failed))
    if (signedIn) {
        AccountIdentityDetails(account, showName = false)
        if (user != null) {
            AccountDisplayNameForm(account, onBusy = {
                nameBusy = it
                onBusy(it || avatarBusy)
            })
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun UserAccountActions(
    enabled: Boolean,
    onChangePassword: () -> Unit,
    onLogout: () -> Unit,
) {
    OutlinedButton(
        onChangePassword,
        Modifier.fillMaxWidth().testTag("profile-change-password"),
        enabled = enabled,
        border = actionButtonBorder(enabled),
    ) { Text(stringResource(R.string.account_password_change_title)) }
    OutlinedButton(
        onLogout,
        Modifier.fillMaxWidth().testTag("profile-logout"),
        enabled = enabled,
        border = actionButtonBorder(enabled),
    ) { Text(stringResource(R.string.account_logout)) }
}

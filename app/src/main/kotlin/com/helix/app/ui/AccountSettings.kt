package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.account.LocalUserProfileStore
import com.helix.app.account.summary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun AccountAccessEntry(
    presentation: AccountEntryPresentation,
    account: com.helix.app.account.AccountSession,
    onRefreshProviders: () -> Unit,
    onChanged: () -> Unit,
    readUsage: suspend () -> com.helix.app.account.AccountUsageSummary = {
        com.helix.app.account
            .AccountUsageClient(account)
            .read()
            .summary()
    },
) {
    val signedIn by account.signedIn.collectAsStateWithLifecycle()
    val user by account.user.collectAsStateWithLifecycle()
    val status by account.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val profileClient =
        remember(account) {
            com.helix.app.account
                .AccountProfileClient(account)
        }
    var registrationEnabled by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val profileStore = remember(context) { LocalUserProfileStore.get(context) }
    val profile by profileStore.state.collectAsStateWithLifecycle()
    var profileFailure by remember { mutableStateOf(false) }
    val noticeState by account.notices.state.collectAsStateWithLifecycle()
    var destination by remember(signedIn) { mutableStateOf(UserAccountDestination.OVERVIEW) }
    AccountNoticePolling(account)
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(profileStore, open) {
        try {
            profileStore.restore()
            profileFailure = false
        } catch (_: java.io.IOException) {
            profileFailure = true
        }
    }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    var failureDetails by remember { mutableStateOf<com.helix.provider.api.wire.HttpFailureDetails?>(null) }
    var loginRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var recovering by remember { mutableStateOf(false) }
    var passwordSignInRequired by remember { mutableStateOf(false) }
    val recoveryClient =
        remember(account) {
            com.helix.app.account
                .AccountPasswordRecoveryClient(account)
        }
    var confirmLogout by remember { mutableStateOf(false) }

    fun navigate(next: UserAccountDestination) {
        failure = null
        failureDetails = null
        destination = next
    }

    fun perform(operation: suspend () -> Unit) {
        if (loading) return
        loading = true
        failure = null
        failureDetails = null
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = accountFailureLabel(error)
                failureDetails = (error as? com.helix.app.account.AccountRequestFailure)?.details
            } finally {
                onRefreshProviders()
                onChanged()
                loading = false
            }
        }
    }
    AccountProfileRefresh(
        account,
        profileClient,
        open,
        signedIn,
        onChanged,
        onRegistration = { registrationEnabled = it },
        onFailure = { error ->
            failure = accountFailureLabel(error)
            failureDetails = (error as? com.helix.app.account.AccountRequestFailure)?.details
        },
    )
    AccountEntryButton(
        status,
        presentation,
        noticeState.unread ?: 0,
        profile = profile,
        displayName = user?.displayName,
    ) {
        open =
            true
    }
    UserPageHost(open) {
        if (showAccountOverview(open, recovering, destination)) {
            UserPageFrame(stringResource(R.string.user_page_title), "user-page", { if (!loading) open = false }) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    UserProfileHeader(profile, user) { navigate(UserAccountDestination.ACCOUNT) }
                    ProfileLoadFeedback(profileFailure)
                    if (signedIn) {
                        AccountIdentityActions(account, profileClient, operationScope = scope, onPasswordBusy = {
                            loading =
                                it
                        }, onSignInRequired = {
                            whenPasswordSignInRequired(account) { passwordSignInRequired = true }
                        })
                        UserAccountMenu(
                            noticeState.unread,
                            onNotices = { navigate(UserAccountDestination.NOTICES) },
                            onUsage = { navigate(UserAccountDestination.USAGE) },
                            onAccount = { navigate(UserAccountDestination.ACCOUNT) },
                        )
                    } else {
                        AccountLoginForm(
                            loading,
                            onSubmit = { email, password, register, code, name ->
                                perform {
                                    if (register) {
                                        profileClient.register(email, password, code, name)
                                        loginRequest++
                                        showAccountToast(context, R.string.account_registered)
                                    } else {
                                        account.login(email, password)
                                        open = keepAccountOpen(account.user.value)
                                    }
                                }
                            },
                            registrationEnabled = registrationEnabled,
                            loginRequest = loginRequest,
                            onModeChanged = {
                                failure = null
                                failureDetails = null
                            },
                            onSendCode = profileClient::sendRegistrationCode,
                            cooldownMillis = { profileClient.remainingCodeCooldownMillis(false) },
                            onForgotPassword = {
                                failure = null
                                failureDetails = null
                                recovering = true
                            },
                        )
                    }
                    PasswordSignInFeedback(passwordSignInRequired, signedIn)
                    AccountOperationFeedback(failure, failureDetails)
                }
            }
        }
        if (showAccountRecovery(open, recovering)) {
            AccountPasswordRecoveryForm(recoveryClient, { recovering = false }) {
                recovering = false
                loginRequest++
                showAccountToast(context, R.string.account_recovery_success)
                onRefreshProviders()
                onChanged()
            }
        }
        AccountNoticeHost(account, destination == UserAccountDestination.NOTICES, signedIn) {
            destination = UserAccountDestination.OVERVIEW
        }
        UserAccountDestinations(
            account,
            readUsage,
            destination == UserAccountDestination.USAGE,
            destination == UserAccountDestination.ACCOUNT,
            loading,
            onUsageBack = { navigate(UserAccountDestination.OVERVIEW) },
            onAccountBack = { navigate(UserAccountDestination.OVERVIEW) },
            onLogout = { confirmLogout = true },
            operationScope = scope,
            onSignInRequired = {
                whenPasswordSignInRequired(account) {
                    passwordSignInRequired = true
                    destination = UserAccountDestination.OVERVIEW
                }
            },
            onFailure = { error ->
                failure = accountFailureLabel(error)
                failureDetails = (error as? com.helix.app.account.AccountRequestFailure)?.details
            },
            feedback = { AccountOperationFeedback(failure, failureDetails) },
        )
    }
    ConfirmAccountLogout(confirmLogout, { confirmLogout = false }) {
        confirmLogout = false
        perform { account.logout() }
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun AccountProfileRefresh(
    account: com.helix.app.account.AccountSession,
    client: com.helix.app.account.AccountProfileClient,
    open: Boolean,
    signedIn: Boolean,
    onChanged: () -> Unit,
    onRegistration: (Boolean) -> Unit,
    onFailure: (java.io.IOException) -> Unit,
) {
    LaunchedEffect(account) {
        try {
            account.restore()
            onChanged()
            if (account.signedIn.value) client.refreshUser()
        } catch (error: java.io.IOException) {
            onFailure(error)
        }
    }
    LaunchedEffect(open, signedIn) {
        if (!open) return@LaunchedEffect
        try {
            if (signedIn) {
                client.refreshUser()
            } else {
                onRegistration(client.registrationPolicy().enabled)
            }
        } catch (error: java.io.IOException) {
            onFailure(error)
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AccountNoticeHost(
    account: com.helix.app.account.AccountSession,
    open: Boolean,
    signedIn: Boolean,
    onDismiss: () -> Unit,
) {
    if (open && signedIn) AccountNoticesSheet(account.notices, onDismiss)
    LaunchedEffect(signedIn) { if (!signedIn) onDismiss() }
}

@Composable
@Suppress("FunctionName")
private fun AccountOperationFeedback(
    failure: Int?,
    details: com.helix.provider.api.wire.HttpFailureDetails?,
) {
    failure?.let { AccountFailureNotice(it, details) }
}

@Composable
@Suppress("FunctionName")
private fun ConfirmAccountLogout(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_logout_confirm)) },
        confirmButton = { TextButton(onConfirm) { Text(stringResource(R.string.account_logout)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.account_cancel)) } },
    )
}

@Composable
@Suppress("FunctionName")
private fun ProfileLoadFeedback(failed: Boolean) {
    if (failed) Text(stringResource(R.string.user_profile_load_failed))
}

private enum class UserAccountDestination { OVERVIEW, NOTICES, USAGE, ACCOUNT }

private fun keepAccountOpen(user: com.helix.app.account.AccountUser?) =
    user?.let { it.mustChange || it.emailBindingRequired } == true

private fun showAccountOverview(
    open: Boolean,
    recovering: Boolean,
    destination: UserAccountDestination,
): Boolean = open && !recovering && destination == UserAccountDestination.OVERVIEW

private fun showAccountRecovery(
    open: Boolean,
    recovering: Boolean,
): Boolean = open && recovering

package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.account.AccountProfileClient
import com.helix.app.account.AccountRequestFailure
import com.helix.app.account.AccountSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Optional email binding never blocks inference; mandatory initial password change is distinct. */
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun AccountIdentityActions(
    account: AccountSession,
    client: AccountProfileClient,
    challenge: AccountCaptchaContent = defaultAccountCaptchaContent,
    operationScope: kotlinx.coroutines.CoroutineScope? = null,
    onSignInRequired: () -> Unit = {},
    onPasswordBusy: (Boolean) -> Unit = {},
) {
    val user by account.user.collectAsStateWithLifecycle()
    val current = user ?: return
    val revision = account.revision
    var dismissed by remember(current.id) { mutableStateOf(false) }
    var expanded by remember(current.id) { mutableStateOf(false) }
    var email by remember(
        current.id,
    ) { mutableStateOf(current.email ?: current.username.takeIf { '@' in it }.orEmpty()) }
    var code by remember(current.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Exception?>(null) }
    val scope = rememberCoroutineScope()

    fun perform(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            failure = null
            try {
                action()
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (
                error: java.io.IOException,
            ) {
                failure = error
            } finally {
                busy = false
            }
        }
    }
    Column {
        if (current.mustChange) {
            Text(stringResource(R.string.account_initial_password))
            AccountPasswordChangeForm(account, client, operationScope, onSignInRequired, onBusy = onPasswordBusy)
        } else if (current.emailBindingRequired && !dismissed) {
            TextButton(
                {
                    expanded = !expanded
                },
                Modifier.testTag(
                    "account-bind-open",
                ),
                enabled = !busy,
            ) { Text(stringResource(R.string.account_bind_email)) }
            if (expanded) {
                OutlinedTextField(email, {
                    email = it.take(320)
                }, label = { Text(stringResource(R.string.account_email)) }, enabled = !busy, singleLine = true)
                AccountEmailCodeField(
                    email,
                    code,
                    { code = it },
                    !busy && '@' in email,
                    { address, proof -> client.sendBindingCode(address, proof, revision) },
                    action = com.helix.app.account.AccountCaptchaAction.EMAIL_BINDING,
                    identity = revision,
                    cooldownMillis = { client.remainingCodeCooldownMillis(true) },
                    challenge = challenge,
                )
                TextButton(
                    {
                        perform {
                            client.bindEmail(
                                email.trim(),
                                code,
                            )
                        }
                    },
                    enabled = !busy && code.length == 6,
                ) {
                    Text(stringResource(R.string.account_bind_confirm))
                }
                Text(stringResource(R.string.account_bind_keeps_login))
            }
            TextButton(
                {
                    dismissed = true
                },
                Modifier.testTag(
                    "account-bind-later",
                ),
                enabled = !busy,
            ) { Text(stringResource(R.string.account_bind_later)) }
        }
        failure?.let { AccountFailureNotice(accountFailureLabel(it), (it as? AccountRequestFailure)?.details) }
    }
}

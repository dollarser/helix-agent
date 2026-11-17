package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.account.AccountPasswordChangedSignInRequired
import com.helix.app.account.AccountProfileClient
import com.helix.app.account.AccountRequestFailure
import com.helix.app.account.AccountSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Shared ordinary/required change form. Secrets are composition-local and discarded on identity changes. */
@Composable
// Typed confirmed change routes to login, without exposing secrets.
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught", "SwallowedException")
internal fun AccountPasswordChangeForm(
    account: AccountSession,
    client: AccountProfileClient,
    operationScope: CoroutineScope? = null,
    onSignInRequired: () -> Unit = {},
    onBusy: (Boolean) -> Unit = {},
    onSuccess: () -> Unit = {},
) {
    val identity by account.identityState.collectAsStateWithLifecycle()
    val revision = remember(identity) { account.revision }
    var current by remember(identity) { mutableStateOf("") }
    var next by remember(identity) { mutableStateOf("") }
    var confirmation by remember(identity) { mutableStateOf("") }
    var failure by remember(identity) { mutableStateOf<Exception?>(null) }
    var changed by remember(identity) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val localScope = rememberCoroutineScope()
    val scope = operationScope ?: localScope
    val mismatch = confirmation.isNotEmpty() && next != confirmation
    val ready = !busy && !changed && passwordChangeReady(current, next, confirmation)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PasswordChangeInput(
            current,
            { current = it.take(4096) },
            R.string.account_current_password,
            "password-current",
            !busy,
        )
        PasswordChangeInput(next, { next = it.take(4096) }, R.string.account_new_password, "password-new", !busy)
        PasswordChangeInput(
            confirmation,
            { confirmation = it.take(4096) },
            R.string.account_confirm_password,
            "password-confirm",
            !busy,
            mismatch,
        )
        Text(stringResource(R.string.account_password_change_effect))
        if (mismatch) Text(stringResource(R.string.account_password_mismatch))
        Button(
            onClick = {
                if (!busy) {
                    busy = true
                    onBusy(true)
                    failure = null
                    val old = current
                    val replacement = next
                    scope.launch {
                        try {
                            client.changePassword(old, replacement, revision)
                            changed = true
                            onSuccess()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: AccountPasswordChangedSignInRequired) {
                            onSignInRequired()
                        } catch (error: Exception) {
                            if (account.revision == revision) failure = error
                        } finally {
                            current = ""
                            next = ""
                            confirmation = ""
                            busy = false
                            onBusy(false)
                        }
                    }
                }
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().testTag("password-change-submit"),
        ) { Text(stringResource(R.string.account_change_password)) }
        if (changed) Text(stringResource(R.string.account_password_change_success))
        failure?.let { AccountFailureNotice(accountFailureLabel(it), (it as? AccountRequestFailure)?.details) }
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun PasswordChangeInput(
    value: String,
    onValue: (String) -> Unit,
    label: Int,
    tag: String,
    enabled: Boolean,
    error: Boolean = false,
) {
    OutlinedTextField(
        value,
        onValue,
        Modifier.fillMaxWidth().testTag(tag),
        label = { Text(stringResource(label)) },
        enabled = enabled,
        isError = error,
        singleLine = true,
        keyboardOptions = accountPasswordKeyboard,
        visualTransformation = PasswordVisualTransformation(),
    )
}

private fun passwordChangeReady(
    current: String,
    next: String,
    confirmation: String,
): Boolean = current.isNotBlank() && next.isNotBlank() && next.length in 6..128 && next == confirmation

internal fun whenPasswordSignInRequired(
    account: com.helix.app.account.AccountSession,
    action: () -> Unit,
) {
    if (!account.signedIn.value) action()
}

@Composable
@Suppress("FunctionName")
internal fun PasswordSignInFeedback(
    required: Boolean,
    signedIn: Boolean,
) {
    if (required && !signedIn) Text(stringResource(R.string.account_password_change_sign_in))
}

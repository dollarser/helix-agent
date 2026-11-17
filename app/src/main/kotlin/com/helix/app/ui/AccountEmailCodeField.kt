package com.helix.app.ui

import android.os.SystemClock
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.account.AccountCaptchaAction
import com.helix.app.account.AccountCaptchaProof
import com.helix.app.account.AccountRequestFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class EmailCaptchaAttempt(
    val context: EmailCodeContext,
    val send: suspend (
        String,
        AccountCaptchaProof,
    ) -> Long,
)

@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod", "TooGenericExceptionCaught")
internal fun AccountEmailCodeField(
    email: String,
    code: String,
    onCode: (String) -> Unit,
    enabled: Boolean,
    send: (suspend (String, AccountCaptchaProof) -> Long)?,
    action: AccountCaptchaAction = AccountCaptchaAction.REGISTRATION,
    identity: Long = 0,
    cooldownMillis: () -> Long = { 0 },
    challenge: AccountCaptchaContent = defaultAccountCaptchaContent,
) {
    var deadline by rememberSaveable(action, identity) { mutableStateOf(0L) }
    var remaining by remember(action, identity) { mutableStateOf((cooldownMillis() + 999) / 1000) }
    var sending by remember(action, identity) { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf<EmailCaptchaAttempt?>(null) }
    var error by remember { mutableStateOf<Exception?>(null) }
    val scope = rememberCoroutineScope()
    val context =
        remember(
            email,
            identity,
            action,
            enabled,
        ) {
            EmailCodeContext(
                email.trim(),
                identity,
                action,
                enabled,
            )
        }
    val current by rememberUpdatedState(context)
    LaunchedEffect(email, identity, action, enabled) {
        accepted = false
        error = null
        onCode("")
        if (attempt != null) {
            attempt = null
            sending = false
        }
    }
    LaunchedEffect(deadline, sending, action, identity) {
        while (true) {
            remaining = (maxOf(deadline - SystemClock.elapsedRealtime(), cooldownMillis(), 0L) + 999) / 1000
            delay(500)
        }
    }
    OutlinedTextField(
        code,
        { onCode(it.filter { char -> char in '0'..'9' }.take(6)) },
        Modifier.testTag("account-email-code"),
        label = { Text(stringResource(R.string.account_verification_code)) },
        singleLine = true,
        keyboardOptions = accountCodeKeyboard,
        enabled = enabled && !sending,
    )
    TextButton(
        modifier = Modifier.testTag("account-send-code"),
        enabled = enabled && !sending && remaining == 0L && send != null,
        onClick = {
            error = null
            accepted = false
            sending = true
            attempt = EmailCaptchaAttempt(current, requireNotNull(send))
        },
    ) {
        Text(
            if (remaining > 0) {
                stringResource(
                    R.string.account_code_wait,
                    remaining,
                )
            } else {
                stringResource(R.string.account_send_code)
            },
        )
    }
    attempt?.let { pending ->
        challenge(action, {
            if (attempt === pending) {
                attempt = null
                sending = false
            }
        }) { proof ->
            if (attempt === pending && current.accepts(pending.context)) {
                attempt = null
                scope.launch {
                    try {
                        val result = sendEmailAttempt(pending, proof)
                        if (current.accepts(pending.context)) {
                            deadline = SystemClock.elapsedRealtime() + result.waitMillis
                            accepted = result.error == null
                            error = result.error
                        }
                    } finally {
                        sending = false
                    }
                }
            }
        }
    }
    EmailCodeFeedback(accepted, error)
}

private class EmailCodeResult(
    val waitMillis: Long,
    val error: Exception?,
)

@Suppress("TooGenericExceptionCaught") // Keep a typed failure for presentation, never report a send as successful.
private suspend fun sendEmailAttempt(attempt: EmailCaptchaAttempt, proof: AccountCaptchaProof): EmailCodeResult =
    try {
        EmailCodeResult(attempt.send(attempt.context.email, proof).coerceIn(0, 86400) * 1000, null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val wait = (failure as? AccountRequestFailure)?.details?.retryAfterMillis ?: 0L
        EmailCodeResult(wait.coerceIn(0, 86_400_000), failure)
    }

@Composable
@Suppress("FunctionName")
private fun EmailCodeFeedback(
    accepted: Boolean,
    error: Exception?,
) {
    if (accepted) Text(stringResource(R.string.account_code_accepted))
    error?.let { AccountFailureNotice(accountFailureLabel(it), (it as? AccountRequestFailure)?.details) }
}

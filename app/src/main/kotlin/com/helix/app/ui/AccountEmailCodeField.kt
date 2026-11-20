package com.helix.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.account.AccountCaptchaAction
import com.helix.app.account.AccountCaptchaProof
import com.helix.app.account.AccountRequestFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var sending by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Exception?>(null) }
    val verification = remember { EmailCodeVerification() }
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
    DisposableEffect(context) {
        onDispose {
            verification.clear()
        }
    }
    LaunchedEffect(email, identity, action, enabled) {
        accepted = false
        error = null
        onCode("")
    }
    LaunchedEffect(deadline, sending, action, identity) {
        while (true) {
            remaining = (maxOf(deadline - SystemClock.elapsedRealtime(), cooldownMillis(), 0L) + 999) / 1000
            verification.expire()
            delay(500)
        }
    }

    fun submit(
        pending: EmailCaptchaAttempt,
        proof: AccountCaptchaProof,
    ) {
        sending = true
        scope.launch {
            try {
                val result = sendEmailAttempt(pending, proof)
                if (current.accepts(pending.context)) {
                    deadline = SystemClock.elapsedRealtime() + result.waitMillis
                    accepted = result.error == null
                    error = result.error
                }
            } finally {
                proof.discard()
                sending = false
            }
        }
    }
    val busy = sending || verification.pending != null
    val interactive = enabled && !busy
    val available = interactive && remaining == 0L && send != null
    val verificationEnabled = available && !verification.ready(current)
    OutlinedButton(
        onClick = {
            error = null
            accepted = false
            verification.begin(EmailCaptchaAttempt(current, requireNotNull(send)))
        },
        enabled = verificationEnabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-verify-human"),
        shape = RoundedCornerShape(14.dp),
        border = actionButtonBorder(verificationEnabled),
    ) {
        Text(
            stringResource(
                if (verification.ready(current)) {
                    R.string.account_captcha_verified
                } else {
                    R.string.account_captcha_title
                },
            ),
        )
    }
    if (verification.expired) Text(stringResource(R.string.account_captcha_retry))
    AccountEmailCodeInputs(
        code = code,
        onCode = onCode,
        inputEnabled = interactive,
        sendEnabled =
            available && verification.ready(current),
        remaining = remaining,
        onSend = {
            error = null
            accepted = false
            verification.take(current)?.let { submit(it.attempt, it.proof) }
        },
    )
    verification.pending?.let { pending ->
        challenge(action, { verification.cancel(pending) }) { proof ->
            verification.complete(pending, proof, current)
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

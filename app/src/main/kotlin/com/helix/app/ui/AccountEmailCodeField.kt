package com.helix.app.ui

import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.account.AccountRequestFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongParameterList", "LongMethod")
internal fun AccountEmailCodeField(
    email: String,
    code: String,
    onCode: (String) -> Unit,
    enabled: Boolean,
    send: (suspend (String) -> Long)?,
) {
    var remaining by remember { mutableStateOf(0L) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Exception?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(remaining) {
        if (remaining > 0) {
            delay(1000)
            remaining--
        }
    }
    OutlinedTextField(
        code,
        { onCode(it.filter { char -> char in '0'..'9' }.take(6)) },
        Modifier.testTag("account-email-code"),
        label = { Text(stringResource(R.string.account_verification_code)) },
        singleLine = true,
        enabled = enabled,
    )
    TextButton(
        modifier = Modifier.testTag("account-send-code"),
        enabled = enabled && !sending && remaining == 0L && send != null,
        onClick = {
            scope.launch {
                sending = true
                error = null
                try {
                    remaining = requireNotNull(send)(email.trim())
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (failure: java.io.IOException) {
                    error = failure
                    remaining =
                        (
                            (failure as? AccountRequestFailure)
                                ?.details
                                ?.retryAfterMillis
                                ?.plus(999)
                                ?.div(1000) ?: 60L
                        )
                } finally {
                    sending = false
                }
            }
        },
    ) {
        Text(
            if (remaining >
                0
            ) {
                stringResource(R.string.account_code_wait, remaining)
            } else {
                stringResource(R.string.account_send_code)
            },
        )
    }
    error?.let { AccountFailureNotice(accountFailureLabel(it), (it as? AccountRequestFailure)?.details) }
}

package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.helix.app.R
import com.helix.app.account.AccountCaptchaAction
import com.helix.app.account.AccountPasswordRecoveryClient
import com.helix.app.account.AccountRequestFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Credentials live only in this composition; closing or completing destroys the form. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun AccountPasswordRecoveryForm(
    client: AccountPasswordRecoveryClient,
    onBack: () -> Unit,
    challenge: AccountCaptchaContent = defaultAccountCaptchaContent,
    onSuccess: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Exception?>(null) }
    val scope = rememberCoroutineScope()
    val validEmail = email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
    val ready = recoveryInputReady(email, code, password, confirmation)
    val submitEnabled = !busy && ready
    val mismatch = recoveryPasswordsMismatch(password, confirmation)
    UserPageFrame(stringResource(R.string.account_recovery_title), "account-recovery", {
        if (!busy) {
            password = ""
            confirmation = ""
            code = ""
            onBack()
        }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.account_recovery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                email,
                { email = it.take(320) },
                Modifier.fillMaxWidth().testTag("account-reset-email"),
                label = { Text(stringResource(R.string.account_email)) },
                singleLine = true,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
            )
            AccountEmailCodeField(
                email,
                code,
                { code = it },
                !busy && validEmail,
                client::sendCode,
                action = AccountCaptchaAction.PASSWORD_RESET,
                cooldownMillis = client::remainingCooldownMillis,
                challenge = challenge,
            )
            OutlinedTextField(
                password,
                { password = it.take(4096) },
                Modifier.fillMaxWidth().testTag("account-reset-password"),
                label = { Text(stringResource(R.string.account_recovery_new_password)) },
                singleLine = true,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = accountPasswordKeyboard,
                supportingText = { Text(stringResource(R.string.account_recovery_password_rule)) },
            )
            OutlinedTextField(
                confirmation,
                { confirmation = it.take(4096) },
                Modifier.fillMaxWidth().testTag("account-reset-confirm"),
                label = { Text(stringResource(R.string.account_confirm_password)) },
                singleLine = true,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = accountPasswordKeyboard,
                isError = mismatch,
            )
            if (mismatch) {
                Text(
                    stringResource(R.string.account_password_mismatch),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    if (!busy && ready) {
                        busy = true
                        failure = null
                        val submittedEmail = email
                        val submittedCode = code
                        val submittedPassword = password
                        val submittedConfirmation = confirmation
                        scope.launch {
                            try {
                                client.reset(submittedEmail, submittedCode, submittedPassword, submittedConfirmation)
                                password = ""
                                confirmation = ""
                                code = ""
                                onSuccess()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                failure = error
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                enabled = submitEnabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("account-reset-submit"),
                border = actionButtonBorder(submitEnabled, filled = true),
            ) {
                Text(stringResource(if (busy) R.string.account_busy else R.string.account_recovery_submit))
            }
            failure?.let {
                AccountFailureNotice(accountFailureLabel(it), (it as? AccountRequestFailure)?.details)
                if (it is java.io.IOException &&
                    it !is AccountRequestFailure
                ) {
                    Text(stringResource(R.string.account_recovery_uncertain))
                }
            }
        }
    }
}

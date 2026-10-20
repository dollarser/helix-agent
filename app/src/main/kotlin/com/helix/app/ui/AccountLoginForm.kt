package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Form state is ephemeral: never rememberSaveable or persisted, removed on dismissal.
 * Conditional login/register validation shares one form; network operations stay in the service.
 */
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun AccountLoginForm(
    loading: Boolean,
    onSubmit: (String, String, Boolean) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val emailValid = email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
    val ready = !loading && emailValid && password.isNotBlank() && (!register || password == confirmation)
    LaunchedEffect(loading) {
        if (!loading) {
            password = ""
            confirmation = ""
            visible = false
        }
    }

    fun submit() {
        if (ready) {
            focus.clearFocus()
            onSubmit(email.trim(), password, register)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(if (register) R.string.account_create_heading else R.string.account_welcome),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(R.string.account_login_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            email,
            { email = it.take(320) },
            Modifier.fillMaxWidth().testTag("account-email"),
            label = { Text(stringResource(R.string.account_email)) },
            singleLine = true,
            enabled = !loading,
            isError = email.isNotEmpty() && !emailValid,
            supportingText = {
                if (email.isNotEmpty() &&
                    !emailValid
                ) {
                    Text(stringResource(R.string.account_email_invalid))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }),
            shape = RoundedCornerShape(14.dp),
        )
        AccountPasswordField(
            password,
            { password = it.take(4096) },
            visible,
            { visible = !visible },
            !loading,
            if (register) ImeAction.Next else ImeAction.Done,
            { if (register) focus.moveFocus(FocusDirection.Next) else submit() },
        )
        if (register) {
            OutlinedTextField(
                confirmation,
                { confirmation = it.take(4096) },
                Modifier.fillMaxWidth().testTag("account-confirm-password"),
                label = { Text(stringResource(R.string.account_confirm_password)) },
                singleLine = true,
                enabled = !loading,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(14.dp),
                isError = confirmation.isNotEmpty() && confirmation != password,
                supportingText = {
                    if (confirmation.isNotEmpty() &&
                        confirmation != password
                    ) {
                        Text(stringResource(R.string.account_password_mismatch))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
        Button(
            ::submit,
            Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("account-submit"),
            enabled = ready,
            shape = RoundedCornerShape(14.dp),
        ) {
            if (loading) CircularProgressIndicator(Modifier.heightIn(max = 20.dp), strokeWidth = 2.dp)
            Text(
                stringResource(
                    if (loading) {
                        R.string.account_busy
                    } else if (register) {
                        R.string.account_register
                    } else {
                        R.string.account_login
                    },
                ),
            )
        }
        TextButton({
            register = !register
            password = ""
            confirmation = ""
            visible = false
        }, Modifier.fillMaxWidth().testTag("account-switch-mode"), enabled = !loading) {
            Text(stringResource(if (register) R.string.account_back_to_login else R.string.account_create_link))
        }
    }
}

@Composable
@Suppress("FunctionName", "LongParameterList")
private fun AccountPasswordField(
    password: String,
    onChange: (String) -> Unit,
    visible: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    action: ImeAction,
    onAction: () -> Unit,
) {
    OutlinedTextField(
        password,
        onChange,
        Modifier.fillMaxWidth().testTag("account-password"),
        label = { Text(stringResource(R.string.account_password)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onToggle, enabled = enabled, modifier = Modifier.testTag("account-password-visibility")) {
                Text(stringResource(if (visible) R.string.account_hide_password else R.string.account_show_password))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = action),
        keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
        shape = RoundedCornerShape(14.dp),
    )
}

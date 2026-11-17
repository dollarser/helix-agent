package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Shared registration, binding and recovery row; narrow/large-text layouts retain full labels. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun AccountEmailCodeInputs(
    code: String,
    onCode: (String) -> Unit,
    inputEnabled: Boolean,
    sendEnabled: Boolean,
    remaining: Long,
    onSend: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val buttonWidth = 132.dp * fontScale
    val field: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(
            code,
            { onCode(it.filter { char -> char in '0'..'9' }.take(6)) },
            modifier.testTag("account-email-code"),
            label = { Text(stringResource(R.string.account_verification_code)) },
            singleLine = true,
            keyboardOptions = accountCodeKeyboard,
            enabled = inputEnabled,
            shape = RoundedCornerShape(14.dp),
        )
    }
    val button: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(
            onClick = onSend,
            modifier = modifier.heightIn(min = 56.dp).testTag("account-send-code"),
            enabled = sendEnabled,
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                if (remaining > 0) {
                    stringResource(R.string.account_code_wait, remaining)
                } else {
                    stringResource(R.string.account_send_code)
                },
                textAlign = TextAlign.Center,
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("account-code-row")) {
        if (maxWidth >= 140.dp * fontScale + buttonWidth + 12.dp) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                field(Modifier.weight(1f))
                button(Modifier.width(buttonWidth))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field(Modifier.fillMaxWidth())
                button(Modifier.fillMaxWidth())
            }
        }
    }
}

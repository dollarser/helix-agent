package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.account.AccountSession

@Composable
@Suppress("FunctionName")
internal fun AccountIdentityDetails(
    account: AccountSession,
    showName: Boolean = true,
) {
    val user by account.user.collectAsStateWithLifecycle()
    val current = user
    if (current == null) {
        Text(stringResource(R.string.account_identity_unavailable))
        return
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showName) {
                AccountIdentityField(
                    stringResource(R.string.account_display_name),
                    current.displayName.ifBlank { stringResource(R.string.account_identity_unavailable) },
                    "account-display-name",
                )
            }
            AccountIdentityField(
                stringResource(R.string.account_email),
                current.email?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_email_unbound),
                "account-bound-email",
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AccountIdentityField(
    label: String,
    value: String,
    tag: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.bodyLarge)
    }
}

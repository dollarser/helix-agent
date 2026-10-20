package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.HelixApplication
import com.helix.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun AccountSettingsEntry(onChanged: () -> Unit = {}) {
    val application = LocalContext.current.applicationContext as HelixApplication
    val account = application.account
    val signedIn by account.signedIn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var registered by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    LaunchedEffect(account) {
        try {
            account.restore()
            onChanged()
        } catch (_: java.io.IOException) {
            failure = true
        }
    }

    fun perform(operation: suspend () -> Unit) {
        if (loading) return
        loading = true
        failure = false
        registered = false
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = true
            } finally {
                application.readyAppContainerOrNull()?.providerService?.refresh()
                onChanged()
                loading = false
            }
        }
    }
    TextButton({
        open = true
    }) { Text(stringResource(if (signedIn) R.string.account_signed_in else R.string.account_title)) }
    if (open) {
        ConversationSheet(stringResource(R.string.account_title), "account", { if (!loading) open = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_helix),
                        null,
                        Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("Helix", style = MaterialTheme.typography.headlineMedium)
                }
                if (signedIn) {
                    Text(stringResource(R.string.account_signed_in), style = MaterialTheme.typography.titleLarge)
                    OutlinedButton({
                        confirmLogout = true
                    }, Modifier.fillMaxWidth(), enabled = !loading) { Text(stringResource(R.string.account_logout)) }
                } else {
                    AccountLoginForm(loading) { email, password, register ->
                        perform {
                            if (register) {
                                account.register(email, password)
                                registered = true
                            } else {
                                account.login(email, password)
                            }
                        }
                    }
                }
                if (registered) {
                    Text(
                        stringResource(R.string.account_registered),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (failure) Text(stringResource(R.string.account_failure), color = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.account_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.account_logout_confirm)) },
            confirmButton = {
                TextButton({
                    confirmLogout = false
                    perform { account.logout() }
                }) { Text(stringResource(R.string.account_logout)) }
            },
            dismissButton = { TextButton({ confirmLogout = false }) { Text(stringResource(R.string.account_cancel)) } },
        )
    }
}

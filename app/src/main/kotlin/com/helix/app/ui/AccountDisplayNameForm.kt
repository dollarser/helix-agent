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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.account.AccountProfileClient
import com.helix.app.account.AccountRequestFailure
import com.helix.app.account.AccountSession
import com.helix.app.account.isDisplayNameValid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun AccountDisplayNameForm(
    account: AccountSession,
    onBusy: (Boolean) -> Unit = {},
) {
    val identity by account.identityState.collectAsStateWithLifecycle()
    val user by account.user.collectAsStateWithLifecycle()
    val revision = remember(identity) { account.revision }
    var name by remember(identity, user?.displayName) { mutableStateOf(user?.displayName.orEmpty()) }
    var busy by remember(identity) { mutableStateOf(false) }
    var failure by remember(identity) { mutableStateOf<Exception?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(account) { AccountProfileClient(account) }
    val valid = isDisplayNameValid(name)
    val ready = !busy && user != null && valid && name != user?.displayName
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            name,
            {
                name = it
            },
            Modifier.fillMaxWidth().testTag("profile-name"),
            label = { Text(stringResource(R.string.account_display_name)) },
            singleLine = true,
            enabled = !busy,
            isError = name.isNotEmpty() && !valid,
        )
        Text(stringResource(R.string.account_display_name_rule))
        Button(
            onClick = {
                if (!busy) {
                    busy = true
                    onBusy(true)
                    failure = null
                    val submittedName = name
                    scope.launch {
                        try {
                            client.changeDisplayName(submittedName, revision)
                            if (account.revision ==
                                revision
                            ) {
                                showAccountToast(context, R.string.account_display_name_saved)
                            }
                        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                            failure = java.net.SocketTimeoutException()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (account.revision == revision) failure = error
                        } finally {
                            busy = false
                            onBusy(false)
                        }
                    }
                }
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth().testTag("profile-name-save"),
            border = actionButtonBorder(ready),
        ) { Text(stringResource(if (busy) R.string.account_busy else R.string.account_display_name_save)) }
        failure?.let { AccountFailureNotice(accountFailureLabel(it), (it as? AccountRequestFailure)?.details) }
    }
}

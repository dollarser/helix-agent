package com.helix.app.connector

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.plugin.InstalledEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Setup is user-driven and distinct from enabling tools or exchanging an account token. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun OAuthClientSetupSection(
    setup: ConnectorOAuthClientSetup,
    endpoint: InstalledEndpoint,
    redirect: String,
    disabled: Boolean,
    onClientId: (String) -> Unit,
    onBusy: (Boolean) -> Unit,
    onError: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<OAuthClientSetupOffer?>(null) }
    var documentUrl by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    val failure = stringResource(R.string.connector_oauth_client_setup_error)

    fun perform(action: suspend () -> Unit) {
        if (working || disabled) return
        working = true
        onBusy(true)
        onError(null)
        scope.launch {
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                onError(failure)
            } finally {
                working = false
                onBusy(false)
            }
        }
    }
    OutlinedButton(
        enabled = !disabled && !working && redirect.isNotBlank(),
        modifier = Modifier.testTag("oauth-client-inspect"),
        onClick = {
            perform {
                val discovered = withContext(Dispatchers.IO) { setup.inspect(endpoint, redirect) }
                offer = discovered
                withContext(Dispatchers.IO) { setup.remembered(discovered) }?.let { onClientId(it.clientId) }
            }
        },
    ) { Text(stringResource(R.string.connector_oauth_client_setup)) }
    offer?.let { reviewed ->
        OAuthClientSetupOptions(
            reviewed,
            !disabled && !working,
            documentUrl,
            { documentUrl = it },
            useDocument = {
                val url = documentUrl
                perform {
                    val identity = withContext(Dispatchers.IO) { setup.useDocument(endpoint, reviewed, url) }
                    onClientId(identity.clientId)
                }
            },
            register = { confirmation = "register" },
            forget = { confirmation = "forget" },
        )
    }
    val reviewed = offer
    val operation = confirmation
    if (reviewed != null && operation != null) {
        OAuthClientSetupConfirmation(
            offer = reviewed,
            forget = operation == "forget",
            dismiss = { confirmation = null },
            confirm = {
                confirmation = null
                perform {
                    if (operation == "forget") {
                        withContext(Dispatchers.IO) { setup.forget(reviewed) }
                        onClientId("")
                    } else {
                        val identity = withContext(Dispatchers.IO) { setup.register(endpoint, reviewed) }
                        onClientId(identity.clientId)
                    }
                }
            },
        )
    }
}

@Composable
@Suppress("FunctionName")
internal fun OAuthClientSetupOptions(
    offer: OAuthClientSetupOffer,
    enabled: Boolean,
    url: String,
    onUrl: (String) -> Unit,
    useDocument: () -> Unit,
    register: () -> Unit,
    forget: () -> Unit,
) {
    Text(stringResource(R.string.connector_oauth_client_issuer, offer.metadata.issuer, offer.redirect))
    if (offer.metadata.clientIdMetadataDocumentSupported) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrl,
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.connector_oauth_client_document)) },
        )
        OutlinedButton(enabled = enabled && url.startsWith("https://"), onClick = useDocument) {
            Text(stringResource(R.string.connector_oauth_client_use_document))
        }
    } else if (offer.metadata.registrationEndpoint != null) {
        OutlinedButton(enabled = enabled, onClick = register, modifier = Modifier.testTag("oauth-client-register")) {
            Text(stringResource(R.string.connector_oauth_client_register))
        }
    } else {
        Text(stringResource(R.string.connector_oauth_client_manual))
    }
    TextButton(enabled = enabled, onClick = forget) { Text(stringResource(R.string.connector_oauth_client_forget)) }
}

@Composable
@Suppress("FunctionName")
internal fun OAuthClientSetupConfirmation(
    offer: OAuthClientSetupOffer,
    forget: Boolean,
    dismiss: () -> Unit,
    confirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            val title = if (forget) R.string.connector_oauth_client_forget else R.string.connector_oauth_client_register
            Text(stringResource(title))
        },
        text = {
            Column {
                val warning =
                    if (forget) {
                        R.string.connector_oauth_client_forget_warning
                    } else {
                        R.string.connector_oauth_client_register_warning
                    }
                Text(stringResource(warning))
                Text(offer.metadata.issuer)
                if (!forget) Text(offer.metadata.registrationEndpoint.orEmpty())
                Text(offer.redirect)
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, modifier = Modifier.testTag("oauth-client-confirm")) {
                Text(stringResource(R.string.connector_oauth_client_confirm))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

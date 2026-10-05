@file:Suppress("FunctionName", "FunctionNaming") // Compose functions use PascalCase.

package com.helix.app.network

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.HelixTheme
import com.helix.app.R
import com.helix.core.policy.network.NativeNetwork
import java.io.IOException

class NetworkSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HelixTheme { Surface { NetworkSettingsPage { finish() } } } }
    }
}

@Composable
internal fun NetworkSettingsEntry() {
    val context = LocalContext.current
    TextButton(
        onClick = { context.startActivity(Intent(context, NetworkSettingsActivity::class.java)) },
        modifier = Modifier.testTag("settings-network"),
    ) { Text(stringResource(R.string.network_dns_title)) }
}

@Composable
private fun NetworkSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { NativeNetwork.initialize(context.filesDir) }
    var saved by remember { mutableStateOf(settings.text()) }
    var draft by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf(saved) }
    var failure by remember { mutableStateOf(false) }
    val parsed =
        remember(draft) {
            com.helix.core.policy.network.HostsDocument
                .parse(draft)
        }
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-network"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onBack) { Text(stringResource(R.string.common_back)) }
        Text(stringResource(R.string.network_dns_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.network_dns_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.network_hosts_help), style = MaterialTheme.typography.bodySmall)
        HostsTextField(draft, parsed.errors.isNotEmpty()) {
            draft = it
            failure = false
        }
        parsed.errors.take(20).forEach { error ->
            Text(
                stringResource(R.string.network_hosts_error, error.line, stringResource(hostsErrorLabel(error.reason))),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (parsed.errors.isEmpty()) Text(stringResource(R.string.network_hosts_count, parsed.addresses.size))
        Button(onClick = {
            try {
                settings.save(draft)
                saved = draft
                failure = false
            } catch (
                _: IllegalArgumentException,
            ) {
                failure = true
            } catch (
                _: IllegalStateException,
            ) {
                failure = true
            } catch (_: IOException) {
                failure = true
            }
        }, enabled = parsed.errors.isEmpty() && draft != saved, modifier = Modifier.testTag("dns-save")) {
            Text(stringResource(R.string.network_hosts_save))
        }
        if (failure) Text(stringResource(R.string.network_hosts_save_failed), color = MaterialTheme.colorScheme.error)
        if (draft == saved) Text(stringResource(R.string.network_hosts_saved), Modifier.testTag("dns-saved"))
    }
}

@Composable
private fun HostsTextField(
    draft: String,
    hasErrors: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        draft,
        onChange,
        label = { Text(stringResource(R.string.network_hosts_label)) },
        placeholder = {
            Text(
                "# example\n192.0.2.10 api.example.com mirror.example.com\n2001:db8::10 api.example.com",
            )
        },
        modifier = Modifier.fillMaxWidth().testTag("dns-hosts"),
        minLines = 8,
        isError = hasErrors,
        textStyle =
            MaterialTheme.typography.bodyMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
        keyboardOptions =
            androidx.compose.foundation.text.KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
            ),
    )
}

private fun hostsErrorLabel(reason: com.helix.core.policy.network.HostsError.Reason): Int =
    when (reason) {
        com.helix.core.policy.network.HostsError.Reason.INVALID_IP -> R.string.network_hosts_invalid_ip
        com.helix.core.policy.network.HostsError.Reason.MISSING_HOST -> R.string.network_hosts_missing_host
        com.helix.core.policy.network.HostsError.Reason.INVALID_HOST -> R.string.network_hosts_invalid_host
        com.helix.core.policy.network.HostsError.Reason.TOO_LARGE -> R.string.network_hosts_too_large
        com.helix.core.policy.network.HostsError.Reason.TOO_MANY_ADDRESSES -> R.string.network_hosts_too_many
    }

package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.network.LanScopeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName", "LongMethod")
internal fun LanScopeSettingsSection(store: LanScopeStore) {
    val origins by store.origins.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun change(action: () -> Unit) {
        scope.launch {
            busy = true
            failed = false
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (_: IllegalArgumentException) {
                failed = true
            } catch (_: IllegalStateException) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("settings-lan-scopes")) {
        Text(stringResource(R.string.settings_lan_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_lan_note), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            enabled = !busy,
            label = { Text(stringResource(R.string.settings_lan_origin)) },
            modifier = Modifier.testTag("settings-lan-origin"),
        )
        OutlinedButton(onClick = {
            val origin = input
            change { store.add(origin) }
        }, enabled = !busy, modifier = Modifier.testTag("settings-lan-add")) {
            Text(stringResource(R.string.settings_lan_add))
        }
        origins.forEach { origin ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(origin, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { change { store.remove(origin) } },
                    enabled = !busy,
                    modifier = Modifier.testTag("settings-lan-remove"),
                ) {
                    Text(stringResource(R.string.settings_lan_remove))
                }
            }
        }
        if (failed) Text(stringResource(R.string.settings_lan_error), color = MaterialTheme.colorScheme.error)
    }
}

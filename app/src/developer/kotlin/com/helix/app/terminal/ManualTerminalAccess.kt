package com.helix.app.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.ui.AdvancedRiskDialog
import com.helix.core.model.SafetyProfile

/** The backend still rechecks capability at execution; opening this page grants nothing. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun ManualTerminalAccess(
    store: SafetyProfileStore,
    onAvailable: () -> Unit,
    onUnavailable: () -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val profile by store.flow.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(profile) {
        if (profile == SafetyProfile.ADVANCED) onAvailable() else onUnavailable()
    }
    if (profile == SafetyProfile.ADVANCED) {
        content()
    } else {
        Surface {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(16.dp)
                    .testTag("terminal-advanced-required"),
            ) {
                TextButton(onBack) { Text(stringResource(R.string.files_back)) }
                Text(stringResource(R.string.terminal_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.terminal_requires_advanced), Modifier.padding(vertical = 16.dp))
                Button({ confirm = true }, Modifier.testTag("terminal-switch-advanced")) {
                    Text(stringResource(R.string.terminal_switch_and_start))
                }
            }
        }
    }
    AdvancedRiskDialog(confirm, {
        store.switchTo(SafetyProfile.ADVANCED)
        confirm = false
    }, { confirm = false })
}

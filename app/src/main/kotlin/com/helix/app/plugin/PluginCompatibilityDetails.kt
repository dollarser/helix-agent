package com.helix.app.plugin

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.extensions.plugin.PluginComponentState
import com.helix.extensions.plugin.PluginDependencyRequirement
import com.helix.extensions.plugin.PluginDiagnostic

/** Package support is separate from a connection's current readiness and grants no permission. */
@Composable
@Suppress("FunctionName")
internal fun PluginCompatibilityDetails(
    diagnostics: List<PluginDiagnostic>,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(diagnostics) { mutableStateOf(initiallyExpanded) }
    TextButton({ expanded = !expanded }, Modifier.testTag("plugin-compatibility-toggle")) {
        Text(stringResource(R.string.plugin_compatibility))
    }
    if (expanded) {
        diagnostics.forEach { diagnostic ->
            val label =
                when (diagnostic.state) {
                    PluginComponentState.AVAILABLE -> R.string.plugin_compat_supported
                    PluginComponentState.NEEDS_CONFIGURATION -> R.string.connector_session_setup
                    PluginComponentState.METADATA_ONLY -> R.string.plugin_state_metadata
                    else -> R.string.plugin_state_unsupported
                }
            Text("${diagnostic.path} · ${stringResource(label)}", style = MaterialTheme.typography.bodySmall)
            val reason = importSupportReason(diagnostic.code)
            reason?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            if (diagnostic.requirement != PluginDependencyRequirement.NONE) {
                Text(
                    stringResource(
                        when (diagnostic.requirement) {
                            PluginDependencyRequirement.REQUIRED -> R.string.plugin_dependency_required
                            PluginDependencyRequirement.OPTIONAL -> R.string.plugin_dependency_optional
                            else -> R.string.plugin_dependency_runtime
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun importSupportReason(code: String): Int? =
    when (code) {
        "STDIO_REQUIRES_ANDROID_RUNTIME" -> R.string.plugin_import_stdio_hint
        "SKILL_REQUIRES_BINARY" -> R.string.plugin_import_binary_hint
        "DESKTOP_SETUP_NOT_EXECUTED" -> R.string.plugin_import_setup_hint
        else -> null
    }

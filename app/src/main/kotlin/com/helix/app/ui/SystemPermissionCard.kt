package com.helix.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Presentation only. Opening the page never invokes [onAction]. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun SystemPermissionCard(
    title: String,
    description: String,
    status: String?,
    tag: String,
    actionLabel: String,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    SettingsGroup {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onAction, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(actionLabel) }
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status?.let {
            Text(it, Modifier.testTag("$tag-status"), style = MaterialTheme.typography.labelLarge)
        }
    }
}

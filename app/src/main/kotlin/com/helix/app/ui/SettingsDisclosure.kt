package com.helix.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Presentation only: opening a card never changes an authorization rule. */
@Composable
@Suppress("FunctionName")
internal fun SettingsDisclosure(
    title: String,
    tag: String,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.nav_expanded else R.string.nav_collapsed)
    SettingsGroup {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag(tag)
                .semantics { stateDescription = state }
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(if (expanded) "▴" else "▾")
        }
        if (expanded) content()
    }
}

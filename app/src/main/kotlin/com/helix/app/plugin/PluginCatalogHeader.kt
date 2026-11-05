package com.helix.app.plugin

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.helix.app.R

/** Shared hierarchy for native plugins and content-only skills; callers own expansion state. */
@Composable
@Suppress("FunctionName")
internal fun PluginCatalogHeader(
    title: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val expansion = stringResource(if (expanded) R.string.nav_expanded else R.string.nav_collapsed)
    TextButton(onClick, modifier.fillMaxWidth().semantics { stateDescription = expansion }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (expanded) "▴" else "▾")
        }
    }
}

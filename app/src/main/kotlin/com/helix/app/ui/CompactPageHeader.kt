package com.helix.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R

@Composable
@Suppress("FunctionName")
internal fun CompactPageHeader(
    title: String,
    onNavigation: () -> Unit,
    back: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("shell-top-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onNavigation,
            modifier = Modifier.size(48.dp).testTag(if (back) "navigate-back" else "open-navigation"),
        ) {
            if (back) {
                val label = stringResource(R.string.common_back)
                Text(
                    "‹",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = label },
                )
            } else {
                NavigationMenuIcon()
            }
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

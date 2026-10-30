package com.helix.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.indicatedHorizontalScroll

@Composable
@Suppress("FunctionName")
internal fun ComposerOptionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().indicatedHorizontalScroll(rememberScrollState()).testTag("chat-options-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Mode, delivery status, context and secondary actions share the composer's top edge. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun ComposerToolbar(
    mode: com.helix.core.model.AgentMode,
    permissionContent: @Composable () -> Unit,
    headerStatus: @Composable () -> Unit,
    optionsContent: @Composable () -> Unit,
    contextIndicator: @Composable () -> Unit,
    scrollStatus: Boolean = true,
) {
    var options by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().testTag("chat-composer-header"), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(modeLabel(mode)),
            Modifier.testTag("chat-current-mode"),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        permissionContent()
        Row(
            Modifier
                .weight(
                    1f,
                ).then(if (scrollStatus) Modifier.indicatedHorizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) { headerStatus() }
        contextIndicator()
        IconButton({ options = true }, modifier = Modifier.size(48.dp).testTag("chat-composer-options")) {
            Icon(painterResource(R.drawable.ic_more_vertical), stringResource(R.string.chat_composer_options))
        }
    }
    if (options) {
        ConversationSheet(
            stringResource(R.string.chat_composer_options),
            "chat-composer-options",
            { options = false },
        ) {
            optionsContent()
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun ComposerOptionPill(content: @Composable () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

/** Selected plugins share the existing header row when controls can keep their touch targets. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun ComposerHeader(
    mode: com.helix.core.model.AgentMode,
    pluginNames: List<String>,
    onPlugins: () -> Unit,
    permissionContent: @Composable () -> Unit,
    headerStatus: (@Composable () -> Unit)?,
    optionsContent: @Composable () -> Unit,
    contextIndicator: @Composable () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inlinePlugins = pluginNames.isNotEmpty() && headerStatus == null && maxWidth >= 360.dp && fontScale <= 1.2f
        Column(Modifier.fillMaxWidth()) {
            if (pluginNames.isNotEmpty() && !inlinePlugins) ComposerPluginStatus(pluginNames, onPlugins)
            ComposerToolbar(
                mode,
                permissionContent,
                headerStatus = {
                    if (inlinePlugins) {
                        ComposerPluginStatus(pluginNames, onPlugins, Modifier.fillMaxWidth())
                    } else {
                        headerStatus?.invoke()
                    }
                },
                optionsContent,
                contextIndicator,
                scrollStatus = !inlinePlugins,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ComposerPluginStatus(
    names: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick,
        modifier.heightIn(min = 48.dp).testTag("composer-plugins-chip"),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Text(
            stringResource(R.string.composer_plugins, names.joinToString()),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

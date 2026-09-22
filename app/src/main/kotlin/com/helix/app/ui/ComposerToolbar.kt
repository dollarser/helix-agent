package com.helix.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort

@Composable
@Suppress("FunctionName")
internal fun ComposerOptionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("chat-options-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Only mode and model stay in the composer; secondary controls share a bounded sheet. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun ComposerToolbar(
    mode: AgentMode,
    onMode: (AgentMode) -> Unit,
    reasoning: ReasoningEffort,
    reasoningSupported: Boolean,
    onReasoning: (ReasoningEffort) -> Unit,
    isSending: Boolean,
    modelSelector: (@Composable () -> Unit)?,
    reasoningOptions: List<ReasoningEffort> = ReasoningEffort.FALLBACK,
    trailingOptions: @Composable () -> Unit = {},
) {
    var options by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            ComposerOptionRow {
                ComposerOptionPill { ComposerModeMenu(mode, !isSending, onMode) }
                modelSelector?.let { model -> ComposerOptionPill { model() } }
            }
        }
        IconButton({ options = true }, modifier = Modifier.size(48.dp).testTag("chat-composer-options")) {
            Icon(painterResource(R.drawable.ic_chat_more), stringResource(R.string.chat_composer_options))
        }
    }
    if (options) {
        ConversationSheet(
            stringResource(R.string.chat_composer_options),
            "chat-composer-options",
            { options = false },
        ) {
            ComposerReasoningMenu(reasoning, reasoningSupported && !isSending, onReasoning, efforts = reasoningOptions)
            trailingOptions()
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

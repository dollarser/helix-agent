package com.helix.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort

/** All present and future composer options share one horizontally scrollable row. */
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
    trailingOptions: @Composable () -> Unit = {},
) {
    ComposerOptionRow {
        ComposerOptionPill { ComposerModeMenu(mode, !isSending, onMode) }
        modelSelector?.let { model -> ComposerOptionPill { model() } }
        ComposerOptionPill { ComposerReasoningMenu(reasoning, reasoningSupported && !isSending, onReasoning) }
        trailingOptions()
    }
}

/** New option controls use this pill inside ComposerOptionRow. */
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

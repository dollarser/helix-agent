package com.helix.feature.conversation.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.feature.conversation.R
import com.helix.feature.conversation.presentation.ToolTimelineRow

@Composable
@Suppress("FunctionName")
fun ToolTimelineGroup(
    rows: List<ToolTimelineRow>,
    intents: ConversationIntents,
    currentTarget: String?,
) {
    val first = rows.first()
    var expanded by rememberSaveable(first.turnId, first.callId) { mutableStateOf(false) }
    val containsTarget = rows.any { it.callId == currentTarget }
    LaunchedEffect(currentTarget, containsTarget) { if (containsTarget) expanded = true }
    Column(Modifier.fillMaxWidth().testTag("tool-group-${first.callId}")) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("tool-group-toggle-${first.callId}"),
        ) {
            Text(
                stringResource(
                    if (expanded) {
                        R.string.tool_group_hide
                    } else {
                        R.string.tool_group_show
                    },
                    rows.size,
                ),
            )
        }
        if (expanded) rows.forEach { ToolTimelineItem(it, intents, it.callId == currentTarget) }
    }
}

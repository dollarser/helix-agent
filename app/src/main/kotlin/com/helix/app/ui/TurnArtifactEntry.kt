package com.helix.app.ui

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
import com.helix.app.R
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.chat.ChatService

/** The original Turn owns the entry; existing result UI checks availability/hash and handles preview/share. */
@Composable
@Suppress("FunctionName")
internal fun TurnArtifactEntry(
    service: ChatService,
    turnId: String,
    onOpen: (String) -> Unit,
) {
    var rows by remember(turnId) { mutableStateOf(emptyList<ArtifactRowUi>()) }
    LaunchedEffect(turnId) { rows = service.taskArtifactsForTurn(turnId) }
    if (rows.isEmpty()) return
    TextButton(onClick = { onOpen(turnId) }, modifier = Modifier.testTag("turn-artifacts-$turnId")) {
        Text(stringResource(R.string.tool_turn_artifacts, rows.size, rows.first().fileName))
    }
}

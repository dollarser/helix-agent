package com.helix.app.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.chat.ChatService
import com.helix.app.files.FileManagerService

/** The original Turn owns the entry; existing result UI checks availability/hash and handles preview/share. */
@Composable
@Suppress("FunctionName")
internal fun TurnArtifactEntry(
    service: ChatService,
    fileManager: FileManagerService?,
    turnId: String,
) {
    if (fileManager == null) return
    var rows by remember(turnId) { mutableStateOf(emptyList<ArtifactRowUi>()) }
    var open by rememberSaveable(turnId) { mutableStateOf(false) }
    LaunchedEffect(turnId) { rows = service.taskArtifactsForTurn(turnId) }
    if (rows.isEmpty()) return
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("turn-artifacts-$turnId")) {
        Text(stringResource(R.string.tool_turn_artifacts, rows.size, rows.first().fileName))
    }
    if (open) {
        TaskArtifactsDialog(
            service,
            fileManager,
            turnId,
            goalId = null,
            onOpenSession = { open = false },
            onDismiss = { open = false },
        )
    }
}

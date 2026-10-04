package com.helix.app.ui

import androidx.compose.runtime.Composable
import com.helix.app.AppContainer
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.chat.BackgroundTaskUi

/** One detail selection shared by the global and project result lists. */
@Composable
@Suppress("FunctionName")
internal fun ArtifactSelection(
    container: AppContainer,
    selected: BackgroundTaskUi?,
    selectedFile: ArtifactRowUi?,
    onOpenSession: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    selected?.let { row ->
        ArtifactResultDialog(container.chatService, row, { onOpenSession(row.sessionId) }, onDismiss)
    }
    selectedFile?.let { row ->
        ArtifactFileDialog(
            container.fileManager,
            row,
            { onOpenSession(row.sessionId) },
            if (row.turnId != null) onOpenTask else null,
            onDismiss,
        )
    }
}

package com.helix.app.ui

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.chat.ArtifactRowUi
import com.helix.core.workspace.FileScopePath

internal const val FILE_LOCATION_ROUTE = "file-location?reference={reference}"
internal val LocalFileLocation = staticCompositionLocalOf<((FileScopePath) -> Unit)?> { null }

internal fun fileLocationRoute(path: FileScopePath): String =
    "file-location?reference=${Uri.encode(path.toModelReference())}"

@Composable
@Suppress("FunctionName")
internal fun ArtifactLocateAction(
    row: ArtifactRowUi,
    state: ArtifactAvailability,
) {
    val open = LocalFileLocation.current ?: return
    val path = row.parsedScopePath() ?: return
    TextButton(
        { open(path) },
        enabled = state is ArtifactAvailability.Ready,
        modifier = Modifier.testTag("artifact-locate-${row.id}"),
    ) { Text(stringResource(R.string.files_locate)) }
}

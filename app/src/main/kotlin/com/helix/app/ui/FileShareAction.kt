package com.helix.app.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.helix.app.R
import com.helix.app.files.FileManagerService
import com.helix.core.workspace.FileScopePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun FileShareAction(
    files: FileManagerService,
    path: FileScopePath,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember(path) { mutableStateOf(false) }
    var busy by remember(path) { mutableStateOf(false) }
    TextButton({
        busy = true
        failed = false
        scope.launch {
            try {
                val (uri, mime) =
                    withContext(Dispatchers.IO) {
                        val snapshot = files.realFileFor(path.scopeId, path.relativePath)
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            snapshot,
                            path.name,
                        ) to
                            com.helix.app.files.ShareSnapshotCache
                                .mime(snapshot)
                    }
                context.startActivity(
                    Intent.createChooser(
                        fileShareIntent(uri, mime),
                        null,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }, enabled = enabled && !busy, modifier = Modifier.testTag("artifact-share-file")) {
        Text(stringResource(R.string.files_share))
    }
    if (failed) Text(stringResource(R.string.artifacts_share_unavailable))
}

/** Explicit ClipData propagates the same scoped read grant through the chooser to its receiver. */
internal fun fileShareIntent(
    uri: Uri,
    mime: String,
): Intent {
    require(uri.scheme == "content")
    val mediaType = mime.ifBlank { "application/octet-stream" }
    return Intent(Intent.ACTION_SEND).apply {
        type = mediaType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData("Shared file", arrayOf(mediaType), ClipData.Item(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

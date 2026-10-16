package com.helix.app.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.helix.app.R
import com.helix.core.workspace.FileScopePath

/** A readable location plus an explicit, copyable identity; labels never become execution paths. */
@Composable
@Suppress("FunctionName")
internal fun ScopedPathText(
    reference: String,
    displayName: String? = null,
) {
    val clipboard = LocalClipboardManager.current
    val path = runCatching { FileScopePath.fromModelReference(reference) }.getOrNull()
    val name = displayName?.takeIf { it.isNotBlank() && it != path?.scopeId } ?: stringResource(R.string.chat_directory)
    val relative = path?.relativePath.orEmpty()
    var details by rememberSaveable(reference) { mutableStateOf(false) }
    Text(if (relative.isEmpty()) name else "$name / $relative")
    TextButton({ details = !details }) {
        Text(stringResource(if (details) R.string.tool_details_hide else R.string.workspace_location_details))
    }
    if (details) {
        SelectionContainer { Text(reference, style = MaterialTheme.typography.bodySmall) }
        TextButton({ clipboard.setText(AnnotatedString(reference)) }) {
            Text(stringResource(R.string.workspace_copy_location))
        }
    }
}

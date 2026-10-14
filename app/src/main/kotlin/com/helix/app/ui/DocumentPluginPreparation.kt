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
import com.helix.app.plugin.PluginService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Staging a document never implicitly selects a plugin. This action only opens the user's picker. */
@Composable
@Suppress("FunctionName")
internal fun DocumentPluginPreparation(
    service: PluginService,
    sessionId: String,
    pickerOpen: Boolean,
    onSelect: () -> Unit,
) {
    var selected by remember(sessionId) { mutableStateOf(true) }
    LaunchedEffect(service, sessionId, pickerOpen) {
        selected =
            withContext(Dispatchers.IO) {
                service.sessionRows(sessionId).any { it.name == "documents" && it.selected && it.ready }
            }
    }
    if (!selected) {
        TextButton(onClick = onSelect, modifier = Modifier.testTag("chat-document-select-plugin")) {
            Text(stringResource(R.string.document_plugin_select))
        }
    }
}

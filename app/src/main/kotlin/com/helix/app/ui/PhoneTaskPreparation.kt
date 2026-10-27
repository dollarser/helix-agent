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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One task-first entry, visible only where this channel actually supplies Mobile Use. */
@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught") // Failed reads open a retryable page, never imply readiness.
internal fun PhoneTaskPreparation(service: PluginService, onOpen: () -> Unit) {
    var available by remember(service) { mutableStateOf(false) }
    var failed by remember(service) { mutableStateOf(false) }
    LaunchedEffect(service) {
        try {
            available = withContext(Dispatchers.IO) { service.list().any { it.native?.pluginId == "mobile-use" } }
            failed = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    if (available || failed) {
        TextButton(onOpen, modifier = Modifier.testTag("phone-task-prepare")) {
            Text(stringResource(R.string.phone_task_prepare))
        }
    }
}

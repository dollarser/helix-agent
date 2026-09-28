package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.localmodel.LocalModelService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** User supplies an exact GGUF digest and size; no bundled model, implicit download or account. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught", "SwallowedException")
internal fun LocalModelDialog(
    service: LocalModelService,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = {
            job?.cancel()
            onDismiss()
        },
        title = { Text(stringResource(R.string.local_model_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.local_model_description))
                OutlinedTextField(
                    name,
                    { name = it },
                    modifier = Modifier.testTag("local-model-name"),
                    label = { Text(stringResource(R.string.local_model_name)) },
                    enabled =
                        job == null,
                )
                OutlinedTextField(
                    url,
                    { url = it },
                    modifier = Modifier.testTag("local-model-url"),
                    label = { Text(stringResource(R.string.local_model_url)) },
                    enabled =
                        job == null,
                )
                OutlinedTextField(
                    hash,
                    { hash = it },
                    modifier = Modifier.testTag("local-model-hash"),
                    label = { Text("SHA-256") },
                    enabled =
                        job == null,
                )
                OutlinedTextField(
                    size,
                    { size = it },
                    modifier = Modifier.testTag("local-model-size"),
                    label = { Text(stringResource(R.string.local_model_size)) },
                    enabled =
                        job == null,
                )
                if (job != null) Text(stringResource(R.string.local_model_downloading))
                if (failure) Text(stringResource(R.string.local_model_failed), Modifier.testTag("local-model-error"))
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("local-model-download"),
                enabled = job == null && name.isNotBlank() && hash.length == 64 && size.toLongOrNull() != null,
                onClick = {
                    failure = false
                    job =
                        scope.launch {
                            try {
                                service.download(url.trim(), hash.trim(), size.toLong(), name.trim())
                                onChanged()
                                onDismiss()
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                failure =
                                    true
                            } finally {
                                job = null
                            }
                        }
                },
            ) { Text(stringResource(R.string.local_model_download)) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.testTag("local-model-cancel"), onClick = {
                job?.cancel()
                onDismiss()
            }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

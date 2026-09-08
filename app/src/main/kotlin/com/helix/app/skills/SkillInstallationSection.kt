package com.helix.app.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.helix.app.R
import com.helix.extensions.skills.SkillImportPreview
import com.helix.extensions.skills.SkillKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName", "LongMethod") // Native user actions share one review state.
fun SkillInstallationSection(
    authoring: SkillAuthoringService,
    service: SkillInstallationService,
) {
    var path by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<SkillImportPreview?>(null) }
    var installed by remember { mutableStateOf<SkillKey?>(null) }
    var enabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            failed = false
            try {
                block()
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                action {
                    val imported =
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri).use { input ->
                                authoring.importArchive(requireNotNull(input))
                            }
                        }
                    path = imported
                    preview = withContext(Dispatchers.IO) { authoring.preview(imported) }
                    installed = null
                }
            }
        }
    Column {
        Text(stringResource(R.string.skill_installer_title))
        Text(stringResource(R.string.skill_installer_intro))
        OutlinedButton(enabled = !busy, onClick = {
            picker.launch(arrayOf("application/zip", "application/octet-stream"))
        }) { Text(stringResource(R.string.skill_installer_archive)) }
        OutlinedTextField(
            path,
            {
                path = it
                preview = null
                installed = null
            },
            enabled = !busy,
            label = { Text(stringResource(R.string.skill_creator_path)) },
            modifier = Modifier.testTag("skill-installer-path"),
        )
        OutlinedButton(
            enabled = !busy && path.isNotBlank(),
            modifier = Modifier.testTag("skill-installer-preview"),
            onClick = {
                action {
                    preview = withContext(Dispatchers.IO) { authoring.preview(path) }
                    installed = null
                }
            },
        ) {
            Text(stringResource(R.string.skill_creator_preview))
        }
        preview?.let { reviewed ->
            Text("${reviewed.name}\n${reviewed.description}\n${reviewed.snapshotHash}")
            Text(listOfNotNull(reviewed.compatibility, reviewed.declaredAllowedTools).joinToString("\n"))
            Text(reviewed.files.joinToString("\n") { "${it.relativePath} · ${it.sizeBytes} B" })
            OutlinedButton(enabled = !busy, modifier = Modifier.testTag("skill-installer-install"), onClick = {
                action {
                    val key = withContext(Dispatchers.IO) { service.install(path, reviewed.snapshotHash) }
                    installed = key
                    enabled = service.isEnabled(key)
                    preview = null
                }
            }) { Text(stringResource(R.string.skill_installer_install)) }
        }
        installed?.let { key ->
            Text(
                "${key.source} · ${key.name}\n${key.snapshotHash}",
                modifier = Modifier.testTag("skill-installer-result"),
            )
            Text(stringResource(if (enabled) R.string.skill_installer_enabled else R.string.skill_installer_disabled))
            if (!enabled) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        action {
                            withContext(Dispatchers.IO) { service.enable(key) }
                            enabled = service.isEnabled(key)
                        }
                    },
                    modifier =
                        Modifier.testTag(
                            "skill-installer-enable",
                        ),
                ) { Text(stringResource(R.string.skill_installer_enable)) }
            }
        }
        if (failed) Text(stringResource(R.string.skill_installer_failed))
    }
}

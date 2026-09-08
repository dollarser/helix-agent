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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.ui.rememberImportActionState
import com.helix.extensions.skills.SkillImportPreview
import com.helix.extensions.skills.SkillKey
import kotlinx.coroutines.Dispatchers
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
    val action = rememberImportActionState()
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                action.launch {
                    preview = null
                    installed = null
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
        OutlinedButton(enabled = !action.busy, onClick = {
            picker.launch(arrayOf("application/zip", "application/octet-stream"))
        }) { Text(stringResource(R.string.skill_installer_archive)) }
        OutlinedTextField(
            path,
            {
                path = it
                preview = null
                installed = null
            },
            enabled = !action.busy,
            label = { Text(stringResource(R.string.skill_creator_path)) },
            modifier = Modifier.testTag("skill-installer-path"),
        )
        OutlinedButton(
            enabled = !action.busy && path.isNotBlank(),
            modifier = Modifier.testTag("skill-installer-preview"),
            onClick = {
                action.launch {
                    preview = null
                    installed = null
                    preview = withContext(Dispatchers.IO) { authoring.preview(path) }
                }
            },
        ) {
            Text(stringResource(R.string.skill_creator_preview))
        }
        preview?.let { reviewed ->
            Text("${reviewed.name}\n${reviewed.description}\n${reviewed.snapshotHash}")
            Text(listOfNotNull(reviewed.compatibility, reviewed.declaredAllowedTools).joinToString("\n"))
            Text(reviewed.files.joinToString("\n") { "${it.relativePath} · ${it.sizeBytes} B" })
            OutlinedButton(enabled = !action.busy, modifier = Modifier.testTag("skill-installer-install"), onClick = {
                action.launch {
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
                    enabled = !action.busy,
                    onClick = {
                        action.launch {
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
        if (action.failed) {
            Text(
                stringResource(R.string.skill_installer_failed),
                modifier = Modifier.testTag("skill-installer-failed"),
            )
        }
    }
}

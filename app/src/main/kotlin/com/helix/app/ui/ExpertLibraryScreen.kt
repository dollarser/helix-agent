package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.plugin.ExpertAbilityChoice
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.expertPluginChoices
import com.helix.app.plugin.expertSkillChoices
import com.helix.core.storage.repository.ExpertDefinition
import com.helix.core.storage.repository.ExpertLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun CapabilitiesScreen(
    chat: ChatService,
    plugins: PluginService,
    initialTab: Int = 0,
    pluginContent: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    Column(Modifier.fillMaxSize().testTag("capabilities-screen")) {
        PrimaryTabRow(tab) {
            listOf(R.string.expert_tab_plugins, R.string.expert_tab_experts).forEachIndexed { index, label ->
                Tab(
                    tab == index,
                    { tab = index },
                    text = { Text(stringResource(label)) },
                    modifier = Modifier.testTag("capabilities-tab-$index"),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) pluginContent() else ExpertLibraryScreen(chat, plugins)
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun ExpertLibraryScreen(
    chat: ChatService,
    plugins: PluginService,
) {
    var definitions by remember { mutableStateOf(emptyList<ExpertDefinition>()) }
    var pluginChoices by remember { mutableStateOf(emptyList<ExpertAbilityChoice>()) }
    var skillChoices by remember { mutableStateOf(emptyList<ExpertAbilityChoice>()) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var actionFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(chat, plugins, revision) {
        loaded = false
        failed = false
        try {
            definitions = chat.savedExperts().await()
            withContext(Dispatchers.IO) {
                pluginChoices = plugins.expertPluginChoices()
                skillChoices = plugins.expertSkillChoices()
            }
            loaded = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("expert-library"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = loaded, onClick = {
                importing = false
                editorId =
                    java.util.UUID
                        .randomUUID()
                        .toString()
            }, modifier = Modifier.testTag("expert-create")) { Text(stringResource(R.string.expert_create)) }
            TextButton(enabled = loaded, onClick = {
                importing = true
                editorId =
                    java.util.UUID
                        .randomUUID()
                        .toString()
            }, modifier = Modifier.testTag("expert-import")) { Text(stringResource(R.string.expert_import)) }
        }
        if (failed) {
            Text(stringResource(R.string.expert_load_failed), color = MaterialTheme.colorScheme.error)
            TextButton({ revision++ }) { Text(stringResource(R.string.chat_retry)) }
        }
        ExpertLibraryEmpty(loaded, definitions)
        if (copied) Text(stringResource(R.string.expert_configuration_copied))
        if (actionFailed) Text(stringResource(R.string.session_expert_save_failed))
        definitions.forEach { definition ->
            val profile = definition.profile
            Column(Modifier.fillMaxWidth()) {
                ConversationMenuRow(
                    profile.displayName,
                    {
                        importing = false
                        editorId = profile.id
                    },
                    Modifier.testTag("expert-edit-${profile.id}"),
                    supporting = definition.description.ifBlank { profile.instruction.take(120) },
                )
                Text(
                    stringResource(
                        R.string.expert_dependency_count,
                        profile.recommendedConnectorIds.size,
                        profile.recommendedSkillIds.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (expertDependenciesUnavailable(definition, pluginChoices, skillChoices)) {
                    Text(
                        stringResource(R.string.expert_missing_dependencies),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(ExpertLibraryRepository.export(definition)))
                    copied = true
                }, modifier = Modifier.testTag("expert-export-${profile.id}")) {
                    Text(stringResource(R.string.expert_copy_configuration))
                }
                TextButton(
                    onClick = { deletingId = profile.id },
                    enabled = !deleting,
                    modifier = Modifier.testTag("expert-delete-${profile.id}"),
                ) {
                    Text(stringResource(R.string.expert_delete))
                }
            }
        }
    }
    editorId?.takeIf { loaded }?.let { id ->
        ExpertEditor(
            id,
            definitions.find { it.profile.id == id },
            importing,
            chat,
            pluginChoices,
            skillChoices,
            onDismiss = { editorId = null },
            onSaved = {
                editorId = null
                revision++
            },
        )
    }
    deletingId?.let { id ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deletingId = null },
            text = { Text(stringResource(R.string.expert_delete_confirmation)) },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    actionFailed = false
                    scope.launch {
                        try {
                            check(chat.removeExpert(id).await())
                            deletingId = null
                            revision++
                        } catch (
                            cancel: CancellationException,
                        ) {
                            throw cancel
                        } catch (_: Exception) {
                            actionFailed = true
                        } finally {
                            deleting = false
                        }
                    }
                }) { Text(stringResource(R.string.expert_delete)) }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { deletingId = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun ExpertLibraryEmpty(
    loaded: Boolean,
    definitions: List<ExpertDefinition>,
) {
    if (loaded && definitions.isEmpty()) Text(stringResource(R.string.expert_library_empty))
}

internal fun expertDependenciesUnavailable(
    definition: ExpertDefinition,
    plugins: List<ExpertAbilityChoice>,
    skills: List<ExpertAbilityChoice>,
): Boolean =
    definition.profile.recommendedConnectorIds.any { id -> plugins.none { it.reference == id && it.available } } ||
        definition.profile.recommendedSkillIds.any { id -> skills.none { it.reference == id && it.available } }

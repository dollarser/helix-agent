package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.expertPluginChoices
import com.helix.app.plugin.expertSkillChoices
import com.helix.core.storage.repository.ExpertDefinition
import com.helix.core.storage.repository.ExpertProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session selection only. Global authoring lives in the shared capabilities page. */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
internal fun SessionExpertDialog(
    chatService: ChatService,
    sessionId: String,
    onManage: () -> Unit = {},
    plugins: PluginService? = null,
    onDismiss: () -> Unit,
) {
    var profiles by remember { mutableStateOf(emptyList<ExpertDefinition>()) }
    var selected by remember { mutableStateOf<ExpertProfile?>(null) }
    var unavailable by remember { mutableStateOf(emptySet<String>()) }
    var dependencyNames by remember { mutableStateOf(emptyMap<String, String>()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(chatService, sessionId) {
        try {
            profiles = chatService.savedExperts().await()
            selected = chatService.loadSessionExpert(sessionId).await()
            if (plugins != null) {
                withContext(Dispatchers.IO) {
                    val p = plugins.expertPluginChoices()
                    val s = plugins.expertSkillChoices()
                    dependencyNames = (p + s).associate { it.reference to it.name }
                    unavailable =
                        profiles
                            .filter { expertDependenciesUnavailable(it, p, s) }
                            .map { it.profile.id }
                            .toSet()
                }
            }
            loaded = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }

    fun choose(id: String?) {
        busy = true
        scope.launch {
            try {
                check(chatService.selectExpert(sessionId, id).await())
                onDismiss()
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
    ConversationSheet(
        stringResource(R.string.expert_select),
        "session-expert",
        { if (!busy) onDismiss() },
        footer = {
            TextButton(
                enabled = !busy,
                onClick = onDismiss,
                modifier = Modifier.testTag("expert-cancel"),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().testTag("session-expert-dialog"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.expert_selection_hint), style = MaterialTheme.typography.bodySmall)
            ConversationMenuRow(
                stringResource(R.string.expert_default),
                { choose(null) },
                Modifier.testTag("session-expert-clear"),
                enabled = loaded && !busy,
                selected = selected == null,
            )
            ExpertSelectionStatus(selected, profiles, loaded)
            profiles.forEach { definition ->
                val profile = definition.profile
                val names =
                    (profile.recommendedConnectorIds + profile.recommendedSkillIds)
                        .take(4)
                        .joinToString(" · ") { dependencyNames[it] ?: it.take(48) }
                ConversationMenuRow(
                    profile.displayName,
                    { choose(profile.id) },
                    Modifier.testTag("expert-row-${profile.id}"),
                    supporting =
                        listOf(definition.description.ifBlank { profile.instruction.take(120) }, names)
                            .filter(String::isNotBlank)
                            .joinToString("\n"),
                    enabled = loaded && !busy,
                    selected = selected == profile,
                )
                if (profile.id in unavailable) {
                    Text(
                        stringResource(R.string.expert_missing_dependencies),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (failed) Text(stringResource(R.string.expert_load_failed), color = MaterialTheme.colorScheme.error)
            ConversationMenuRow(
                stringResource(R.string.expert_manage),
                onManage,
                Modifier.testTag("expert-manage"),
                enabled = !busy,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ExpertSelectionStatus(
    selected: ExpertProfile?,
    profiles: List<ExpertDefinition>,
    loaded: Boolean,
) {
    selected?.let { current ->
        Text(stringResource(R.string.expert_current, current.displayName))
        if (profiles.none { it.profile == current }) Text(stringResource(R.string.expert_snapshot_retained))
    }
    if (loaded && profiles.isEmpty()) Text(stringResource(R.string.expert_library_empty))
}

package com.helix.app.ui

import androidx.compose.material3.AlertDialog
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.AppContainer
import com.helix.app.R
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

/** No automatic navigation or configuration: each proposal requires a visible user action. */
@Composable
@Suppress("FunctionName", "ReturnCount", "LongMethod") // Optional service, matching session and idle-only UI.
internal fun HelixSettingsProposalDialog(container: AppContainer, onNavigate: (String) -> Unit) {
    val requests = container.settingsRequests ?: return
    val proposals by requests.pending.collectAsStateWithLifecycle()
    val screen by container.chatService.screen.collectAsStateWithLifecycle()
    val providers by container.providerService.rows.collectAsStateWithLifecycle()
    val proposal = proposals.firstOrNull { it.sessionId == screen.openSessionId } ?: return
    if (screen.isSending) return
    val scope = rememberCoroutineScope()
    var failure by remember(proposal.id) { mutableStateOf(false) }
    var applying by remember(proposal.id) { mutableStateOf(false) }
    val values = proposal.values
    val fields = listOf("mode", "providerId", "model", "reasoning").filter { values[it] != null }
    val providerNames = providers.associate { it.id to it.displayName }
    val labels =
        mapOf(
            "mode" to stringResource(R.string.session_settings_mode),
            "providerId" to stringResource(R.string.nav_models),
            "model" to stringResource(R.string.session_settings_model),
            "reasoning" to stringResource(R.string.helix_settings_reasoning),
        )
    val destination = stringResource(settingsPageLabel(values["page"]?.jsonPrimitive?.content))
    AlertDialog(
        onDismissRequest = { if (!applying) requests.remove(proposal.id) },
        title = { Text(stringResource(R.string.helix_settings_proposal_title)) },
        text = {
            Text(
                if (failure) {
                    stringResource(R.string.helix_settings_proposal_failed)
                } else {
                    stringResource(R.string.helix_settings_proposal_body) + "\n" +
                        if (fields.isEmpty()) {
                            destination
                        } else {
                            fields.joinToString("\n") {
                                val value = values[it]?.jsonPrimitive?.content
                                val display = if (it == "providerId") providerNames[value] ?: value else value
                                "${labels[it]}: $display"
                            }
                        }
                },
            )
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("helix-settings-confirm"), enabled = !applying, onClick = {
                if (fields.isEmpty()) {
                    requests.remove(proposal.id)
                    onNavigate(values["page"]?.jsonPrimitive?.content ?: "settings")
                } else {
                    scope.launch {
                        applying = true
                        try {
                            if (applySettingsProposal(container, proposal)) {
                                requests.remove(proposal.id)
                            } else {
                                failure = true
                            }
                        } finally {
                            applying = false
                        }
                    }
                }
            }) {
                Text(
                    stringResource(
                        if (fields.isEmpty()) R.string.helix_settings_open else R.string.helix_settings_apply,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !applying, onClick = { requests.remove(proposal.id) }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

private suspend fun applySettingsProposal(
    container: AppContainer,
    proposal: com.helix.app.settings.HelixSettingsRequests.Proposal,
): Boolean {
    val values = proposal.values
    return try {
        container.chatService.applyUserSettings(
            proposal.sessionId,
            values["mode"]?.jsonPrimitive?.content?.let(AgentMode::valueOf),
            values["providerId"]?.jsonPrimitive?.content,
            values["model"]?.jsonPrimitive?.content,
            values["reasoning"]?.jsonPrimitive?.content?.let {
                ReasoningEffort.valueOf(it.uppercase(java.util.Locale.ROOT))
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}

private fun settingsPageLabel(page: String?) =
    when (page) {
        "models" -> R.string.nav_models
        "permissions" -> R.string.settings_system_permissions_title
        "session" -> R.string.session_settings_title
        else -> R.string.nav_settings
    }

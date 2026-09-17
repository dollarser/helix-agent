package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.approval.SessionPermissionEditService
import com.helix.app.chat.ChatService
import com.helix.app.tool.ToolPipeline
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.repository.SessionPermissionDraft
import com.helix.tools.framework.ToolDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The HXA-209 session-authorization settings section (ADR-PERMISSIONS-001). The UI's ONLY path
 * to a mode / rule-set / tool-availability change is the [SessionPermissionEditService]
 * ("UI 经服务操作，不直接写 DAO"); the repositories are read here, always off the main thread
 * (HelixStorage allows no main-thread queries).
 *
 * What it offers and, deliberately, what it does NOT:
 * - the NEW-SESSION DEFAULT (a preset only — the write service refuses a CUSTOM default);
 * - the current session's four-mode picker (FULL_ACCESS / WORKSPACE / READ_ONLY / CUSTOM);
 * - an app-wide (GLOBAL) tool enable/disable list.
 *
 * There is NO per-tool ASK or risk-level toggle (the two-state availability model has no ASK to
 * restore), and a mode switch never re-enables a disabled tool (a pre-existing B2 property — the
 * mode and the tool rows are independent stores). Every change here produces an independent audit
 * event via the service.
 */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun SessionPermissionSection(
    edit: SessionPermissionEditService,
    toolPipeline: ToolPipeline,
    chatService: ChatService? = null,
) {
    val controller = rememberPermissionController(edit, toolPipeline, chatService)
    SettingsGroup {
        Text(stringResource(R.string.settings_perm_title), style = MaterialTheme.typography.titleMedium)
        PermissionDefaultPicker(controller)
        HorizontalDivider(modifier = Modifier.fillMaxWidth())
        if (controller.sessionId != null) {
            PermissionSessionPicker(controller)
            PermissionCustomEditor(
                draft = controller.draft.value,
                onCopyPreset = { controller.copyPresetIntoDraft(it) },
                onSetRule = { effect, rule -> controller.setDraftRule(effect, rule) },
            )
            PermissionTighteningNotice(controller, chatService)
        } else {
            Text(
                stringResource(R.string.settings_perm_session_absent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.fillMaxWidth())
        PermissionToolList(controller)
    }
}

/**
 * The section's state + actions, kept out of the composable so the render tree stays a thin list
 * of pickers. Backed by [SessionPermissionEditService]; every Room read/write runs on IO and any
 * change re-loads so the UI shows the REAL stored state, never an optimistic guess.
 */
@Composable
@Suppress("FunctionName", "LongParameterList")
private fun rememberPermissionController(
    edit: SessionPermissionEditService,
    toolPipeline: ToolPipeline,
    chatService: ChatService?,
): SessionPermissionController {
    val scope = rememberCoroutineScope()
    val sessionId =
        chatService
            ?.screen
            ?.collectAsStateWithLifecycle()
            ?.value
            ?.openSessionId
    // In-memory tool identity list — safe to read on the main thread (no Room).
    val tools = remember(toolPipeline) { toolPipeline.registry.all() }
    val defaultMode = remember { mutableStateOf<SessionPermissionMode?>(null) }
    val sessionMode = remember { mutableStateOf<SessionPermissionMode?>(null) }
    val sessionHasStored = remember { mutableStateOf(false) }
    val toolsDisabled = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val draft = remember { mutableStateOf<SessionPermissionDraft?>(null) }
    val controller =
        remember(edit, tools, sessionId, scope) {
            SessionPermissionController(
                edit,
                scope,
                sessionId,
                tools,
                defaultMode,
                sessionMode,
                sessionHasStored,
                toolsDisabled,
                draft,
            )
        }
    LaunchedEffect(controller) { controller.load() }
    return controller
}

/** The NEW-SESSION DEFAULT picker — a preset only (the write service refuses a CUSTOM default). */
@Composable
@Suppress("FunctionName")
private fun PermissionDefaultPicker(controller: SessionPermissionController) {
    Text(stringResource(R.string.settings_perm_default_label))
    SettingsActions {
        PRESETS.forEach { mode ->
            ModeButton(
                mode = mode,
                selected = controller.defaultMode.value == mode,
                testTag = "settings-perm-default-${mode.name}",
                onClick = { controller.chooseDefault(mode) },
            )
        }
    }
}

/** The current session's REAL mode: a four-mode picker, its provenance, and a reset-to-default. */
@Composable
@Suppress("FunctionName")
private fun PermissionSessionPicker(controller: SessionPermissionController) {
    Text(stringResource(R.string.settings_perm_session_label))
    val provenance =
        if (controller.sessionHasStored.value) RES_SESSION_CUSTOMIZED else RES_SESSION_USING_DEFAULT
    Text(
        stringResource(provenance),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingsActions {
        ALL_MODES.forEach { mode ->
            ModeButton(
                mode = mode,
                selected = controller.sessionMode.value == mode,
                testTag = "settings-perm-mode-${mode.name}",
                onClick = { controller.chooseSessionMode(mode) },
            )
        }
    }
    if (controller.sessionMode.value == SessionPermissionMode.CUSTOM && controller.draft.value == null) {
        Text(stringResource(R.string.settings_perm_custom_hint), style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = { controller.resetSession() }, Modifier.testTag("settings-perm-reset")) {
        Text(stringResource(R.string.settings_perm_reset))
    }
}

/** The app-wide (GLOBAL) tool enable/disable list. */
@Composable
@Suppress("FunctionName")
private fun PermissionToolList(controller: SessionPermissionController) {
    Text(stringResource(R.string.settings_perm_tools_label))
    Text(
        stringResource(R.string.settings_perm_tools_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        controller.tools.forEach { descriptor ->
            ToolRow(
                toolName = descriptor.name.value,
                disabled = controller.toolsDisabled.value[toolKey(descriptor)] == true,
                testTag = "settings-perm-tool-${descriptor.name.value}",
                onToggle = { controller.toggleTool(descriptor) },
            )
        }
    }
}

/** A mode option: the selected one renders filled, the rest outlined. */
@Composable
@Suppress("FunctionName")
private fun ModeButton(
    mode: SessionPermissionMode,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val label = stringResource(mode.labelRes())
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.testTag(testTag)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.testTag(testTag)) { Text(label) }
    }
}

/** One tool in the app-wide availability list: its name, its state, and the enable/disable toggle. */
@Composable
@Suppress("FunctionName")
private fun ToolRow(
    toolName: String,
    disabled: Boolean,
    testTag: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(toolName)
            val stateRes = if (disabled) R.string.settings_perm_tool_disabled else R.string.settings_perm_tool_enabled
            Text(
                stringResource(stateRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onToggle, modifier = Modifier.testTag(testTag)) {
            Text(stringResource(R.string.settings_perm_tool_toggle))
        }
    }
}

/**
 * The section's state + actions. Reads the repositories (via [SessionPermissionEditService]) on
 * [Dispatchers.IO]; a change writes through the service then re-[load]s, so the UI always shows
 * the stored state. The state fields are Compose [MutableState] so the pickers track them.
 */
private class SessionPermissionController(
    private val edit: SessionPermissionEditService,
    private val scope: CoroutineScope,
    val sessionId: String?,
    val tools: List<ToolDescriptor>,
    val defaultMode: MutableState<SessionPermissionMode?>,
    val sessionMode: MutableState<SessionPermissionMode?>,
    val sessionHasStored: MutableState<Boolean>,
    val toolsDisabled: MutableState<Map<String, Boolean>>,
    val draft: MutableState<SessionPermissionDraft?>,
) {
    fun load() {
        scope.launch {
            withContext(Dispatchers.IO) {
                defaultMode.value = edit.appDefault().mode
                toolsDisabled.value =
                    tools.associate { toolKey(it) to edit.globalToolDisabled(it.origin.canonicalOf(), it.name.value) }
                val id = sessionId
                val stored = id?.let { edit.activeConfigFor(it) }
                sessionMode.value = (stored ?: edit.appDefault()).mode
                sessionHasStored.value = stored != null
                draft.value = id?.let { edit.customDraftFor(it) }
            }
        }
    }

    fun chooseDefault(mode: SessionPermissionMode) {
        scope.launch {
            withContext(Dispatchers.IO) { edit.setNewSessionDefault(mode, System.currentTimeMillis()) }
            load()
        }
    }

    fun chooseSessionMode(mode: SessionPermissionMode) {
        val id = sessionId ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val before = edit.activeConfigFor(id) ?: edit.appDefault()
                val now = System.currentTimeMillis()
                if (mode == SessionPermissionMode.CUSTOM) {
                    edit.activateCustomDraft(id, now)
                } else {
                    edit.saveSessionConfig(id, SessionPermissionConfig.of(mode), now)
                }
                markTightened((edit.activeConfigFor(id) ?: edit.appDefault()).tightens(before))
            }
            load()
        }
    }

    fun resetSession() {
        val id = sessionId ?: return
        scope.launch {
            withContext(Dispatchers.IO) { edit.resetSessionToDefault(id, System.currentTimeMillis()) }
            load()
        }
    }

    fun toggleTool(descriptor: ToolDescriptor) {
        val sourceRef = descriptor.origin.canonicalOf()
        val toolName = descriptor.name.value
        val disableNext = toolsDisabled.value[toolKey(descriptor)] != true
        scope.launch {
            withContext(Dispatchers.IO) {
                edit.setToolAvailability(
                    sourceRef,
                    toolName,
                    ToolAvailabilityScope.GLOBAL,
                    "",
                    disableNext,
                    System.currentTimeMillis(),
                )
            }
            load()
        }
    }

    /**
     * Copies a preset into this session's CUSTOM draft (section 4 — "copy a preset then edit
     * rules"): the preset's fixed rule table is materialized and saved as the draft. The write
     * service then syncs the active config if the session is already CUSTOM, so the draft and the
     * active rules never drift.
     */
    fun copyPresetIntoDraft(preset: SessionPermissionMode) {
        val id = sessionId ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                val before = edit.activeConfigFor(id) ?: edit.appDefault()
                edit.saveCustomDraft(
                    id,
                    preset,
                    SessionPermissionConfig.copyPreset(preset),
                    System.currentTimeMillis(),
                )
                markTightened((edit.activeConfigFor(id) ?: edit.appDefault()).tightens(before))
            }
            load()
        }
    }

    /**
     * Sets one operation effect's rule in the session's CUSTOM draft (a user change, audited by
     * the write service). A missing draft is a no-op — there is nothing to edit until a preset
     * has been copied.
     */
    fun setDraftRule(
        effect: OperationEffect,
        rule: OperationRule,
    ) {
        val id = sessionId ?: return
        val current = draft.value ?: return
        val nextRules = current.rules.toMutableMap().apply { this[effect] = rule }
        scope.launch {
            withContext(Dispatchers.IO) {
                val before = edit.activeConfigFor(id) ?: edit.appDefault()
                edit.saveCustomDraft(id, current.sourcePreset, nextRules, System.currentTimeMillis())
                markTightened((edit.activeConfigFor(id) ?: edit.appDefault()).tightens(before))
            }
            load()
        }
    }

    /** True while a rule tightening in this session should be surfaced (the notice + precise stop). */
    val tightened = mutableStateOf(false)

    /** Bumped on each tightening so the notice re-reads the running-task count (0 = none yet). */
    val tighteningSeq = mutableStateOf(0)

    /** Records whether the just-applied change tightened the session's effective rules (off-main). */
    private fun markTightened(next: Boolean) {
        tightened.value = next
        if (next) tighteningSeq.value += 1
    }

    /** Dismisses the tightening notice (after the user stops the tasks, or once they are all done). */
    fun clearTightened() {
        tightened.value = false
    }
}

/**
 * The HXA-209 rule-tightening notice (ADR-PERMISSIONS-001 section 4). After the session's rules
 * tighten it says "subsequent operations now take effect" and — for the still-running turns that
 * started under the looser rules — "there are still N previously started tasks", with a precise
 * stop. It never claims retroactive revocation: a started process keeps running until it is
 * stopped, so the only offered action is to stop the specific still-running tasks. The running-task
 * read is a Room read, run off the main thread.
 */
@Composable
@Suppress("FunctionName")
private fun PermissionTighteningNotice(
    controller: SessionPermissionController,
    chatService: ChatService?,
) {
    val sessionId = controller.sessionId
    val service = chatService
    val scope = rememberCoroutineScope()
    val runningCount = remember { mutableStateOf(-1) }
    LaunchedEffect(controller.tighteningSeq.value, sessionId) {
        if (controller.tightened.value && sessionId != null && service != null) {
            withContext(Dispatchers.IO) {
                runningCount.value = service.runningTurnIdsForSession(sessionId).size
            }
        }
    }
    if (controller.tightened.value && sessionId != null && service != null) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_perm_tightened_effective))
            if (runningCount.value > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.settings_perm_tightened_still_running, runningCount.value),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    service.runningTurnIdsForSession(sessionId).forEach { service.stopTask(it) }
                                }
                                controller.clearTightened()
                            }
                        },
                        modifier = Modifier.testTag("settings-perm-tightened-stop"),
                    ) {
                        Text(stringResource(R.string.settings_perm_tightened_stop))
                    }
                }
            }
        }
    }
}

/** A stable, collision-free key for a tool identity (source ref + name). */
private fun toolKey(descriptor: ToolDescriptor): String = descriptor.origin.canonicalOf() + " " + descriptor.name.value

/** The preset modes — the valid new-session defaults (CUSTOM is not a default). Shared with the
 * CUSTOM editor: a custom draft is copied from one of these presets (never from CUSTOM). */
internal val PRESETS =
    listOf(
        SessionPermissionMode.FULL_ACCESS,
        SessionPermissionMode.WORKSPACE,
        SessionPermissionMode.READ_ONLY,
    )

/** All four selectable modes for a session. */
private val ALL_MODES =
    listOf(
        SessionPermissionMode.FULL_ACCESS,
        SessionPermissionMode.WORKSPACE,
        SessionPermissionMode.READ_ONLY,
        SessionPermissionMode.CUSTOM,
    )

/** The session caption when it carries its own stored config. */
private val RES_SESSION_CUSTOMIZED = R.string.settings_perm_session_customized

/** The session caption when it resolves to the app default. */
private val RES_SESSION_USING_DEFAULT = R.string.settings_perm_session_using_default

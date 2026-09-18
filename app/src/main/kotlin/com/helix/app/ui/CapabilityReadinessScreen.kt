package com.helix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.helix.app.AppContainer
import com.helix.app.R
import com.helix.app.proot.ProotToolModule
import com.helix.app.proot.ProotVerificationNote
import com.helix.app.readiness.CapabilityReadiness
import com.helix.app.readiness.ReadinessActionKind
import com.helix.app.readiness.ReadinessGoal
import com.helix.app.readiness.ReadinessItem
import com.helix.app.readiness.ReadinessItemKind
import com.helix.app.readiness.ReadinessProjection
import com.helix.app.readiness.ReadinessState
import com.helix.app.readiness.RuntimeReadiness
import com.helix.core.model.Capability
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Capability Readiness view (HXA-205): one page that aggregates the model connection, the
 * app Workspace, the required system capability and the PRoot Runtime state, and gives the user
 * the next action for their CURRENT goal. It is a READ-ONLY projection of already-readable facts:
 * passive entry cold-binds nothing, opens no login and installs nothing (HXA-205: 被动进入页面不
 * 拉起 Runtime 或登录). Only an explicit click on a next action performs a cold bind / repair.
 *
 * The per-goal items and the single next action come from the pure, unit-tested
 * [CapabilityReadiness] projection (slice 1): structured facts, distinct actions, never one
 * generic resume (the HXA-204 invariant). The runtime state is read through the flavor seam's
 * bind-free [ProotToolModule.runtimeReadiness] (developer: the live gate; consumer: honestly
 * NOT_AVAILABLE, so a consumer build never offers a LINUX goal), and the browser capability
 * through [resolveOnly] (no audit write on a passive refresh). The install / not-installed /
 * disabled states all route to the SAME existing repair-activity entry — there is no separate
 * APK install flow.
 */
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod") // one branch per goal / item kind / state / action
internal fun CapabilityReadinessScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
) {
    val profile by container.profileStore.flow.collectAsState()
    val linuxGoalEnabled = ProotToolModule.AVAILABLE && profile == SafetyProfile.ADVANCED
    val allGoals: List<ReadinessGoal> = ReadinessGoal.entries.toList()
    val goals: List<ReadinessGoal> =
        if (linuxGoalEnabled) {
            allGoals
        } else {
            allGoals.filter { it != ReadinessGoal.LINUX }
        }

    var goal by rememberSaveable(
        stateSaver =
            Saver<ReadinessGoal, String>(
                save = { it.name },
                restore = { name -> ReadinessGoal.entries.firstOrNull { g -> g.name == name } ?: ReadinessGoal.CHAT },
            ),
    ) {
        mutableStateOf(ReadinessGoal.CHAT)
    }
    // The profile may drop LINUX under the user; fall back to the always-available CHAT goal.
    val effectiveGoal = if (goal in goals) goal else ReadinessGoal.CHAT

    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var projection by remember { mutableStateOf<ReadinessProjection?>(null) }
    // The last explicit verify result (a structured note, not a generic error); it belongs to
    // the current goal, so it is cleared when the goal changes, never by a passive refresh.
    var verifyNote by remember { mutableStateOf<ProotVerificationNote?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Resolved in composition (stringResource is @Composable); the click handlers only read them.
    val rebaselineSuccess = stringResource(R.string.settings_proot_rebaseline_success)
    val rebaselineEmpty = stringResource(R.string.settings_proot_rebaseline_empty)

    val readProjection: (ReadinessGoal) -> ReadinessProjection = { g ->
        val modelConfigured =
            container.providerService.rows.value
                .any { it.hasKey }
        val runtime =
            if (g == ReadinessGoal.LINUX) {
                ProotToolModule.runtimeReadiness()
            } else {
                RuntimeReadiness.NOT_AVAILABLE
            }
        CapabilityReadiness.project(
            goal = g,
            modelConfigured = modelConfigured,
            capability = { capability -> container.capabilityCenter.resolveOnly(capability) },
            runtime = runtime,
        )
    }

    LaunchedEffect(effectiveGoal) {
        verifyNote = null
    }
    LaunchedEffect(effectiveGoal, revision) {
        projection = withContext(Dispatchers.IO) { readProjection(effectiveGoal) }
    }

    // Re-open / rotate / returning from Settings or the repair activity: re-read the current
    // state so the view always reflects reality (HXA-205: 重开状态可恢复). Passive — no bind.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision += 1 }

    // The explicit user click is the ONLY cold-bind path (HXA-085); afterwards the gate is
    // re-read so READY shows without a second tap.
    fun onVerify() {
        if (busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                verifyNote = ProotToolModule.verifyNowNote()
                projection = readProjection(effectiveGoal)
            }
            busy = false
        }
    }

    // The explicit user click is the ONLY repair-activity path (install + fix share it); re-read
    // when the user returns from the companion.
    fun onRepair() {
        ProotToolModule.openRepair()
        revision += 1
    }

    // The explicit re-baseline action (HXA-087 需更新): only reachable after a verify has shown
    // the stable lock-mismatch note. Two explicit user actions, never automatic.
    fun onRebaseline() {
        scope.launch(Dispatchers.IO) {
            val existed = ProotToolModule.rebaseline()
            verifyNote =
                if (existed) {
                    ProotVerificationNote(rebaselineSuccess)
                } else {
                    ProotVerificationNote(rebaselineEmpty)
                }
            projection = readProjection(effectiveGoal)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("capability-readiness"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.readiness_lead),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("capability-readiness-lead"),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.readiness_goal_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("capability-readiness-goal-label"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                goals.forEach { g ->
                    val selected = g == effectiveGoal
                    Text(
                        stringResource(g.titleRes()),
                        style = MaterialTheme.typography.labelLarge,
                        modifier =
                            Modifier
                                .testTag("capability-readiness-goal-${g.route()}")
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = MaterialTheme.shapes.small,
                                ).clickable { goal = g }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        TextButton(
            onClick = { if (!busy) revision += 1 },
            modifier = Modifier.align(Alignment.End).testTag("capability-readiness-refresh"),
        ) {
            Text(stringResource(R.string.cap_refresh))
        }

        val current = projection
        if (current != null && current.goal == effectiveGoal) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                current.items.forEach { item -> ReadinessItemRow(item) }
                val action = current.nextAction
                if (action != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.readiness_next_action),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                when (action) {
                                    ReadinessActionKind.ADD_MODEL -> onOpenSettings()
                                    ReadinessActionKind.VERIFY_RUNTIME -> onVerify()
                                    ReadinessActionKind.REPAIR_RUNTIME -> onRepair()
                                }
                            },
                            modifier = Modifier.testTag("capability-readiness-action-${action.route()}"),
                        ) {
                            Text(stringResource(action.labelRes()))
                        }
                    }
                }
                verifyNote?.let { note ->
                    Text(
                        note.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("capability-readiness-result"),
                    )
                }
                if (verifyNote?.needsRebaseline == true) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { onRebaseline() },
                        modifier = Modifier.testTag("capability-readiness-action-rebaseline"),
                    ) {
                        Text(stringResource(R.string.settings_proot_rebaseline))
                    }
                }
            }
        } else {
            Text(
                "…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("capability-readiness-loading"),
            )
        }
    }
}

/** One readiness item: the kind label + its live state chip (structured fact → label, HXA-204). */
@Composable
@Suppress("FunctionName")
private fun ReadinessItemRow(item: ReadinessItem) {
    val route = item.kind.route()
    val stateColor =
        when (item.state) {
            ReadinessState.READY -> MaterialTheme.colorScheme.primary
            ReadinessState.MISSING -> MaterialTheme.colorScheme.tertiary
            ReadinessState.NOT_AVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("capability-readiness-item-$route"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(item.kind.labelRes()), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(item.state.labelRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = stateColor,
            modifier = Modifier.testTag("capability-readiness-item-$route-state"),
        )
    }
}

/** Stable locale-independent key for a goal (used in testTags). */
private fun ReadinessGoal.route(): String =
    when (this) {
        ReadinessGoal.CHAT -> "chat"
        ReadinessGoal.FILES -> "files"
        ReadinessGoal.BROWSER -> "browser"
        ReadinessGoal.LINUX -> "linux"
    }

/** Stable locale-independent key for an action (used in testTags). */
private fun ReadinessActionKind.route(): String =
    when (this) {
        ReadinessActionKind.ADD_MODEL -> "add-model"
        ReadinessActionKind.VERIFY_RUNTIME -> "verify-runtime"
        ReadinessActionKind.REPAIR_RUNTIME -> "repair-runtime"
    }

/** Stable locale-independent key for an item kind (used in testTags). */
private fun ReadinessItemKind.route(): String =
    when (this) {
        ReadinessItemKind.MODEL -> "model"
        ReadinessItemKind.WORKSPACE -> "workspace"
        ReadinessItemKind.WEB -> "web"
        ReadinessItemKind.RUNTIME -> "runtime"
    }

@Suppress("FunctionName")
private fun ReadinessGoal.titleRes(): Int =
    when (this) {
        ReadinessGoal.CHAT -> R.string.readiness_goal_chat
        ReadinessGoal.FILES -> R.string.readiness_goal_files
        ReadinessGoal.BROWSER -> R.string.readiness_goal_browser
        ReadinessGoal.LINUX -> R.string.readiness_goal_linux
    }

@Suppress("FunctionName")
private fun ReadinessItemKind.labelRes(): Int =
    when (this) {
        ReadinessItemKind.MODEL -> R.string.readiness_item_model
        ReadinessItemKind.WORKSPACE -> R.string.readiness_item_workspace
        ReadinessItemKind.WEB -> R.string.readiness_item_web
        ReadinessItemKind.RUNTIME -> R.string.readiness_item_runtime
    }

@Suppress("FunctionName")
private fun ReadinessState.labelRes(): Int =
    when (this) {
        ReadinessState.READY -> R.string.readiness_state_ready
        ReadinessState.MISSING -> R.string.readiness_state_missing
        ReadinessState.NOT_AVAILABLE -> R.string.readiness_state_unavailable
    }

@Suppress("FunctionName")
private fun ReadinessActionKind.labelRes(): Int =
    when (this) {
        ReadinessActionKind.ADD_MODEL -> R.string.readiness_action_add_model
        ReadinessActionKind.VERIFY_RUNTIME -> R.string.readiness_action_verify_runtime
        ReadinessActionKind.REPAIR_RUNTIME -> R.string.readiness_action_repair_runtime
    }

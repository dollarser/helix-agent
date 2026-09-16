package com.helix.app.approval

import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.repository.SessionPermissionConfigRepository
import com.helix.core.storage.repository.SessionPermissionDraft
import com.helix.core.storage.repository.ToolAvailabilityRepository
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The app's WRITE path for the session authorization (HXA-209 D, ADR-PERMISSIONS-001
 * sections 4 + 5): the service the settings UI operates through ("UI 经服务操作，不直接写
 * DAO"). Every change is a USER action — only the UI may change a mode, the root binding or
 * a rule (section 4) — and each one (a) is linearized against execution starts by the B2
 * store's single-row atomic upsert + monotonic [revision], and (b) produces an INDEPENDENT
 * audit event recording the mode, the rule-set version, the user change time and the binding
 * (section 5). A config change is never disguised as a per-call approval.
 *
 * This service and the read seam [SessionPermissionService] share ONE linearization
 * contract: both go through the same B2 repositories, whose rows are upserted atomically and
 * re-read LIVE at the dispatcher's start gate and the PRoot not-yet-launched recheck. An
 * approval WAIT never holds a mode write lock — the approval acquisition is asynchronous and
 * holds no Room write lock, so a mode change always lands and the next start-gate re-read
 * sees it (section 4: 等待审批不得持有模式写入锁).
 *
 * Fail-closed (section 2: CAS 冲突或存储失败不显示保存成功): a storage failure PROPAGATES
 * out of a save — nothing is audited when the write fails, and there is no catch-all success
 * path the UI could mistake for "saved".
 */
class SessionPermissionEditService(
    private val configs: SessionPermissionConfigRepository,
    private val availability: ToolAvailabilityRepository,
    private val idGenerator: () -> String,
    private val appendAudit: (
        id: String,
        correlationId: String,
        type: String,
        actor: String,
        payload: String,
        timestamp: Long,
    ) -> Unit,
) {
    /**
     * Saves (or updates) the ACTIVE config for one session; returns the new revision. The
     * repository enforces that a preset carries exactly its own rule table (a preset with any
     * other table throws — copy it into a CUSTOM snapshot to edit individual rules), so the
     * UI cannot smuggle an edited table under a preset. Storage failures propagate.
     */
    fun saveSessionConfig(
        sessionId: String,
        config: SessionPermissionConfig,
        nowEpochMillis: Long,
    ): Long {
        val revision = configs.setForSession(sessionId, config, nowEpochMillis)
        appendAudit(
            idGenerator(),
            sessionId,
            TYPE,
            ACTOR,
            Change(
                action = ACTION_SESSION_CONFIG,
                changedAt = nowEpochMillis,
                mode = config.mode.name,
                configVersion = config.configVersion,
                revision = revision,
            ).encode(),
            nowEpochMillis,
        )
        return revision
    }

    /**
     * "Back to the app default" for one session: a row delete, not a stored fourth state.
     * The audit records the mode the session now resolves to (the app default). Storage
     * failures propagate.
     */
    fun resetSessionToDefault(
        sessionId: String,
        nowEpochMillis: Long,
    ) {
        configs.resetToDefault(sessionId)
        appendAudit(
            idGenerator(),
            sessionId,
            TYPE,
            ACTOR,
            Change(
                action = ACTION_RESET,
                changedAt = nowEpochMillis,
                mode = configs.appDefault().mode.name,
                configVersion = configs.appDefault().configVersion,
            ).encode(),
            nowEpochMillis,
        )
    }

    /**
     * Sets the NEW-SESSION DEFAULT mode (the default a fresh/copy/imported session adopts);
     * returns the new revision. The default is a PRESET only — CUSTOM needs an explicit
     * per-session snapshot, so the repository rejects a CUSTOM default. A default change
     * affects ONLY sessions created after it, never existing sessions (section 4).
     */
    fun setNewSessionDefault(
        mode: SessionPermissionMode,
        nowEpochMillis: Long,
    ): Long {
        val revision = configs.setAppDefault(mode, nowEpochMillis)
        appendAudit(
            idGenerator(),
            APP_DEFAULT_CORRELATION,
            TYPE,
            ACTOR,
            Change(
                action = ACTION_APP_DEFAULT,
                changedAt = nowEpochMillis,
                mode = mode.name,
                configVersion = configs.appDefault().configVersion,
                revision = revision,
            ).encode(),
            nowEpochMillis,
        )
        return revision
    }

    /**
     * Enables or disables ONE tool identity in ONE scope; returns whether the stored state
     * actually changed (the UI uses this to show "saved"). The two-state model: DISABLED is
     * a stored row, ENABLED is its ABSENCE (a delete) — there is no ASK to restore, so
     * re-enabling only returns availability and never revives a per-tool approval floor
     * (sections 1.1 + 2: 重新启用仅改变可用性，不恢复 ASK/ALLOW). A no-op enable (no row to
     * remove) returns false and writes nothing. Storage failures propagate.
     */
    fun setToolAvailability(
        sourceRef: String,
        toolName: String,
        scope: ToolAvailabilityScope,
        scopeRef: String,
        disabled: Boolean,
        nowEpochMillis: Long,
    ): Boolean {
        val changed =
            if (disabled) {
                availability.set(sourceRef, toolName, scope, scopeRef, ToolAvailabilityState.DISABLED, nowEpochMillis)
                true
            } else {
                val present =
                    availability
                        .byTool(sourceRef, toolName)
                        .any { it.scopeKind == scope.name && it.scopeRef == scopeRef }
                if (present) {
                    availability.remove(sourceRef, toolName, scope, scopeRef)
                    true
                } else {
                    false
                }
            }
        if (changed) {
            appendAudit(
                idGenerator(),
                scopeRef.ifBlank { GLOBAL_CORRELATION },
                TYPE,
                ACTOR,
                Change(
                    action = ACTION_TOOL_AVAILABILITY,
                    changedAt = nowEpochMillis,
                    toolName = toolName,
                    toolState =
                        if (disabled) {
                            ToolAvailabilityState.DISABLED.name
                        } else {
                            ToolAvailabilityState.ENABLED.name
                        },
                    scope = scope.name,
                ).encode(),
                nowEpochMillis,
            )
        }
        return changed
    }

    /**
     * The stored CUSTOM draft for one session, or null when it has none yet — the read seam the
     * settings editor uses to repopulate the snapshot. A pure read: it produces no audit row
     * (section 5 — a change is audited, a read is not).
     */
    fun customDraftFor(sessionId: String): SessionPermissionDraft? = configs.customDraftFor(sessionId)

    /**
     * The NEW-SESSION DEFAULT the settings picker shows and edits (section 4 — a preset only;
     * the write path [setNewSessionDefault] refuses a CUSTOM default). A pure read: no audit row.
     */
    fun appDefault(): SessionPermissionConfig = configs.appDefault()

    /**
     * The stored ACTIVE config for one session, or null when it has no row and so resolves to the
     * app default — the read seam the settings picker uses to show the session's REAL mode. A
     * null means "using default", which the UI renders as such rather than a stored mode.
     */
    fun activeConfigFor(sessionId: String): SessionPermissionConfig? = configs.forSession(sessionId)

    /**
     * Whether one tool identity is disabled in the GLOBAL scope — the toggle state of the app-wide
     * tool list. The two-state model stores DISABLED as a row and ENABLED as its absence, so a
     * present GLOBAL row means disabled.
     */
    fun globalToolDisabled(
        sourceRef: String,
        toolName: String,
    ): Boolean =
        availability
            .byTool(sourceRef, toolName)
            .any { it.scopeKind == ToolAvailabilityScope.GLOBAL.name }

    /**
     * Saves one session's CUSTOM draft — the copied-from preset plus the copied-then-edited rule
     * snapshot (section 4). Persisting the draft does NOT by itself change what the session
     * executes: if the session's active mode is already CUSTOM the active config is synced to the
     * new draft (so the draft and the active rules never drift); otherwise the draft stays INERT
     * (re-select CUSTOM to apply it). A user change, so it produces an independent
     * `session_permission_change` audit (action `custom_draft`). Storage failures propagate.
     */
    fun saveCustomDraft(
        sessionId: String,
        sourcePreset: SessionPermissionMode,
        rules: Map<OperationEffect, OperationRule>,
        nowEpochMillis: Long,
    ) {
        configs.setCustomDraft(sessionId, sourcePreset, rules, nowEpochMillis)
        val active = configs.forSession(sessionId) ?: configs.appDefault()
        if (active.mode == SessionPermissionMode.CUSTOM) {
            configs.setForSession(sessionId, SessionPermissionConfig.custom(rules), nowEpochMillis)
        }
        appendAudit(
            idGenerator(),
            sessionId,
            TYPE,
            ACTOR,
            Change(
                action = ACTION_CUSTOM_DRAFT,
                changedAt = nowEpochMillis,
                mode = active.mode.name,
                configVersion = SessionPermissionConfig.CURRENT_CONFIG_VERSION,
                sourcePreset = sourcePreset.name,
            ).encode(),
            nowEpochMillis,
        )
    }

    /**
     * Re-selects CUSTOM for one session: applies the stored draft as the ACTIVE config
     * (section 4 — "re-selecting CUSTOM restores it"). Returns true when a draft existed and was
     * applied; false when the session has no custom snapshot yet (the UI keeps the editor open to
     * copy a preset first). When it applies, it goes through [saveSessionConfig], so it is
     * audited as a `session_config` change and linearized against execution starts like any mode
     * switch.
     */
    fun activateCustomDraft(
        sessionId: String,
        nowEpochMillis: Long,
    ): Boolean {
        val draft = configs.customDraftFor(sessionId) ?: return false
        saveSessionConfig(sessionId, SessionPermissionConfig.custom(draft.rules), nowEpochMillis)
        return true
    }

    /**
     * The redacted inputs of one authorization CHANGE: the action, the resulting mode +
     * rule-set version (when a mode/rule change), the store revision (when one was produced),
     * the source preset (when a CUSTOM draft is saved) and, for a tool change, the tool name,
     * the new state and the scope. The binding is the row's correlationId, not repeated here.
     * Enum names and a timestamp only — no tool arguments, scope paths or bodies. [encode]
     * always emits every key (JsonNull where a field does not apply), so a reader of either row
     * shape never sees a missing field.
     */
    private data class Change(
        val action: String,
        val changedAt: Long,
        val mode: String? = null,
        val configVersion: Int? = null,
        val revision: Long? = null,
        val toolName: String? = null,
        val toolState: String? = null,
        val scope: String? = null,
        val sourcePreset: String? = null,
    ) {
        fun encode(): String =
            buildJsonObject {
                put("version", VERSION)
                put("action", action)
                put("changedAt", changedAt)
                put("mode", mode?.let(::JsonPrimitive) ?: JsonNull)
                put("configVersion", configVersion?.let(::JsonPrimitive) ?: JsonNull)
                put("revision", revision?.let(::JsonPrimitive) ?: JsonNull)
                put("toolName", toolName?.let(::JsonPrimitive) ?: JsonNull)
                put("toolState", toolState?.let(::JsonPrimitive) ?: JsonNull)
                put("scope", scope?.let(::JsonPrimitive) ?: JsonNull)
                put("sourcePreset", sourcePreset?.let(::JsonPrimitive) ?: JsonNull)
            }.toString()

        private companion object {
            const val VERSION = 1
        }
    }

    companion object {
        /** The `audit_events.type` for session-authorization CHANGE rows (section 5). */
        const val TYPE = "session_permission_change"

        /** The actor of every change — the user, via the settings UI (section 4). */
        const val ACTOR = "user"

        const val ACTION_SESSION_CONFIG = "session_config"

        const val ACTION_CUSTOM_DRAFT = "custom_draft"

        const val ACTION_RESET = "reset_to_default"

        const val ACTION_APP_DEFAULT = "app_default"

        const val ACTION_TOOL_AVAILABILITY = "tool_availability"

        /** App-default changes belong to no single session — a stable correlation of their own. */
        private const val APP_DEFAULT_CORRELATION = "session-permission-defaults"

        /** A GLOBAL tool-availability row has an empty scopeRef — a stable correlation. */
        private const val GLOBAL_CORRELATION = "session-permission-tools"
    }
}

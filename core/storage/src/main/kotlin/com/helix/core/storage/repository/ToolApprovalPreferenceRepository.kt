package com.helix.core.storage.repository

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.policy.ToolApprovalPreferenceRecord
import com.helix.core.storage.dao.ToolApprovalPreferenceDao
import com.helix.core.storage.entity.ToolApprovalPreferenceEntity
import java.util.UUID

/**
 * Durable user tool-approval preferences (HXA-200, ADR-0052). This is the ONLY write path: the
 * user application service (settings / approval card) sets and removes preferences through here;
 * the model, Skill, MCP and A2A cannot. The Registry (model exposure) and the ToolDispatcher
 * (pre-start re-resolution) read through [applicable] and resolve the result with
 * [com.helix.core.policy.ToolApprovalResolver] — they never mutate the store.
 *
 * Identity is the stable tool source + name ([sourceRef]/[toolName]); a display name is never a
 * key. [set] upserts under the (source, name, scope, scopeRef) unique key, advancing the revision
 * on change; [remove] deletes the row (a reset, not a fourth state).
 */
class ToolApprovalPreferenceRepository(
    private val dao: ToolApprovalPreferenceDao,
) {
    /**
     * Sets (or updates) the preference for one tool identity in one scope. Returns the storage id.
     * [contractHash] is the descriptor `contractHash` an ALLOW is bound to (null for ASK/DENY);
     * a later contract change invalidates a stored ALLOW at read time.
     */
    fun set(
        sourceRef: String,
        toolName: String,
        scope: ToolApprovalPreferenceScope,
        scopeRef: String,
        preference: ToolApprovalPreference,
        contractHash: String?,
        nowEpochMillis: Long,
    ): String {
        requireValidScope(scope, scopeRef)
        val existing = dao.byKey(sourceRef, toolName, scope.name, scopeRef)
        return if (existing != null) {
            dao.update(
                id = existing.id,
                preference = preference.name,
                contractHash = contractHash.orEmpty(),
                revision = existing.revision + 1,
                updatedAtEpoch = nowEpochMillis,
            )
            existing.id
        } else {
            val id = UUID.randomUUID().toString()
            dao.insert(
                ToolApprovalPreferenceEntity(
                    id = id,
                    sourceRef = sourceRef,
                    toolName = toolName,
                    preference = preference.name,
                    scopeKind = scope.name,
                    scopeRef = scopeRef,
                    contractHash = contractHash.orEmpty(),
                    revision = 1L,
                    createdAtEpoch = nowEpochMillis,
                    updatedAtEpoch = nowEpochMillis,
                ),
            )
            id
        }
    }

    /** Removes the preference for one tool identity in one scope; throws when it does not exist. */
    fun remove(
        sourceRef: String,
        toolName: String,
        scope: ToolApprovalPreferenceScope,
        scopeRef: String,
    ) {
        require(dao.deleteByScope(sourceRef, toolName, scope.name, scopeRef) == 1) {
            "no stored preference for $toolName in ${scope.name} scope"
        }
    }

    /**
     * Whether the user has stored ANY preference for this tool identity, in ANY scope (HXA-200 Gap
     * 2). The new-tool default applies only to a tool the user has never configured; a preference
     * set in a different session still counts as "configured," so this ignores the current context.
     */
    fun hasAnyPreference(
        sourceRef: String,
        toolName: String,
    ): Boolean = dao.countByTool(sourceRef, toolName) > 0

    /**
     * The records applicable to one tool identity in a context: the GLOBAL row always, plus the
     * current session's and workspace's rows when present. Rehydrated fail-closed (an unknown
     * stored value throws rather than being guessed). The caller resolves the list with
     * [com.helix.core.policy.ToolApprovalResolver.effectivePreference].
     */
    fun applicable(
        sourceRef: String,
        toolName: String,
        sessionId: String?,
        workspaceRef: String?,
    ): List<ToolApprovalPreferenceRecord> =
        dao
            .byTool(sourceRef, toolName)
            .filter { entity ->
                when (entity.scopeKind) {
                    ToolApprovalPreferenceScope.GLOBAL.name -> true
                    ToolApprovalPreferenceScope.SESSION.name -> entity.scopeRef == sessionId
                    ToolApprovalPreferenceScope.WORKSPACE.name -> entity.scopeRef == workspaceRef
                    else -> error("unknown preference scope: ${entity.scopeKind}")
                }
            }.map { it.toRecord() }

    private fun ToolApprovalPreferenceEntity.toRecord(): ToolApprovalPreferenceRecord =
        ToolApprovalPreferenceRecord(
            preference = ToolApprovalPreference.valueOf(preference),
            scope = ToolApprovalPreferenceScope.valueOf(scopeKind),
            contractHash = contractHash.ifEmpty { null },
        )

    private fun requireValidScope(
        scope: ToolApprovalPreferenceScope,
        scopeRef: String,
    ) {
        when (scope) {
            ToolApprovalPreferenceScope.GLOBAL -> {
                require(scopeRef.isEmpty()) { "GLOBAL preference must have an empty scopeRef" }
            }

            ToolApprovalPreferenceScope.SESSION, ToolApprovalPreferenceScope.WORKSPACE -> {
                require(scopeRef.isNotBlank()) { "${scope.name} preference requires a scopeRef" }
            }
        }
    }
}

package com.helix.core.storage.repository

import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolAvailabilityStates
import com.helix.core.storage.dao.ToolAvailabilityDao
import com.helix.core.storage.entity.ToolAvailabilityEntity

/**
 * Stored tool availability states (HXA-209, ADR-PERMISSIONS-001 section 1.1). The single write
 * path for the two-state (ENABLED/DISABLED) availability of a tool identity per scope; the
 * dispatcher and EVERY exposure surface read through [statesFor] and resolve the effective
 * state with the core/policy `effectiveAvailability` — they never mutate the store.
 *
 * [set] upserts under the (source, name, scope, scopeRef) key, advancing the revision; [remove]
 * deletes the row (a reset, not a third state). [statesFor] returns the RAW per-scope states
 * applicable to one context (GLOBAL always, plus the current session's and workspace's rows);
 * the precedence folding (outer disable wins) lives in core/policy, so the store has no policy
 * dependency. Rehydration is fail-closed: an unknown stored scope or state throws.
 */
class ToolAvailabilityRepository(
    private val dao: ToolAvailabilityDao,
) {
    /** One-time catalog migration, inside the host storage transaction; never resolves old calls. */
    fun moveToolStates(
        sourceRef: String,
        replacements: Map<String, Set<String>>,
    ) {
        replacements.forEach { (old, names) ->
            require(old !in names && names.none { it in replacements }) { "Tool migration must be terminal" }
            dao.byTool(sourceRef, old).forEach { row ->
                names.forEach { name ->
                    val existing = dao.byKey(sourceRef, name, row.scopeKind, row.scopeRef)
                    val revision = maxOf(row.revision, existing?.revision ?: 0)
                    check(revision < Long.MAX_VALUE) { "tool availability revision exhausted" }
                    dao.insert(
                        row.copy(
                            toolName = name,
                            state =
                                if (stateOf(row) == ToolAvailabilityState.DISABLED ||
                                    existing?.let(::stateOf) == ToolAvailabilityState.DISABLED
                                ) {
                                    ToolAvailabilityState.DISABLED.name
                                } else {
                                    ToolAvailabilityState.ENABLED.name
                                },
                            revision = revision + 1,
                        ),
                    )
                }
                check(dao.deleteByKey(sourceRef, old, row.scopeKind, row.scopeRef) == 1)
            }
        }
    }

    /** One-time key normalization, called inside the host's storage transaction before exposure. */
    fun normalizeSourceRefs(normalize: (String) -> String): Int {
        val rows = dao.all()
        val moved = rows.filter { normalize(it.sourceRef) != it.sourceRef }
        if (moved.isEmpty()) return 0
        val affectedKeys = moved.map { listOf(normalize(it.sourceRef), it.toolName, it.scopeKind, it.scopeRef) }.toSet()
        rows
            .groupBy { listOf(normalize(it.sourceRef), it.toolName, it.scopeKind, it.scopeRef) }
            .filterKeys { it in affectedKeys }
            .forEach { (key, records) ->
                require(normalize(key[0]) == key[0]) { "source normalization must be idempotent" }
                val newest = records.maxBy { it.revision }
                check(newest.revision < Long.MAX_VALUE) { "tool availability revision exhausted" }
                val state =
                    if (records.any { stateOf(it) == ToolAvailabilityState.DISABLED }) {
                        ToolAvailabilityState.DISABLED
                    } else {
                        ToolAvailabilityState.ENABLED
                    }
                dao.insert(
                    newest.copy(
                        sourceRef = key[0],
                        state = state.name,
                        revision = newest.revision + 1,
                        createdAtEpoch = records.minOf { it.createdAtEpoch },
                        updatedAtEpoch = records.maxOf { it.updatedAtEpoch },
                    ),
                )
            }
        moved.forEach { row ->
            check(dao.deleteByKey(row.sourceRef, row.toolName, row.scopeKind, row.scopeRef) == 1)
        }
        return moved.size
    }

    /**
     * Sets (or updates) the availability state of one tool identity in one scope; returns the
     * new revision.
     */
    fun set(
        sourceRef: String,
        toolName: String,
        scope: ToolAvailabilityScope,
        scopeRef: String,
        state: ToolAvailabilityState,
        nowEpochMillis: Long,
    ): Long {
        requireValidScope(scope, scopeRef)
        val existing = dao.byKey(sourceRef, toolName, scope.name, scopeRef)
        val revision = (existing?.revision ?: 0L) + 1L
        dao.insert(
            ToolAvailabilityEntity(
                sourceRef = sourceRef,
                toolName = toolName,
                scopeKind = scope.name,
                scopeRef = scopeRef,
                state = state.name,
                revision = revision,
                createdAtEpoch = existing?.createdAtEpoch ?: nowEpochMillis,
                updatedAtEpoch = nowEpochMillis,
            ),
        )
        return revision
    }

    /** Removes the state of one tool identity in one scope; throws when it does not exist. */
    fun remove(
        sourceRef: String,
        toolName: String,
        scope: ToolAvailabilityScope,
        scopeRef: String,
    ) {
        requireValidScope(scope, scopeRef)
        require(dao.deleteByKey(sourceRef, toolName, scope.name, scopeRef) == 1) {
            "no stored availability state for $toolName in ${scope.name} scope"
        }
    }

    /** Every stored state for one tool identity, across all scopes; deterministic order. */
    fun byTool(
        sourceRef: String,
        toolName: String,
    ): List<ToolAvailabilityEntity> = dao.byTool(sourceRef, toolName)

    /**
     * The RAW per-scope states applicable to one context: the GLOBAL row always, plus the
     * current session's and workspace's rows when present. Null slots mean "no explicit state
     * at that scope". The caller folds them with core/policy `effectiveAvailability`.
     */
    fun statesFor(
        sourceRef: String,
        toolName: String,
        sessionId: String?,
        workspaceRef: String?,
    ): ToolAvailabilityStates {
        var global: ToolAvailabilityState? = null
        var workspace: ToolAvailabilityState? = null
        var session: ToolAvailabilityState? = null
        for (row in dao.byTool(sourceRef, toolName)) {
            when (row.scopeKind) {
                ToolAvailabilityScope.GLOBAL.name -> {
                    global = stateOf(row)
                }

                ToolAvailabilityScope.WORKSPACE.name -> {
                    if (row.scopeRef == workspaceRef) {
                        workspace = stateOf(row)
                    }
                }

                ToolAvailabilityScope.SESSION.name -> {
                    if (row.scopeRef == sessionId) {
                        session = stateOf(row)
                    }
                }

                else -> {
                    error("unknown tool availability scope: ${row.scopeKind}")
                }
            }
        }
        return ToolAvailabilityStates(
            global = global,
            workspace = workspace,
            session = session,
        )
    }

    private fun stateOf(row: ToolAvailabilityEntity): ToolAvailabilityState =
        runCatching { ToolAvailabilityState.valueOf(row.state) }.getOrElse {
            throw IllegalArgumentException("unknown tool availability state: ${row.state}")
        }

    private fun requireValidScope(
        scope: ToolAvailabilityScope,
        scopeRef: String,
    ) {
        when (scope) {
            ToolAvailabilityScope.GLOBAL -> {
                require(scopeRef.isEmpty()) {
                    "GLOBAL availability state must have an empty scopeRef"
                }
            }

            ToolAvailabilityScope.SESSION, ToolAvailabilityScope.WORKSPACE -> {
                require(scopeRef.isNotBlank()) {
                    "${scope.name} availability state requires a scopeRef"
                }
            }
        }
    }
}

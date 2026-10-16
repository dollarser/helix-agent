package com.helix.core.storage.repository

import com.helix.core.storage.dao.ModelCallDao
import com.helix.core.storage.entity.ModelCallEntity

class ModelCallRepository(
    private val dao: ModelCallDao,
    private val transaction: (() -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun append(
        id: String,
        turnId: String,
        providerSnapshot: String,
        state: String,
        requestManifest: String? = null,
    ): ModelCallEntity {
        require(providerSnapshot.isNotBlank()) { "providerSnapshot must not be blank" }
        require(state.isNotBlank()) { "state must not be blank" }
        lateinit var entity: ModelCallEntity
        transaction {
            entity =
                ModelCallEntity(
                    id,
                    turnId,
                    providerSnapshot,
                    state,
                    null,
                    null,
                    null,
                    null,
                    requestManifest,
                    ordinal = dao.maxOrdinal(turnId) + 1,
                    createdAt = clock(),
                )
            dao.insert(entity)
        }
        return entity
    }

    fun find(id: String): ModelCallEntity? = dao.byId(id)

    fun hasOtherRunning(
        turnId: String,
        modelCallId: String,
    ): Boolean = dao.hasOtherRunning(turnId, modelCallId)

    fun resolve(id: String): ModelCallEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("model call not found: $id")
    }

    fun listByTurn(turnId: String): List<ModelCallEntity> = dao.listByTurn(turnId)

    fun bindToolMessage(
        id: String,
        messageId: String,
    ) {
        check(dao.bindToolMessage(id, messageId) == 1) { "MODEL_TOOL_MESSAGE_CONFLICT" }
    }

    /** Reconcile abandoned calls, including rows left by older recovery versions; keep known metadata. */
    fun interruptForInterruptedTurns(): Int = dao.interruptForInterruptedTurns(clock())

    fun update(
        call: ModelCallEntity,
        state: String,
        usage: String?,
        requestId: String?,
    ) {
        require(state.isNotBlank()) { "state must not be blank" }
        dao.update(call.id, state, usage, requestId, if (state == "RUNNING") null else clock())
    }

    /**
     * Records the request's system-prompt snapshot on the call row (research doc section 4.4):
     * the fingerprint of the exact prompt bytes sent plus the redacted section list. Both
     * values are caller-redacted (content hashes, never content).
     */
    fun recordPrompt(
        id: String,
        fingerprint: String,
        sections: String,
    ) {
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
        require(sections.isNotBlank()) { "sections must not be blank" }
        dao.recordPrompt(id, fingerprint, sections)
    }

    /**
     * HXA-217 / ADR-AGENT-005: Records the request context manifest compact JSON on the call row.
     */
    fun recordRequestManifest(
        id: String,
        manifest: String,
    ) {
        require(manifest.isNotBlank()) { "manifest must not be blank" }
        dao.recordRequestManifest(id, manifest)
    }
}

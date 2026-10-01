package com.helix.app.agent

import com.helix.core.agent.RequestProvenanceStore
import com.helix.core.agent.WorkspaceBindingSnapshot
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.SessionWorkspaceEntity

/** Persists the exact already-frozen request binding; it never resolves a newer Workspace. */
internal class RoomRequestProvenance(
    private val storage: HelixStorage,
) : RequestProvenanceStore {
    override suspend fun inputIds(
        turnId: String,
        messageIds: Set<String>,
    ): List<String> = storage.sessionInputs.appendedForMessages(turnId, messageIds).map { it.inputId }

    override suspend fun recordWorkspace(
        modelCallId: String,
        binding: WorkspaceBindingSnapshot,
    ) {
        storage.workspaces.recordRequest(
            modelCallId,
            SessionWorkspaceEntity(binding.sessionId, binding.workspaceId, binding.relativePath, binding.revision),
        )
    }
}

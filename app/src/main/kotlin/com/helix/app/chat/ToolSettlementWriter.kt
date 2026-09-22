package com.helix.app.chat

import com.helix.core.model.Clock
import com.helix.core.model.ToolCallState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ToolCallEntity

/** Commit each call's durable outcome and Goal charge before publishing any UI projection. */
internal class ToolSettlementWriter(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    fun persist(
        call: ToolCallEntity,
        state: ToolCallState,
        status: String,
        summary: String,
        content: String? = null,
        verified: Boolean = false,
    ) {
        // Room cannot roll back files. A failed commit can leave an unreferenced content
        // object, but never a terminal call without its result or its budget settlement.
        val contentRef = content?.takeIf { it.isNotBlank() }?.let { storage.contentStore.write(it).toStorageString() }
        storage.withTransaction {
            val existing = storage.toolResults.byToolCall(call.id)
            if (existing == null) {
                val result = storage.toolResults.appendPrepared(idGenerator(), call.id, status, summary, contentRef)
                if (verified) storage.toolResults.markVerified(result)
                storage.toolCalls.updateState(call, state)
            } else {
                check(existing.status == status && existing.verified == verified) { "Conflicting tool settlement" }
                check(storage.toolCalls.resolve(call.id).state == state.name) { "Incomplete tool settlement" }
            }
            GoalToolCallBudget(storage, clock).finish(call.callId)
        }
    }
}

package com.helix.core.storage.repository

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.storage.dao.A2aTaskDao
import com.helix.core.storage.entity.A2aTaskEntity

enum class A2aDeliveryState {
    NOT_SENT,
    ACCEPTED,
    UNKNOWN,
    RECONCILED,
}

enum class A2aPersistedTaskState {
    SUBMITTING,
    WORKING,
    INPUT_REQUIRED,
    COMPLETED,
    FAILED,
    CANCELLED,
    NEEDS_REVIEW,
}

@Suppress("LongParameterList")
class A2aTaskRepository(
    private val dao: A2aTaskDao,
) {
    fun begin(
        toolCallId: String,
        agentId: String,
        skillId: String,
        cardHash: String,
        skillHash: String,
        inputHash: String,
        interfaceUrl: String,
        binding: String,
        protocolVersion: String,
        tenant: String?,
        updatedAt: Long,
    ): A2aTaskEntity {
        require(
            toolCallId.isNotBlank() && agentId.isNotBlank() && skillId.isNotBlank(),
        ) { "A2A task identity is invalid" }
        requireSha256(cardHash, "cardHash")
        requireSha256(skillHash, "skillHash")
        requireSha256(inputHash, "inputHash")
        val endpoint = NormalizedEndpoint.parse(interfaceUrl)
        require(binding == "JSONRPC" || binding == "HTTP+JSON") { "unsupported A2A binding" }
        require(protocolVersion == "1.0") { "unsupported A2A protocol version" }
        require(tenant == null || (tenant.isNotBlank() && tenant.length <= 256)) { "invalid A2A tenant" }
        require(updatedAt >= 0) { "updatedAt must be non-negative" }
        val entity =
            A2aTaskEntity(
                toolCallId = toolCallId,
                agentId = agentId,
                skillId = skillId,
                cardHash = cardHash,
                skillHash = skillHash,
                inputHash = inputHash,
                interfaceUrl = endpoint.full,
                binding = binding,
                protocolVersion = protocolVersion,
                tenant = tenant,
                taskId = null,
                contextId = null,
                lastEventSequence = -1,
                lastEventId = null,
                state = A2aPersistedTaskState.SUBMITTING.name,
                deliveryState = A2aDeliveryState.NOT_SENT.name,
                updatedAtEpochMillis = updatedAt,
            )
        dao.insert(entity)
        return entity
    }

    fun resolve(toolCallId: String): A2aTaskEntity? = dao.byToolCall(toolCallId)

    fun listUnsettled(): List<A2aTaskEntity> = dao.listUnsettled()

    fun markAccepted(
        current: A2aTaskEntity,
        taskId: String,
        contextId: String?,
        state: A2aPersistedTaskState,
        sequence: Long,
        eventId: String?,
        updatedAt: Long,
    ): A2aTaskEntity {
        require(
            current.taskId == null || current.taskId == taskId,
        ) { "A2A Task id cannot change during reconciliation" }
        require(current.contextId == null || current.contextId == contextId) { "A2A context id cannot change" }
        require(taskId.isNotBlank() && taskId.length <= 512) { "A2A Task id is invalid" }
        require(contextId == null || contextId.isNotBlank()) { "A2A context id is invalid" }
        require(sequence >= current.lastEventSequence) { "A2A event sequence regressed" }
        require(eventId == null || (eventId.isNotBlank() && eventId.length <= 512)) { "A2A event id is invalid" }
        return update(
            current,
            taskId,
            contextId,
            sequence,
            eventId ?: current.lastEventId,
            state,
            A2aDeliveryState.RECONCILED,
            updatedAt,
        )
    }

    fun markDeliveryUnknown(
        current: A2aTaskEntity,
        updatedAt: Long,
    ): A2aTaskEntity =
        update(
            current,
            current.taskId,
            current.contextId,
            current.lastEventSequence,
            current.lastEventId,
            A2aPersistedTaskState.NEEDS_REVIEW,
            A2aDeliveryState.UNKNOWN,
            updatedAt,
        )

    fun markNotSentFailed(
        current: A2aTaskEntity,
        updatedAt: Long,
    ): A2aTaskEntity =
        update(
            current,
            current.taskId,
            current.contextId,
            current.lastEventSequence,
            current.lastEventId,
            A2aPersistedTaskState.FAILED,
            A2aDeliveryState.NOT_SENT,
            updatedAt,
        )

    fun markDirectCompleted(
        current: A2aTaskEntity,
        updatedAt: Long,
    ): A2aTaskEntity =
        update(
            current,
            null,
            current.contextId,
            current.lastEventSequence.coerceAtLeast(0),
            current.lastEventId,
            A2aPersistedTaskState.COMPLETED,
            A2aDeliveryState.RECONCILED,
            updatedAt,
        )

    private fun update(
        current: A2aTaskEntity,
        taskId: String?,
        contextId: String?,
        sequence: Long,
        eventId: String?,
        state: A2aPersistedTaskState,
        delivery: A2aDeliveryState,
        updatedAt: Long,
    ): A2aTaskEntity {
        require(updatedAt >= current.updatedAtEpochMillis) { "A2A task timestamp regressed" }
        check(
            dao.updateRemoteState(
                current.toolCallId,
                taskId,
                contextId,
                sequence,
                eventId,
                state.name,
                delivery.name,
                updatedAt,
            ) == 1,
        ) { "A2A task not found: ${current.toolCallId}" }
        return current.copy(
            taskId = taskId,
            contextId = contextId,
            lastEventSequence = sequence,
            lastEventId = eventId,
            state = state.name,
            deliveryState = delivery.name,
            updatedAtEpochMillis = updatedAt,
        )
    }

    private fun requireSha256(
        value: String,
        label: String,
    ) {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "$label must be lowercase SHA-256"
        }
    }
}

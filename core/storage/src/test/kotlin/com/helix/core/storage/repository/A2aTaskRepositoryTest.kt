package com.helix.core.storage.repository

import com.helix.core.storage.dao.A2aTaskDao
import com.helix.core.storage.entity.A2aTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class A2aTaskRepositoryTest {
    @Test
    fun `one ToolCall binds one immutable remote Task and monotonic event sequence`() {
        val dao = FakeA2aTaskDao()
        val repository = A2aTaskRepository(dao)
        val started = begin(repository, "call-1", hash('c'), 10)
        val accepted =
            repository.markAccepted(
                started,
                "task-1",
                "context-1",
                A2aPersistedTaskState.WORKING,
                2,
                "event-2",
                20,
            )
        val completed =
            repository.markAccepted(
                accepted,
                "task-1",
                "context-1",
                A2aPersistedTaskState.COMPLETED,
                3,
                null,
                30,
            )

        assertEquals("task-1", completed.taskId)
        assertEquals(A2aDeliveryState.RECONCILED.name, completed.deliveryState)
        assertEquals(A2aPersistedTaskState.COMPLETED.name, completed.state)
        assertEquals("event-2", completed.lastEventId)
        assertThrows(IllegalArgumentException::class.java) {
            repository.markAccepted(completed, "task-2", "context-1", A2aPersistedTaskState.COMPLETED, 4, null, 40)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.markAccepted(completed, "task-1", "context-1", A2aPersistedTaskState.COMPLETED, 1, null, 40)
        }
    }

    @Test
    fun `ambiguous delivery parks NEEDS_REVIEW without inventing a remote Task`() {
        val dao = FakeA2aTaskDao()
        val repository = A2aTaskRepository(dao)
        val started = begin(repository, "call-1", hash('c'), 10)

        val parked = repository.markDeliveryUnknown(started, 11)

        assertEquals(null, parked.taskId)
        assertEquals(A2aDeliveryState.UNKNOWN.name, parked.deliveryState)
        assertEquals(A2aPersistedTaskState.NEEDS_REVIEW.name, parked.state)
        assertEquals(listOf("call-1"), repository.listUnsettled().map { it.toolCallId })
    }

    @Test
    fun `confirmed pre-send failure and direct response settle without retryable ambiguity`() {
        val dao = FakeA2aTaskDao()
        val repository = A2aTaskRepository(dao)
        val failed = repository.markNotSentFailed(begin(repository, "call-f", hash('c'), 1), 2)
        val direct = repository.markDirectCompleted(begin(repository, "call-d", hash('d'), 1), 2)

        assertEquals(A2aDeliveryState.NOT_SENT.name, failed.deliveryState)
        assertEquals(A2aPersistedTaskState.FAILED.name, failed.state)
        assertEquals(A2aDeliveryState.RECONCILED.name, direct.deliveryState)
        assertEquals(A2aPersistedTaskState.COMPLETED.name, direct.state)
    }

    private fun hash(char: Char): String = char.toString().repeat(64)

    private fun begin(
        repository: A2aTaskRepository,
        toolCallId: String,
        inputHash: String,
        updatedAt: Long,
    ): A2aTaskEntity =
        repository.begin(
            toolCallId = toolCallId,
            agentId = "agent-1",
            skillId = "skill-1",
            cardHash = hash('a'),
            skillHash = hash('b'),
            inputHash = inputHash,
            interfaceUrl = "https://agent.example/a2a",
            binding = "JSONRPC",
            protocolVersion = "1.0",
            tenant = "tenant-a",
            updatedAt = updatedAt,
        )
}

private class FakeA2aTaskDao : A2aTaskDao {
    private val rows = linkedMapOf<String, A2aTaskEntity>()

    override fun insert(task: A2aTaskEntity) {
        check(rows.putIfAbsent(task.toolCallId, task) == null)
    }

    override fun byToolCall(toolCallId: String): A2aTaskEntity? = rows[toolCallId]

    override fun listUnsettled(): List<A2aTaskEntity> =
        rows.values.filter { it.state !in setOf("COMPLETED", "FAILED", "CANCELLED") }

    override fun updateRemoteState(
        toolCallId: String,
        taskId: String?,
        contextId: String?,
        sequence: Long,
        eventId: String?,
        state: String,
        deliveryState: String,
        updatedAt: Long,
    ): Int {
        val current = rows[toolCallId] ?: return 0
        rows[toolCallId] =
            current.copy(
                taskId = taskId,
                contextId = contextId,
                lastEventSequence = sequence,
                lastEventId = eventId,
                state = state,
                deliveryState = deliveryState,
                updatedAtEpochMillis = updatedAt,
            )
        return 1
    }
}

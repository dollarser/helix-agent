package com.helix.app.agent

import com.helix.core.model.ModelRole
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.MessageEntity

/** Only settled, paired tool batches can cross a checkpoint boundary. */
internal object ContextSegments {
    fun mapped(
        storage: HelixStorage,
        row: MessageEntity,
    ) = ChatHistoryBuilder.toModelMessagesStrict(
        listOf(
            ChatHistoryBuilder.PersistedRow(
                row.turnId,
                row.role,
                row.kind,
                storage.messages.readContent(row),
                row.id,
            ),
        ),
    )

    fun candidates(
        storage: HelixStorage,
        history: List<MessageEntity>,
        currentTurn: String,
    ): List<List<MessageEntity>> {
        val protected =
            history
                .mapNotNull { it.turnId }
                .distinct()
                .takeLast(2)
                .toSet() + currentTurn
        val old =
            history
                .takeWhile { it.turnId !in protected }
                .filter { it.turnId != null && it.role != ModelRole.SYSTEM.name }
        if (old.isNotEmpty()) return settledGroups(storage, old)
        val stepRows = history.filter { it.role in setOf(ModelRole.ASSISTANT.name, ModelRole.TOOL.name) }
        val steps = settledGroups(storage, stepRows.filter { it.turnId == currentTurn })
        // Keep the newest complete step and all pending batches; current input stays verbatim.
        val currentPrefix = steps.dropLast(1)
        // A new Turn has no settled steps yet. Its first request may already exceed the window.
        val previousTurn = history.mapNotNull { it.turnId }.lastOrNull { it != currentTurn }
        return if (currentPrefix.isNotEmpty() || previousTurn == null) {
            currentPrefix
        } else {
            settledGroups(storage, stepRows.filter { it.turnId == previousTurn }).dropLast(1)
        }
    }

    private fun settledGroups(
        storage: HelixStorage,
        rows: List<MessageEntity>,
    ): List<List<MessageEntity>> {
        val groups = mutableListOf<List<MessageEntity>>()
        var batch = mutableListOf<MessageEntity>()
        val pending = mutableSetOf<String>()
        val modelRows = rows.mapNotNull { row -> mapped(storage, row).singleOrNull()?.let { row to it } }
        for ((row, message) in modelRows) {
            val valid =
                if (message.role == ModelRole.TOOL) {
                    pending.remove(message.toolCallId?.value)
                } else {
                    pending.isEmpty()
                }
            if (!valid) break
            if (message.toolCalls.isNotEmpty()) {
                pending.addAll(message.toolCalls.map { it.id.value })
                batch.add(row)
            } else if (message.role == ModelRole.TOOL) {
                batch.add(row)
                if (pending.isEmpty()) {
                    groups.add(batch.toList())
                    batch = mutableListOf()
                }
            } else {
                groups.add(listOf(row))
            }
        }
        return groups
    }

    fun remainingRequest(
        storage: HelixStorage,
        history: List<MessageEntity>,
        removed: Set<String>,
        previous: ContextCompaction.Checkpoint?,
        request: ChatContextRequest,
    ): ChatContextRequest? {
        val source =
            history
                .flatMap { row -> mapped(storage, row).map { row.id to it } }
                .filter { it.second.role != ModelRole.SYSTEM }
        val actual = request.messages.filter { it.role != ModelRole.SYSTEM }.drop(if (previous == null) 0 else 1)
        // A reordered retry uses a different history: do not infer positional identity.
        if (actual.size != source.size ||
            actual.zip(source).any { (message, row) ->
                message.copy(images = emptyList()) != row.second.copy(images = emptyList())
            }
        ) {
            return null
        }
        return request.copy(
            messages =
                request.messages.filter { it.role == ModelRole.SYSTEM } +
                    actual.filterIndexed { index, _ -> source[index].first !in removed },
        )
    }
}

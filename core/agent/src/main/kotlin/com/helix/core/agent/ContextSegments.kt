package com.helix.core.agent

import com.helix.core.agent.ContextCheckpoint
import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.ModelRole

/** Only settled, paired tool batches can cross a checkpoint boundary. */
object ContextSegments {
    /** Derived pixel selection may differ; canonical text and call identity must still match exactly. */
    fun sameHistoryMessage(
        actual: com.helix.core.model.ModelMessage,
        persisted: com.helix.core.model.ModelMessage,
    ): Boolean = actual.withoutVisualProjection() == persisted.withoutVisualProjection()

    fun candidates(
        history: List<ContextSourceRow>,
        currentTurn: String,
    ): List<List<ContextSourceRow>> {
        val protected =
            history
                .mapNotNull { it.turnId }
                .distinct()
                .takeLast(2)
                .toSet() + currentTurn
        val old =
            history
                .takeWhile { it.turnId !in protected }
                .filter { it.role != ModelRole.SYSTEM.name }
        val oldGroups = inheritedPrefix(old)
        if (oldGroups.isNotEmpty()) return oldGroups
        val stepRows = history.filter { it.role in setOf(ModelRole.ASSISTANT.name, ModelRole.TOOL.name) }
        val steps = settledGroups(stepRows.filter { it.turnId == currentTurn })
        // Keep the newest complete step and all pending batches; current input stays verbatim.
        val currentPrefix = steps.dropLast(1)
        // A new Turn has no settled steps yet. Its first request may already exceed the window.
        val previousTurn = history.mapNotNull { it.turnId }.lastOrNull { it != currentTurn }
        return if (currentPrefix.isNotEmpty() || previousTurn == null) {
            currentPrefix
        } else {
            settledGroups(stepRows.filter { it.turnId == previousTurn }).dropLast(1)
        }
    }

    /** Turn-free fork history remains compactable, while its latest tool batch and tail stay verbatim. */
    private fun inheritedPrefix(rows: List<ContextSourceRow>): List<List<ContextSourceRow>> {
        val groups = settledGroups(rows)
        if (rows.lastOrNull()?.turnId != null || groups.isEmpty()) return groups
        val lastTool = groups.indexOfLast { group -> group.any { it.toolCallBatch } }
        return groups.take(if (lastTool >= 0) lastTool else groups.lastIndex)
    }

    private fun settledGroups(rows: List<ContextSourceRow>): List<List<ContextSourceRow>> {
        val groups = mutableListOf<List<ContextSourceRow>>()
        var batch = mutableListOf<ContextSourceRow>()
        val pending = mutableSetOf<String>()
        val modelRows = rows.mapNotNull { row -> row.mappedMessages().singleOrNull()?.let { row to it } }
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
        snapshot: ContextSourceSnapshot,
        removed: Set<String>,
        request: TurnContextRequest,
    ): TurnContextRequest? {
        val history = snapshot.rows
        val previous = snapshot.checkpoint
        val predecessorId = snapshot.predecessorTurnId
        val source =
            history
                .filter { row -> RecoveryContextFilter.keep(row.turnId, row.role, predecessorId) }
                .flatMap { row -> row.mappedMessages().map { row.id to it } }
                .filter { it.second.role != ModelRole.SYSTEM }
        val actual = request.messages.filter { it.role != ModelRole.SYSTEM }.drop(if (previous == null) 0 else 1)
        // A reordered retry uses a different history: do not infer positional identity.
        if (actual.size != source.size ||
            actual.zip(source).any { (message, row) ->
                !sameHistoryMessage(message, row.second)
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

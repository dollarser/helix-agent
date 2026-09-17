package com.helix.app.chat

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ArtifactEntity

/**
 * One row of the artifact center's files section (doc 02 §8): a file the agent's tools actually
 * wrote, read from the `artifacts` table — the real artifact entry, as opposed to the
 * turn-result rows the same page lists from the turns table. [fileName] is the display name
 * (the path's last segment); [sessionTitle] is the source session's title, null when the
 * session is gone; [turnId] is the turn that last wrote the file (the row's identity is
 * stable per (sessionId, relativePath), so a re-write keeps the row and refreshes it).
 */
internal data class ArtifactRowUi(
    val id: String,
    val sessionId: String,
    val relativePath: String,
    val fileName: String,
    val mediaType: String,
    val sizeBytes: Long,
    val turnId: String?,
    val sessionTitle: String?,
)

/**
 * The cross-session files view of the artifact center: newest-registered first (the DAO orders
 * by rowid — a re-write keeps the row's position, so the order is by FIRST write of a path,
 * doc 02 §8). Same read pattern as [PlanRowQuery] / [GoalSummaryQuery]: a transactional
 * snapshot off the main thread.
 */
internal class ArtifactQuery(
    private val storage: HelixStorage,
) {
    fun recent(limit: Int): List<ArtifactRowUi> {
        var snapshot = emptyList<ArtifactRowUi>()
        storage.withTransaction { snapshot = query(limit) }
        return snapshot
    }

    /**
     * The artifacts of ONE turn by real ownership (HXA-202 slice 2): the rows the turn's
     * tools actually wrote (`turnId` matches), no cross-session window — a task's files can
     * never be lost behind the artifact center's recent truncation.
     */
    fun forTurn(turnId: String): List<ArtifactRowUi> {
        var snapshot = emptyList<ArtifactRowUi>()
        storage.withTransaction { snapshot = storage.artifacts.listByTurn(turnId).map(::toRow) }
        return snapshot
    }

    /**
     * The artifacts of a GOAL by real ownership (HXA-202 slice 2): the union of every
     * turn bound to the goal, in turn-start order. A goal row hides its bound turns from
     * the dashboard list, so the task's files must still be reachable through the goal.
     */
    fun forGoal(goalId: String): List<ArtifactRowUi> {
        var snapshot = emptyList<ArtifactRowUi>()
        storage.withTransaction {
            val turnIds = storage.goalTurnBindings.turnsForGoal(goalId)
            snapshot =
                turnIds
                    .flatMap { storage.artifacts.listByTurn(it) }
                    .distinctBy { it.id }
                    .map(::toRow)
        }
        return snapshot
    }

    private fun query(limit: Int): List<ArtifactRowUi> = storage.artifacts.recent(limit).map(::toRow)

    private fun toRow(entity: ArtifactEntity): ArtifactRowUi =
        ArtifactRowUi(
            entity.id,
            entity.sessionId,
            entity.relativePath,
            entity.relativePath.substringAfterLast('/'),
            entity.mediaType,
            entity.size,
            entity.turnId,
            runCatching { storage.sessions.resolve(entity.sessionId) }.getOrNull()?.title,
        )
}

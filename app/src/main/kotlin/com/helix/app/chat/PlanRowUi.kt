package com.helix.app.chat

import com.helix.core.storage.HelixStorage

/**
 * One row of the cross-session plan review queue (research doc section 4.2, P0-B Tasks
 * dashboard, doc section 12/13). [state] is the persisted [com.helix.core.storage.repository.PlanLifecycleState]
 * name; only READY rows surface in the dashboard's "needs you" bucket.
 */
internal data class PlanRowUi(
    val id: String,
    val objective: String,
    val version: Int,
    val state: String,
)

internal class PlanRowQuery(
    private val storage: HelixStorage,
) {
    fun read(): List<PlanRowUi> {
        var snapshot = emptyList<PlanRowUi>()
        storage.withTransaction { snapshot = query() }
        return snapshot
    }

    private fun query(): List<PlanRowUi> =
        storage.plans.list().map { entity ->
            PlanRowUi(entity.id, entity.objective, entity.version, entity.state)
        }
}

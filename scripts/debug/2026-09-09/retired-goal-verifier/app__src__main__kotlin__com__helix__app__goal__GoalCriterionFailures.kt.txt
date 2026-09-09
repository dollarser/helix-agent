package com.helix.app.goal

import com.helix.core.storage.entity.AuditEventEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Diagnostic projection only; audit text never supplies completion evidence or authority. */
internal object GoalCriterionFailures {
    fun from(events: List<AuditEventEntity>): Map<String, GoalEvidenceFailure> {
        val failures = mutableMapOf<String, GoalEvidenceFailure>()
        events.filter { it.type in TYPES }.forEach { event ->
            val fields = fields(event.redactedPayload)
            val criterion = (fields?.get("criterionId") as? JsonPrimitive)?.content
            if (criterion != null) {
                if (event.type == "goal.criterion_invalidated") {
                    failures[criterion] = reason(fields)
                } else {
                    failures.remove(criterion)
                }
            }
        }
        return failures
    }

    private fun reason(fields: JsonObject): GoalEvidenceFailure {
        val name = (fields["reason"] as? JsonPrimitive)?.content
        return GoalEvidenceFailure.entries.find { it.name == name } ?: GoalEvidenceFailure.UNKNOWN
    }

    @Suppress("SwallowedException") // Unreadable diagnostic metadata cannot affect Goal state.
    private fun fields(payload: String): JsonObject? =
        try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

    private val TYPES =
        setOf(
            "goal.criterion_invalidated",
            "goal.criterion_bound",
            "goal.criterion_review_staged",
            "goal.criterion_review_cleared",
        )
}

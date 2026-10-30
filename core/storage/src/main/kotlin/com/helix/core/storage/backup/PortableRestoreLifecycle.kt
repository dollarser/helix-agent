package com.helix.core.storage.backup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Restored facts never carry execution admission, live ownership or approval forward. */
internal object PortableRestoreLifecycle {
    fun apply(
        table: String,
        original: JsonObject,
        reviewTurns: Set<String>,
        reviewGoals: Set<String>,
        now: Long,
    ): JsonObject {
        val row = original.toMutableMap()
        when (table) {
            "turns" -> {
                turn(row, reviewTurns, now)
            }

            "model_calls" -> {
                if (row.text("state") == "RUNNING") {
                    row.string("state", "INTERRUPTED")
                    row.finish("settledAt", "createdAt", now)
                }
            }

            "tool_calls" -> {
                when (row.text("state")) {
                    "PENDING", "AWAITING_APPROVAL" -> row.string("state", "CANCELLED")
                    "RUNNING" -> row.string("state", "NEEDS_REVIEW")
                }
            }

            "goals" -> {
                goal(row, reviewGoals)
            }

            "goal_runs" -> {
                run(row, reviewGoals, now)
            }

            else -> {
                metadata(table, row)
            }
        }
        return JsonObject(row)
    }

    private fun turn(
        row: MutableMap<String, JsonElement>,
        reviews: Set<String>,
        now: Long,
    ) {
        if (row.text("state") !in setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED")) {
            if (row.text("id") in reviews || row.text("state") == "NEEDS_REVIEW") {
                row.string("state", "NEEDS_REVIEW")
                row.string("errorCode", "RESTORE_REVIEW_REQUIRED")
                row["endedAt"] = JsonNull
            } else {
                row.string("state", "INTERRUPTED")
                row.string("errorCode", "RESTORE_INTERRUPTED")
                row.finish("endedAt", "startedAt", now)
            }
        }
    }

    private fun goal(
        row: MutableMap<String, JsonElement>,
        reviews: Set<String>,
    ) {
        if (row.text("id") in reviews) {
            row.string("state", "BLOCKED")
        } else if (row.text("state") !in setOf("COMPLETED", "FAILED", "CANCELLED", "BLOCKED", "DRAFT")) {
            row.string("state", "PAUSED")
        }
        row["currentWakeMillis"] = JsonPrimitive(0)
    }

    private fun run(
        row: MutableMap<String, JsonElement>,
        reviews: Set<String>,
        now: Long,
    ) {
        if (row["endedAt"] == JsonNull) {
            row.string("outcome", if (row.text("goalId") in reviews) "BLOCKED(NEEDS_REVIEW)" else "INTERRUPTED")
            row.finish("endedAt", "startedAt", now)
            if (row["wakeDurationMillis"] == JsonNull) row["wakeDurationMillis"] = JsonPrimitive(0)
        }
    }

    private fun metadata(
        table: String,
        row: MutableMap<String, JsonElement>,
    ) {
        when (table) {
            "goal_controls" -> {
                row["pendingTurnId"] = JsonNull
                row["pendingJson"] = JsonNull
            }

            "plans" -> {
                if (row.text("state") in setOf("APPROVED", "EXECUTING")) row.string("state", "READY")
                row["approvedSnapshot"] = JsonNull
            }

            "mcp_servers" -> {
                row["enabled"] = JsonPrimitive(0)
                row.string("trustState", "UNTRUSTED")
            }

            "a2a_agents" -> {
                row["enabled"] = JsonPrimitive(0)
            }

            "workspaces" -> {
                row.string("availability", "UNAVAILABLE")
                row["witness"] = JsonNull
            }

            "session_inputs" -> {
                if (row.text("state") in setOf("PENDING", "NEEDS_ATTENTION")) {
                    row.string("state", "NEEDS_ATTENTION")
                    row.string("blockedReason", "RESTORE_INTERRUPTED")
                }
            }
        }
    }
}

private fun Map<String, JsonElement>.text(key: String) = getValue(key).jsonPrimitive.content

private fun MutableMap<String, JsonElement>.string(
    key: String,
    value: String,
) {
    this[key] = JsonPrimitive(value)
}

private fun MutableMap<String, JsonElement>.finish(
    key: String,
    started: String,
    now: Long,
) {
    this[key] = JsonPrimitive(now.coerceAtLeast(getValue(started).jsonPrimitive.long))
}

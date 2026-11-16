package com.helix.app.goal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Matches snapshots and the goal_update report echo; neither result implies work has executed. */
internal object GoalToolOutputSchema {
    private val snapshot =
        Json
            .parseToJsonElement(
                """{"description":"Goal object, or null when no goal exists.","properties":{
        "id":{"type":"string"},"objective":{"type":"string"},"state":{"type":"string"},
        "revision":{"type":"integer","minimum":0},"armed":{"type":"boolean"},
        "pending_change":{"description":"Staged edit object, or null."},
        "last_outcome":{"description":"Last observed outcome string, or null."},
        "rounds_started":{"type":"integer","minimum":0},"model_calls":{"type":"integer","minimum":0},
        "tool_calls":{"type":"integer","minimum":0},"tokens":{"type":"integer","minimum":0},
        "runtime_millis":{"type":"integer","minimum":0},"max_goal_rounds":{"type":"integer","minimum":1},
        "blocker_code":{"description":"Blocker code string, or null."}
        },"required":["id","objective","state","revision","armed","pending_change","last_outcome",
        "rounds_started","model_calls","tool_calls","tokens","runtime_millis","max_goal_rounds","blocker_code"],
        "additionalProperties":false}""",
            ).jsonObject

    fun forTool(name: String): JsonObject {
        val properties = mutableMapOf("goal" to snapshot)
        if (name == "goal_update") {
            // A report returns its accepted fields; an edit returns a pending snapshot.
            val report =
                Json
                    .parseToJsonElement(
                        """{"id":{"type":"string"},"expected_revision":{"type":"integer","minimum":0},
                "status":{"type":"string"},"summary":{"type":"string"},
                "user_request":{"type":"string"}}""",
                    ).jsonObject
            properties.putAll(report.mapValues { it.value.jsonObject })
        }
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "description" to
                    JsonPrimitive(
                        "Current goal snapshot (null when absent); goal_update reports echo accepted report fields. " +
                            "pending_change has not settled yet. Counts reflect observed usage, not remaining quota.",
                    ),
                "properties" to JsonObject(properties),
                "required" to JsonArray(if (name == "goal_update") emptyList() else listOf(JsonPrimitive("goal"))),
                "additionalProperties" to JsonPrimitive(false),
            ),
        )
    }
}

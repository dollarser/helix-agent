package com.helix.core.storage.mapping

import com.helix.core.model.GoalBudgets
import com.helix.core.model.HelixError
import com.helix.core.model.PlanArtifact
import com.helix.core.model.TurnState
import com.helix.core.storage.criteria.CriteriaCodec
import com.helix.core.storage.criteria.StoredCriterion
import com.helix.core.storage.entity.GoalEntity
import com.helix.core.storage.entity.PlanEntity
import com.helix.core.storage.internal.Value
import com.helix.core.storage.internal.parseStrictArray

/**
 * Storage-level goal row (architecture doc 9.1 + ADR-0004 recovery fields). The `core:agent`
 * `Goal` is the domain type; the recovery coordinator (HXA-015) maps between `Goal` and
 * [StoredGoal], so this module stays independent of `core:agent`.
 */
data class StoredGoal(
    val id: String,
    val objective: String,
    val criteria: List<StoredCriterion>,
    val budgets: GoalBudgets,
    val state: String,
    val planId: String?,
    val planHash: String?,
    val nextCheckpoint: Long?,
    val correlationId: String,
    val runCount: Int,
    val modelCalls: Int,
    val toolCalls: Int,
    val totalTokens: Long,
    val runTimeMillis: Long,
    val currentWakeMillis: Long,
    val lastWakeReason: String?,
    val error: HelixError?,
    val finishReason: String?,
) {
    init {
        require((planId == null) == (planHash == null)) { "planId and planHash must be set together" }
    }
}

object EntityMappers {
    fun planEntityFor(
        artifact: PlanArtifact,
        state: String,
        evidenceRef: String?,
    ): PlanEntity =
        PlanEntity(
            id = artifact.id.value,
            objective = artifact.objective,
            version = artifact.version,
            hash = artifact.sha256().hex,
            state = state,
            evidenceRef = evidenceRef,
        )

    /** Canonical JSON string list: `["a","b"]` with strict escaping. */
    fun stringListJson(items: List<String>): String =
        items.joinToString(separator = ",", prefix = "[", postfix = "]") { "\"${escapeJson(it)}\"" }

    /** Parses a canonical JSON string list produced by [stringListJson]. */
    fun parseStringListJson(text: String): List<String> =
        parseStrictArray(text).map {
            (it as? Value.Str)?.value
                ?: throw IllegalArgumentException("plan list item must be a string")
        }

    private fun escapeJson(value: String): String =
        value
            .map { char ->
                when (char) {
                    '"' -> {
                        "\\\""
                    }

                    '\\' -> {
                        "\\\\"
                    }

                    '\n' -> {
                        "\\n"
                    }

                    '\r' -> {
                        "\\r"
                    }

                    '\t' -> {
                        "\\t"
                    }

                    '\b' -> {
                        "\\b"
                    }

                    '\u000C' -> {
                        "\\f"
                    }

                    else -> {
                        if (char < ' ') {
                            "\\u${char.code.toString(16).padStart(4, '0')}"
                        } else {
                            char.toString()
                        }
                    }
                }
            }.joinToString("")
}

fun StoredGoal.toGoalEntity(): GoalEntity =
    GoalEntity(
        id = id,
        objective = objective,
        criteria = CriteriaCodec.encode(criteria),
        budgets = budgets.toStorageString(),
        state = state,
        planId = planId,
        planHash = planHash,
        nextCheckpoint = nextCheckpoint,
        correlationId = correlationId,
        runCount = runCount,
        modelCalls = modelCalls,
        toolCalls = toolCalls,
        totalTokens = totalTokens,
        runTimeMillis = runTimeMillis,
        currentWakeMillis = currentWakeMillis,
        lastWakeReason = lastWakeReason,
        error = error?.toStorageString(),
        finishReason = finishReason,
    )

fun GoalEntity.toStoredGoal(): StoredGoal =
    StoredGoal(
        id = id,
        objective = objective,
        criteria = CriteriaCodec.decode(criteria),
        budgets = GoalBudgets.parse(budgets),
        state = state,
        planId = planId,
        planHash = planHash,
        nextCheckpoint = nextCheckpoint,
        correlationId = correlationId,
        runCount = runCount,
        modelCalls = modelCalls,
        toolCalls = toolCalls,
        totalTokens = totalTokens,
        runTimeMillis = runTimeMillis,
        currentWakeMillis = currentWakeMillis,
        lastWakeReason = lastWakeReason,
        error = error?.let { HelixError.parse(it) },
        finishReason = finishReason,
    )

/** Safe enum name -> value lookup used for persisted state columns. */
fun <T : Enum<T>> enumByName(
    name: String,
    enumClass: Class<T>,
    field: String,
): T =
    enumClass.enumConstants.firstOrNull { it.name == name }
        ?: throw IllegalArgumentException("unknown $field: '$name'")

/** Validates a persisted turn state column value. */
fun turnStateName(state: String): TurnState = enumByName(state, TurnState::class.java, "turn state")

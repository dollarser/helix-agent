package com.helix.core.storage.mapping

import com.helix.core.model.GoalBudgets
import com.helix.core.model.HelixError
import com.helix.core.model.PlanArtifact
import com.helix.core.model.PlanId
import com.helix.core.model.PlanStep
import com.helix.core.model.TurnState
import com.helix.core.storage.criteria.CriteriaCodec
import com.helix.core.storage.criteria.StoredCriterion
import com.helix.core.storage.entity.GoalEntity
import com.helix.core.storage.entity.PlanEntity
import com.helix.core.storage.entity.PlanStepEntity
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
    val retries: Int,
    val lastWakeReason: String?,
    val error: HelixError?,
    val finishReason: String?,
) {
    init {
        require((planId == null) == (planHash == null)) { "planId and planHash must be set together" }
    }
}

object EntityMappers {
    /**
     * Builds the `plans` row from a [PlanArtifact]. Per ADR-0001 `PlanArtifact` carries only a
     * canonical writer (no decoder), so the normalized columns are the recovery source: every
     * artifact field lands in its own column (steps in `plan_steps`), and [PlanArtifact.sha256]
     * is stored as the integrity check binding the columns to the exact artifact version.
     */
    fun planEntityFor(
        artifact: PlanArtifact,
        state: String,
        evidenceRef: String?,
    ): PlanEntity {
        require(state.isNotBlank()) { "plan state must not be blank" }
        return PlanEntity(
            id = artifact.id.value,
            objective = artifact.objective,
            assumptionsJson = stringListJson(artifact.assumptions),
            acceptanceCriteriaJson = stringListJson(artifact.acceptanceCriteria),
            risksJson = stringListJson(artifact.risks),
            version = artifact.version,
            hash = artifact.sha256().hex,
            state = state,
            evidenceRef = evidenceRef,
        )
    }

    fun planStepsFor(artifact: PlanArtifact): List<PlanStepEntity> =
        artifact.steps.mapIndexed { index, step ->
            PlanStepEntity(
                rowId = 0,
                planId = artifact.id.value,
                sequence = index,
                title = step.title,
                description = step.description,
            )
        }

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

/**
 * Rebuilds the [PlanArtifact] from the normalized `plans` columns and [steps] (ADR-0001
 * recovery path for a writer-only type) and verifies the stored hash binds them to this exact
 * artifact version. [steps] must be in `sequence` order as returned by the DAO.
 */
fun PlanEntity.toPlanArtifact(steps: List<PlanStepEntity>): PlanArtifact {
    steps.forEachIndexed { index, step ->
        require(step.sequence == index) { "plan steps out of order at $index" }
    }
    val artifact =
        PlanArtifact(
            id = PlanId(id),
            objective = objective,
            assumptions = EntityMappers.parseStringListJson(assumptionsJson),
            steps = steps.map { PlanStep(it.title, it.description) },
            acceptanceCriteria = EntityMappers.parseStringListJson(acceptanceCriteriaJson),
            risks = EntityMappers.parseStringListJson(risksJson),
            version = version,
        )
    require(artifact.sha256().hex == hash) {
        "plan column hash does not match the normalized plan"
    }
    return artifact
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
        retries = retries,
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
        retries = retries,
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

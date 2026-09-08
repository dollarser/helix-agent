package com.helix.app.goal

import com.helix.core.agent.ArtifactCriterionCheck
import com.helix.core.model.CriterionEvidenceSource
import com.helix.core.model.GoalId
import com.helix.core.model.GoalRunId
import com.helix.core.model.GoalState
import com.helix.core.model.Hex
import com.helix.core.model.SessionId
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** A verified source snapshot, not an assertion that a natural-language criterion was met. */
internal data class GoalToolEvidenceSnapshot(
    val source: CriterionEvidenceSource,
    val call: ToolCallEntity,
    val result: ToolResultEntity,
    val content: String?,
    val hash: Sha256,
)

/** Reads only existing local results. No dispatch, network, scope changes or automatic replay. */
internal class GoalToolEvidenceReader(
    private val storage: HelixStorage,
    private val registry: ToolRegistry,
) {
    fun read(
        goalId: String,
        toolCallId: String,
    ): GoalToolEvidenceSnapshot = goalEvidenceCheck(storage) { readBound(goalId, toolCallId) }

    private fun readBound(
        goalId: String,
        toolCallId: String,
    ): GoalToolEvidenceSnapshot {
        val call = storage.toolCalls.resolve(toolCallId)
        requireGoalEvidence(call.state == ToolCallState.COMPLETED.name, GoalEvidenceFailure.SOURCE_NOT_READY)
        val descriptor = registry.resolve(ToolName(call.name), ToolVersion(call.version.toInt()))
        requireGoalEvidence(descriptor.origin is ToolOrigin.BuiltInOrigin, GoalEvidenceFailure.UNSUPPORTED_SOURCE)
        requireGoalEvidence(
            call.argsJson.length <= ArtifactCriterionCheck.MAX_CONTENT_BYTES,
            GoalEvidenceFailure.TOO_LARGE,
        )
        require(
            com.helix.core.storage.content.FileContentStore
                .sha256Hex(call.argsJson.toByteArray(Charsets.UTF_8)) ==
                call.argsHash,
        ) {
            "evidence arguments hash mismatch"
        }
        val source = source(goalId, call.turnId)
        val result = requireNotNull(storage.toolResults.byToolCall(call.id)) { "evidence result is missing" }
        requireGoalEvidence(result.status == "SUCCEEDED" && result.verified, GoalEvidenceFailure.SOURCE_NOT_READY)
        requireGoalEvidence(
            result.summary.length <= ArtifactCriterionCheck.MAX_CONTENT_BYTES,
            GoalEvidenceFailure.TOO_LARGE,
        )
        val content =
            result.contentRef?.let {
                readContent(it)
            }
        return GoalToolEvidenceSnapshot(source, call, result, content, hash(call, result, content))
    }

    private fun readContent(reference: String): String {
        val ref = ContentRef.parse(reference)
        requireGoalEvidence(ref.size <= ArtifactCriterionCheck.MAX_CONTENT_BYTES, GoalEvidenceFailure.TOO_LARGE)
        return try {
            storage.contentStore.readBounded(ref, ArtifactCriterionCheck.MAX_CONTENT_BYTES)
        } catch (failure: IllegalArgumentException) {
            throw GoalEvidenceRejected(GoalEvidenceFailure.CONTENT_CHANGED, failure)
        }
    }

    private fun source(
        goalId: String,
        turnId: String,
    ): CriterionEvidenceSource {
        val binding = requireNotNull(storage.goalTurnBindings.byTurn(turnId)) { "evidence Turn has no Goal binding" }
        val run = storage.goalRuns.resolve(binding.runId)
        val turn = storage.turns.resolve(turnId)
        requireGoalEvidence(run.goalId == goalId, GoalEvidenceFailure.SOURCE_MISMATCH)
        requireGoalEvidence(
            turn.state == TurnState.COMPLETED.name && turn.endedAt != null,
            GoalEvidenceFailure.SOURCE_NOT_READY,
        )
        requireGoalEvidence(
            storage.goalTurnBindings.sessionForGoal(goalId) == turn.sessionId,
            GoalEvidenceFailure.SOURCE_MISMATCH,
        )
        requireGoalEvidence(!storage.goalTurnBindings.hasUnsettledCalls(goalId), GoalEvidenceFailure.UNSETTLED_CALLS)
        val validRun =
            if (run.endedAt == null) {
                storage.goals.resolve(goalId).state == GoalState.RUNNING.name
            } else {
                run.outcome in setOf("RUN_FINISHED", "COMPLETED")
            }
        requireGoalEvidence(validRun, GoalEvidenceFailure.SOURCE_NOT_READY)
        return CriterionEvidenceSource(GoalId(goalId), GoalRunId(run.id), SessionId(turn.sessionId), TurnId(turn.id))
    }

    private fun hash(
        call: ToolCallEntity,
        result: ToolResultEntity,
        content: String?,
    ): Sha256 {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            listOf(
                "helix.goal.tool-result.v1",
                call.id,
                call.turnId,
                call.name,
                call.version,
                call.argsHash,
                result.id,
                result.status,
                result.summary,
                result.contentRef.orEmpty(),
                content.orEmpty(),
            ).forEach {
                val field = it.toByteArray(Charsets.UTF_8)
                output.writeInt(field.size)
                output.write(field)
            }
        }
        return Sha256(Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())))
    }
}

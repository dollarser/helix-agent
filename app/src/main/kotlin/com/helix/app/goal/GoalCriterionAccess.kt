package com.helix.app.goal

import com.helix.core.agent.Criterion
import com.helix.core.model.ArtifactRef
import com.helix.core.model.Clock
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.GoalState
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.ToolRegistry
import java.io.File

internal data class GoalCriteriaSnapshot(
    val criteria: List<Criterion>,
    val editable: Boolean,
    val failures: Map<String, GoalEvidenceFailure> = emptyMap(),
)

/** Session-bound user access. UI has no database or verifier authority. */
internal class GoalCriterionAccess(
    private val storage: HelixStorage,
    registry: ToolRegistry,
    workspace: File,
    clock: Clock,
    idGenerator: () -> String,
    editedSource: ScopedEditedArtifactReader?,
) {
    constructor(
        storage: HelixStorage,
        registry: ToolRegistry,
        workspace: File,
        clock: Clock,
        idGenerator: () -> String,
    ) : this(storage, registry, workspace, clock, idGenerator, null)

    private val reader = GoalToolEvidenceReader(storage, registry)
    private val artifacts = GoalToolArtifactStore(storage, workspace, reader, editedSource)
    private val archives = GoalArchiveArtifactStore(storage, workspace, reader)
    private val verifier = GoalCriterionVerifier(reader, artifacts, clock)
    private val editor = GoalCriterionEditor(storage, verifier, clock, idGenerator)

    fun snapshot(
        goalId: String,
        sessionId: String,
    ): GoalCriteriaSnapshot {
        var result: GoalCriteriaSnapshot? = null
        storage.withTransaction {
            requireSession(goalId, sessionId)
            val goal = storage.goals.resolve(goalId).toRuntimeGoal()
            result =
                GoalCriteriaSnapshot(
                    goal.criteria,
                    goal.state in setOf(GoalState.DRAFT, GoalState.READY, GoalState.PAUSED, GoalState.INPUT_REQUIRED) &&
                        storage.goalRuns.listOpenByGoal(goalId).isEmpty(),
                    GoalCriterionFailures.from(storage.auditEvents.listByCorrelation(goal.correlationId.value)),
                )
        }
        return requireNotNull(result)
    }

    fun bind(
        goalId: String,
        sessionId: String,
        expected: Criterion,
        description: String,
        binding: CriterionVerificationBinding?,
    ) {
        storage.withTransaction {
            requireSession(goalId, sessionId)
            editor.bind(goalId, expected, description, binding)
        }
    }

    fun candidates(
        goalId: String,
        sessionId: String,
    ): List<GoalEvidenceCandidate> {
        requireSession(goalId, sessionId)
        return storage.turns.listBySession(sessionId).flatMap { turn ->
            val runId = storage.goalTurnBindings.byTurn(turn.id)?.runId
            if (runId == null || storage.goalRuns.resolve(runId).goalId != goalId) {
                emptyList()
            } else {
                storage.toolCalls
                    .listByTurn(turn.id)
                    .filter { it.state == "COMPLETED" }
                    .flatMap {
                        val result = GoalEvidenceCandidate(it.id, it.name, runId, turn.id)
                        val archiveCandidates =
                            archivePaths(goalId, it.id).map { path ->
                                result.copy(archivePath = path)
                            }
                        archiveCandidates +
                            if (it.name in setOf("write", "edit") && it.version == "1") {
                                listOf(result, result.copy(written = true))
                            } else {
                                listOf(result)
                            }
                    }
            }
        }
    }

    fun preview(
        goalId: String,
        sessionId: String,
        criterionId: String,
        callId: String,
        written: Boolean = false,
        archivePath: String? = null,
    ): GoalEvidencePreview =
        goalEvidenceCheck(storage) {
            requireSession(goalId, sessionId)
            val criterion =
                storage.goals
                    .resolve(goalId)
                    .toRuntimeGoal()
                    .criteria
                    .single { it.id == criterionId }
            val source = reader.read(goalId, callId)
            if (archivePath != null) {
                require(!written)
                val reference = ArtifactRef(archives.capture(goalId, callId, archivePath).id)
                val bytes = archives.read(goalId, callId, reference).second
                val text =
                    Charsets.UTF_8
                        .newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString()
                require('\u0000' !in text) { "artifact is not displayable text" }
                GoalEvidencePreview(criterion, source, reference, text)
            } else if (written) {
                val reference = ArtifactRef(artifacts.capture(goalId, callId, true).id)
                val bytes = artifacts.read(goalId, callId, reference).second
                GoalEvidencePreview(criterion, source, reference, bytes.toString(Charsets.UTF_8))
            } else {
                GoalEvidencePreview(criterion, source)
            }
        }

    fun review(
        goalId: String,
        sessionId: String,
        criterionId: String,
        selection: GoalEvidenceReview,
    ) {
        storage.withTransaction {
            requireSession(goalId, sessionId)
            editor.stageReview(goalId, criterionId, selection)
        }
    }

    fun clearReview(
        goalId: String,
        sessionId: String,
        criterionId: String,
    ) {
        storage.withTransaction {
            requireSession(goalId, sessionId)
            editor.clearReview(goalId, criterionId)
        }
    }

    private fun archivePaths(
        goalId: String,
        callId: String,
    ): List<String> =
        try {
            archives.paths(goalId, callId)
        } catch (_: IllegalArgumentException) {
            emptyList()
        } catch (_: java.io.IOException) {
            emptyList()
        }

    private fun requireSession(
        goalId: String,
        sessionId: String,
    ) {
        val goal = storage.goals.resolve(goalId)
        require(storage.goalTurnBindings.sessionForGoal(goalId) == sessionId || goal.runCount == 0) {
            "Goal belongs to another session"
        }
    }
}

internal data class GoalEvidenceCandidate(
    val callId: String,
    val tool: String,
    val runId: String,
    val turnId: String,
    val written: Boolean = false,
    val archivePath: String? = null,
)

internal data class GoalEvidencePreview(
    val criterion: Criterion,
    val snapshot: GoalToolEvidenceSnapshot,
    val artifactRef: ArtifactRef? = null,
    val body: String = snapshot.content.orEmpty(),
) {
    fun selection(): GoalEvidenceReview {
        val binding = requireNotNull(criterion.binding)
        return GoalEvidenceReview(
            snapshot.call.id,
            binding.hash(criterion.id, criterion.description),
            snapshot.hash,
            artifactRef,
        )
    }
}

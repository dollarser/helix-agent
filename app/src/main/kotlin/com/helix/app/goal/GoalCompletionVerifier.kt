package com.helix.app.goal

import com.helix.core.agent.ArtifactCriterionCheck
import com.helix.core.agent.ArtifactCriterionResult
import com.helix.core.agent.Criterion
import com.helix.core.agent.CriterionEvidence
import com.helix.core.agent.Goal
import com.helix.core.model.ArtifactRef
import com.helix.core.model.Clock
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.GoalState
import com.helix.core.model.Sha256
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.tools.framework.ToolRegistry
import java.io.File
import java.io.IOException

/** Revalidates receipts inside the caller's terminal transaction; completion itself stays in the reducer. */
internal class GoalCompletionVerifier(
    private val storage: HelixStorage,
    registry: ToolRegistry,
    workspace: File,
    private val clock: Clock,
    private val idGenerator: () -> String,
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

    fun refresh(
        goal: Goal,
        turnId: String?,
    ): Goal {
        require(goal.state == GoalState.RUNNING)
        require(!storage.goalTurnBindings.hasUnsettledCalls(goal.id.value))
        val calls =
            if (turnId == null) {
                emptyList()
            } else {
                val run = requireNotNull(storage.goalTurnBindings.byTurn(turnId))
                require(storage.goalRuns.resolve(run.runId).goalId == goal.id.value)
                storage.toolCalls.listByTurn(turnId).filter { it.state == "COMPLETED" }
            }
        val criteria =
            goal.criteria.map { criterion ->
                var invalidReason = "EVIDENCE_INVALID"
                val previous = checked({ invalidReason = it }) { revalidate(goal, criterion) }
                if ((criterion.evidence != null || criterion.pendingReview != null) && previous == null) {
                    storage.auditEvents.append(
                        idGenerator(),
                        goal.correlationId.value,
                        "goal.criterion_invalidated",
                        "SYSTEM",
                        """{"criterionId":"${criterion.id}","reason":"$invalidReason"}""",
                        clock.now().toEpochMilli(),
                    )
                }
                val fresh =
                    previous ?: calls.firstNotNullOfOrNull { call ->
                        checked { automatic(goal.id.value, criterion, call.id) }
                    }
                val cleared = criterion.copy(evidence = null, pendingReview = null)
                fresh?.let(cleared::withEvidence) ?: cleared
            }
        return goal.copy(criteria = criteria)
    }

    private fun revalidate(
        goal: Goal,
        criterion: Criterion,
    ): CriterionEvidence? {
        val pending = criterion.pendingReview
        val existing = criterion.evidence
        return when {
            pending != null -> {
                verifier.review(
                    goal.id.value,
                    criterion,
                    GoalEvidenceReview(
                        pending.toolCallId.value,
                        pending.bindingHash,
                        pending.sourceHash,
                        pending.artifactRef,
                    ),
                )
            }

            existing?.verification != null && criterion.acceptsEvidence(existing) -> {
                val record = requireNotNull(existing.verification)
                val call = requireNotNull(existing.toolCallId).value
                require(record.source.goalId == goal.id)
                val fresh =
                    if (record.method == CriterionVerificationMethod.MANUAL_REVIEW) {
                        verifier.review(
                            goal.id.value,
                            criterion,
                            GoalEvidenceReview(
                                call,
                                record.bindingHash,
                                requireNotNull(record.sourceHash),
                                existing.artifactRef,
                            ),
                        )
                    } else {
                        verifier.automatic(goal.id.value, criterion, call, existing.artifactRef)
                    }
                fresh?.takeIf {
                    it.verification?.contentHash == record.contentHash &&
                        it.verification?.source == record.source
                }
            }

            else -> {
                null
            }
        }
    }

    private fun automatic(
        goalId: String,
        criterion: Criterion,
        callId: String,
    ): CriterionEvidence? {
        val rule = criterion.binding
        if (rule == null || rule.method == CriterionVerificationMethod.MANUAL_REVIEW) return null
        return if (rule.method == CriterionVerificationMethod.LOCAL_TOOL_SUCCESS) {
            verifier.automatic(goalId, criterion, callId)
        } else {
            artifactEvidence(goalId, criterion, callId) ?: archiveEvidence(goalId, criterion, callId)
        }
    }

    private fun artifactEvidence(
        goalId: String,
        criterion: Criterion,
        callId: String,
    ): CriterionEvidence? {
        val rule = requireNotNull(criterion.binding)
        val snapshot = reader.read(goalId, callId)
        return listOf(false, true).firstNotNullOfOrNull { written ->
            checked {
                val body = requireNotNull(snapshot.content)
                val bytes =
                    if (written && snapshot.call.name == "edit") {
                        val ref = ArtifactRef(artifacts.capture(goalId, callId, true).id)
                        artifacts.read(goalId, callId, ref).second
                    } else if (written) {
                        WrittenArtifactContent.decode(
                            snapshot.call.name,
                            snapshot.call.version,
                            snapshot.call.argsJson,
                            body,
                        )
                    } else {
                        body.toByteArray(Charsets.UTF_8)
                    }
                val hash = Sha256(FileContentStore.sha256Hex(bytes))
                require(ArtifactCriterionCheck.verify(rule, bytes, hash) == ArtifactCriterionResult.MATCHED)
                val reference = ArtifactRef(artifacts.capture(goalId, callId, written).id)
                verifier.automatic(goalId, criterion, callId, reference)
            }
        }
    }

    private fun archiveEvidence(
        goalId: String,
        criterion: Criterion,
        callId: String,
    ): CriterionEvidence? =
        archives.paths(goalId, callId).firstNotNullOfOrNull { path ->
            checked {
                val reference = ArtifactRef(archives.capture(goalId, callId, path).id)
                verifier.automatic(goalId, criterion, callId, reference)
            }
        }

    /** Expected missing/stale/invalid evidence yields no proof; database and programming failures still propagate. */
    private fun checked(
        onRejected: (String) -> Unit = {},
        block: () -> CriterionEvidence?,
    ): CriterionEvidence? =
        try {
            block()
        } catch (failure: IllegalArgumentException) {
            onRejected((failure as? GoalEvidenceRejected)?.reason?.name ?: "EVIDENCE_INVALID")
            null
        } catch (_: IOException) {
            onRejected(GoalEvidenceFailure.READ_UNAVAILABLE.name)
            null
        }
}

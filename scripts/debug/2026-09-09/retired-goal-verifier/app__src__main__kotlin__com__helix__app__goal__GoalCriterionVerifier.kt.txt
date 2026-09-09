package com.helix.app.goal

import com.helix.core.agent.ArtifactCriterionCheck
import com.helix.core.agent.ArtifactCriterionResult
import com.helix.core.agent.Criterion
import com.helix.core.agent.CriterionEvidence
import com.helix.core.model.ArtifactRef
import com.helix.core.model.Clock
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.model.CriterionVerificationRecord
import com.helix.core.model.Sha256
import com.helix.core.model.ToolCallId

/** The exact binding and source snapshot actually shown before an explicit user review action. */
internal data class GoalEvidenceReview(
    val toolCallId: String,
    val bindingHash: Sha256,
    val sourceHash: Sha256,
    val artifactRef: ArtifactRef? = null,
)

/** Produces receipts from host-checked facts, never from a model-supplied verifier or success flag. */
internal class GoalCriterionVerifier(
    private val reader: GoalToolEvidenceReader,
    private val artifacts: GoalToolArtifactStore,
    private val clock: Clock,
) {
    fun automatic(
        goalId: String,
        criterion: Criterion,
        toolCallId: String,
        artifactRef: ArtifactRef? = null,
    ): CriterionEvidence? {
        val rule = criterion.binding ?: return null
        val snapshot = reader.read(goalId, toolCallId)
        val contentHash =
            when (rule.method) {
                CriterionVerificationMethod.LOCAL_TOOL_SUCCESS -> {
                    require(artifactRef == null) { "tool success rule does not verify an artifact reference" }
                    if (snapshot.call.name == rule.argument) snapshot.hash else null
                }

                CriterionVerificationMethod.ARTIFACT_SHA256, CriterionVerificationMethod.ARTIFACT_UTF8_CONTAINS -> {
                    artifactRef?.let { reference ->
                        val (artifact, bytes) = artifacts.read(goalId, toolCallId, reference)
                        val hash = Sha256(artifact.sha256)
                        hash.takeIf {
                            ArtifactCriterionCheck.verify(rule, bytes, hash) == ArtifactCriterionResult.MATCHED
                        }
                    }
                }

                CriterionVerificationMethod.MANUAL_REVIEW -> {
                    null
                }
            }
        return contentHash?.let { evidence(criterion, snapshot, it, artifactRef) }
    }

    /** Called only by the explicit review intent; a changed display snapshot requires a new review. */
    fun review(
        goalId: String,
        criterion: Criterion,
        selection: GoalEvidenceReview,
    ): CriterionEvidence {
        val rule = requireNotNull(criterion.binding)
        require(rule.method == CriterionVerificationMethod.MANUAL_REVIEW)
        requireGoalEvidence(
            rule.hash(criterion.id, criterion.description) == selection.bindingHash,
            GoalEvidenceFailure.BINDING_CHANGED,
        )
        val snapshot = reader.read(goalId, selection.toolCallId)
        requireGoalEvidence(snapshot.hash == selection.sourceHash, GoalEvidenceFailure.CONTENT_CHANGED)
        val hash =
            selection.artifactRef?.let {
                Sha256(artifacts.read(goalId, selection.toolCallId, it).first.sha256)
            } ?: snapshot.hash
        return evidence(criterion, snapshot, hash, selection.artifactRef)
    }

    private fun evidence(
        criterion: Criterion,
        snapshot: GoalToolEvidenceSnapshot,
        hash: Sha256,
        artifactRef: ArtifactRef?,
    ): CriterionEvidence {
        val rule = requireNotNull(criterion.binding)
        return CriterionEvidence(
            CriterionEvidence.HOST_VERIFIER,
            artifactRef,
            ToolCallId(snapshot.call.id),
            CriterionVerificationRecord(
                rule.method,
                rule.hash(criterion.id, criterion.description),
                snapshot.source,
                hash,
                clock.now().toEpochMilli(),
                sourceHash = snapshot.hash,
            ),
        )
    }
}

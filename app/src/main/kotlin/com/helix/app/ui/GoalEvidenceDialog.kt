package com.helix.app.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.goal.GoalEvidenceCandidate
import com.helix.app.goal.GoalEvidenceFailure
import com.helix.app.goal.GoalEvidencePreview
import com.helix.app.goal.GoalEvidenceRejected
import com.helix.core.model.CriterionVerificationMethod
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
@Suppress("FunctionName", "LongMethod", "SwallowedException")
internal fun GoalEvidenceDialog(
    service: ChatService,
    goalId: String,
    criterionId: String,
    canReview: Boolean,
    onDismiss: () -> Unit,
) {
    var candidates by remember { mutableStateOf<List<GoalEvidenceCandidate>>(emptyList()) }
    var preview by remember { mutableStateOf<GoalEvidencePreview?>(null) }
    var failure by remember { mutableStateOf<GoalEvidenceFailure?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(goalId) {
        try {
            candidates = service.goalEvidenceCandidates(goalId)
        } catch (
            rejected: IllegalArgumentException,
        ) {
            failure = evidenceFailure(rejected)
        } finally {
            loading = false
        }
    }
    val shown = preview
    if (shown != null) {
        GoalEvidenceReviewDialog(shown, canReview, { preview = null }) {
            service.reviewGoalEvidence(goalId, criterionId, shown.selection())
            onDismiss()
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.goal_evidence_view)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    if (failure != null) item { Text(stringResource(goalEvidenceFailureLabel(failure))) }
                    if (!loading && candidates.isEmpty()) item { Text(stringResource(R.string.goal_evidence_empty)) }
                    items(candidates, key = { "${it.callId}-${it.written}-${it.archivePath}" }) { candidate ->
                        TextButton(
                            enabled = !loading,
                            modifier = Modifier.testTag(candidateTag(candidate)),
                            onClick = {
                                scope.launch {
                                    loading = true
                                    failure = null
                                    try {
                                        preview =
                                            service.goalEvidencePreview(
                                                goalId,
                                                criterionId,
                                                candidate.callId,
                                                candidate.written,
                                                candidate.archivePath,
                                            )
                                    } catch (
                                        rejected: IllegalArgumentException,
                                    ) {
                                        failure = evidenceFailure(rejected)
                                    } catch (_: IOException) {
                                        failure = GoalEvidenceFailure.READ_UNAVAILABLE
                                    } finally {
                                        loading = false
                                    }
                                }
                            },
                        ) { EvidenceCandidateLabel(candidate) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.goal_close)) } },
        )
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "SwallowedException")
internal fun GoalEvidenceReviewDialog(
    preview: GoalEvidencePreview,
    canReview: Boolean,
    onDismiss: () -> Unit,
    onReview: suspend () -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<GoalEvidenceFailure?>(null) }
    val scope = rememberCoroutineScope()
    val source = preview.snapshot
    val chunks = remember(source.hash, preview.artifactRef) { preview.body.chunked(4096) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.goal_evidence_view)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp).testTag("evidence-content")) {
                item { Text(preview.criterion.description) }
                item {
                    Text(
                        stringResource(
                            if (preview.artifactRef != null) {
                                R.string.goal_evidence_written
                            } else {
                                R.string.goal_evidence_result
                            },
                        ),
                    )
                }
                if (preview.artifactRef != null) item { Text(stringResource(R.string.goal_evidence_snapshot_hint)) }
                item {
                    Text(
                        stringResource(
                            R.string.goal_evidence_source,
                            source.call.name,
                            source.source.runId.value,
                            source.source.turnId.value,
                            source.call.id,
                        ),
                    )
                }
                item { Text(source.result.summary) }
                items(chunks) { Text(it) }
                item { Text(stringResource(R.string.goal_evidence_review_hint)) }
                if (failure != null) {
                    item {
                        Text(
                            stringResource(goalEvidenceFailureLabel(failure)),
                            Modifier.testTag("evidence-error"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (preview.criterion.binding?.method == CriterionVerificationMethod.MANUAL_REVIEW) {
                TextButton(enabled = canReview && !saving, modifier = Modifier.testTag("evidence-confirm"), onClick = {
                    scope.launch {
                        saving = true
                        failure = null
                        try {
                            onReview()
                        } catch (
                            rejected: IllegalArgumentException,
                        ) {
                            failure = evidenceFailure(rejected)
                        } catch (_: IOException) {
                            failure = GoalEvidenceFailure.READ_UNAVAILABLE
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(stringResource(R.string.goal_evidence_confirm)) }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !saving,
                onClick = onDismiss,
            ) { Text(stringResource(R.string.goal_close)) }
        },
    )
}

private fun candidateTag(candidate: GoalEvidenceCandidate): String =
    "evidence-source-${candidate.callId}${if (candidate.written) "-written" else ""}${candidate.archivePath.orEmpty()}"

@Composable
@Suppress("FunctionName")
private fun EvidenceCandidateLabel(candidate: GoalEvidenceCandidate) {
    val label =
        if (candidate.written ||
            candidate.archivePath != null
        ) {
            R.string.goal_evidence_written
        } else {
            R.string.goal_evidence_result
        }
    Text(
        stringResource(label) + "\n${candidate.tool}\n${candidate.callId}" +
            candidate.archivePath?.let { "\n$it" }.orEmpty(),
    )
}

private fun evidenceFailure(failure: IllegalArgumentException): GoalEvidenceFailure =
    (failure as? GoalEvidenceRejected)?.reason ?: GoalEvidenceFailure.UNKNOWN

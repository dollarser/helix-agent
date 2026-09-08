package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.R
import com.helix.app.goal.GoalEvidencePreview
import com.helix.app.goal.goalEvidenceReader
import com.helix.app.goal.seedGoalToolEvidence
import com.helix.app.goal.withGoalEvidenceStorage
import com.helix.core.agent.Criterion
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import com.helix.core.storage.content.ContentRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoalEvidenceFailureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closingPreviewDoesNotConfirmEvidence() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            val source = goalEvidenceReader(storage).read("goal", "call")
            var reviewed = false
            var dismissed = false
            var closeLabel = ""
            compose.setContent {
                closeLabel = stringResource(R.string.goal_close)
                MaterialTheme {
                    GoalEvidenceReviewDialog(preview(source), true, { dismissed = true }) { reviewed = true }
                }
            }
            compose.onNodeWithText(closeLabel).performClick()
            compose.runOnIdle {
                assertTrue(dismissed)
                assertFalse(reviewed)
            }
        }

    @Test fun leavingReviewCancelsSuspendedSaveWithoutSuccessCallback() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            val source = goalEvidenceReader(storage).read("goal", "call")
            val visible = mutableStateOf(true)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            var completed = false
            compose.setContent {
                MaterialTheme {
                    if (visible.value) {
                        GoalEvidenceReviewDialog(preview(source), true, { visible.value = false }) {
                            started.complete(Unit)
                            try {
                                release.await()
                                completed = true
                            } finally {
                                cancelled.complete(Unit)
                            }
                        }
                    }
                }
            }
            compose.onNodeWithTag("evidence-confirm").performClick()
            compose.waitUntil(10000) { started.isCompleted }
            compose.runOnIdle { visible.value = false }
            compose.waitUntil(10000) { cancelled.isCompleted }
            release.complete(Unit)
            compose.runOnIdle { assertFalse(completed) }
        }

    private fun preview(source: com.helix.app.goal.GoalToolEvidenceSnapshot) =
        GoalEvidencePreview(
            Criterion(
                "c1",
                "Review actual result",
                binding = CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, ""),
            ),
            source,
        )

    @Test fun deletingDisplayedContentShowsSpecificFailureAndDoesNotDismiss() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            val reader = goalEvidenceReader(storage)
            val source = reader.read("goal", "call")
            val criterion =
                Criterion(
                    "c1",
                    "Review actual result",
                    binding =
                        CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, ""),
                )
            var dismissed = false
            var changedLabel = ""
            compose.setContent {
                changedLabel = stringResource(R.string.goal_evidence_changed)
                MaterialTheme {
                    GoalEvidenceReviewDialog(GoalEvidencePreview(criterion, source), true, { dismissed = true }) {
                        withContext(Dispatchers.IO) { reader.read("goal", "call") }
                    }
                }
            }
            storage.contentStore.delete(ContentRef.parse(requireNotNull(source.result.contentRef)))
            compose.onNodeWithTag("evidence-confirm").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("evidence-content").performScrollToNode(hasText(changedLabel))
            compose.onNodeWithText(changedLabel).assertIsDisplayed()
            compose.runOnIdle { assertFalse(dismissed) }
        }
}

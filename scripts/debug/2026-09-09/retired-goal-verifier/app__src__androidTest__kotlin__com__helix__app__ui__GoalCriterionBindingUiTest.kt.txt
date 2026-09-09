package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.agent.Criterion
import com.helix.core.model.CriterionVerificationBinding
import com.helix.core.model.CriterionVerificationMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoalCriterionBindingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun literalRequiresParameterAndExplicitSave() {
        var saved: CriterionVerificationBinding? = null
        compose.setContent {
            MaterialTheme {
                GoalCriterionBindingEditor(Criterion("c1", "Expected output"), {}) { _, binding -> saved = binding }
            }
        }
        compose.onNodeWithTag("criterion-method-ARTIFACT_UTF8_CONTAINS").performScrollTo().performClick()
        compose.onNodeWithTag("criterion-save").assertIsNotEnabled()
        compose.onNodeWithTag("criterion-argument").performScrollTo().performTextInput("完成")
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("criterion-save").performClick()
        compose.runOnIdle {
            assertEquals(CriterionVerificationBinding(CriterionVerificationMethod.ARTIFACT_UTF8_CONTAINS, "完成"), saved)
        }
    }

    @Test fun invalidHashCannotSaveAndManualReviewNeedsNoParameter() {
        var saved: CriterionVerificationBinding? = null
        compose.setContent {
            MaterialTheme {
                GoalCriterionBindingEditor(Criterion("c1", "Expected output"), {}) { _, binding -> saved = binding }
            }
        }
        compose.onNodeWithTag("criterion-method-ARTIFACT_SHA256").performScrollTo().performClick()
        compose.onNodeWithTag("criterion-argument").performScrollTo().performTextInput("invalid")
        compose.onNodeWithTag("criterion-save").assertIsNotEnabled()
        compose.onNodeWithTag("criterion-method-MANUAL_REVIEW").performScrollTo().performClick()
        compose.onNodeWithTag("criterion-save").performClick()
        compose.runOnIdle {
            assertEquals(CriterionVerificationBinding(CriterionVerificationMethod.MANUAL_REVIEW, ""), saved)
        }
    }
}

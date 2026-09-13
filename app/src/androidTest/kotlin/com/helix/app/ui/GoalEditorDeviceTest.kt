package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.GoalBudgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoalEditorDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun budgetDefaultsSaveOnlyAfterExplicitClick() {
        var saved: GoalBudgets? = null
        compose.setContent {
            MaterialTheme {
                GoalEditor(com.helix.app.runcontrol.GoalBudgetDefaults.VALUE, {}, { budgets ->
                    saved = budgets
                })
            }
        }
        compose.onNodeWithTag("goal-save").assertIsEnabled()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("goal-save").performClick()
        compose.runOnIdle { assertEquals(com.helix.app.runcontrol.GoalBudgetDefaults.VALUE, saved) }
    }

    @Test
    fun cancelledSavePropagatesCancellationWithoutShowingValidationError() {
        lateinit var job: Job
        renderEditor {
            job = requireNotNull(currentCoroutineContext()[Job])
            throw CancellationException("Fixture cancellation")
        }
        compose.onNodeWithTag("goal-save").performClick()
        compose.runOnIdle { assertTrue("Save cancellation must propagate", job.isCancelled) }
        compose.onNodeWithTag("goal-save-failed").assertDoesNotExist()
    }

    @Test
    fun successfulRetryClearsThePreviousSaveError() {
        var fail = true
        var saved = false
        renderEditor {
            if (fail) error("Fixture state race")
            saved = true
        }
        compose.onNodeWithTag("goal-save").performClick()
        compose.onNodeWithTag("goal-save-failed").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { fail = false }
        compose.onNodeWithTag("goal-save").performClick()
        compose.runOnIdle { assertTrue(saved) }
        compose.onNodeWithTag("goal-save-failed").assertDoesNotExist()
    }

    private fun renderEditor(save: suspend () -> Unit) {
        compose.setContent {
            MaterialTheme {
                GoalEditor(com.helix.app.runcontrol.GoalBudgetDefaults.VALUE, {}, { save() })
            }
        }
    }
}

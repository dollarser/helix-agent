package com.helix.app

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.maintenance.PreviewResetRequest
import com.helix.app.maintenance.PreviewResetSection
import com.helix.app.maintenance.PreviewResetStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real Compose flow without replacing the daily emulator's database or granting system access. */
class StartupExperienceDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noticeAppearsDuringPreflightAndReadinessDoesNotInterruptReview() {
        val state = mutableStateOf<AppStartupState>(AppStartupState.Loading)
        val step = mutableStateOf(StartupOnboarding.NOTICE)
        compose.setContent {
            StartupExperience(
                state.value,
                step.value,
                true,
                { step.value = StartupOnboarding.PERMISSIONS },
                { step.value = StartupOnboarding.COMPLETE },
                { Text("fixture shell") },
                {},
                {},
                {},
            )
        }
        compose.onNodeWithTag("first-launch-notice").assertIsDisplayed()
        compose.runOnIdle { state.value = AppStartupState.Ready }
        compose.onNodeWithTag("first-launch-notice").assertIsDisplayed()
        compose.onNodeWithTag("first-launch-continue").performScrollTo().performClick()
        compose.onNodeWithTag("startup-permissions").assertIsDisplayed()
        compose.onNodeWithText("fixture shell").assertDoesNotExist()
        compose.onNodeWithTag("startup-permissions-continue").performClick()
        compose.onNodeWithText("fixture shell").assertIsDisplayed()
    }

    @Test fun permissionReviewWaitsForPreflightAndResetConfirmationRemainsSeparate() {
        var reset = 0
        val request = PreviewResetRequest(listOf(PreviewResetStep(PreviewResetSection.DATABASE, {}, { reset++ })))
        val state = mutableStateOf<AppStartupState>(AppStartupState.Loading)
        compose.setContent {
            StartupExperience(
                state.value,
                StartupOnboarding.PERMISSIONS,
                true,
                {},
                {},
                { Text("fixture shell") },
                { it.confirm() },
                {},
                {},
            )
        }
        compose.onNodeWithTag("startup-permissions").assertDoesNotExist()
        compose.runOnIdle { state.value = AppStartupState.ResetRequired(request) }
        compose.onNodeWithTag("preview-reset-confirm").assertIsDisplayed()
        assertEquals(0, reset)
        compose.onNodeWithTag("preview-reset-confirm").performScrollTo().performClick()
        assertEquals(1, reset)
        compose.runOnIdle { state.value = AppStartupState.Initializing }
        compose.onNodeWithTag("startup-permissions").assertIsDisplayed()
        compose.runOnIdle { state.value = AppStartupState.Failed }
        compose.onNodeWithTag("startup-permissions").assertDoesNotExist()
        compose.onNodeWithTag("app-startup").assertIsDisplayed()
    }
}

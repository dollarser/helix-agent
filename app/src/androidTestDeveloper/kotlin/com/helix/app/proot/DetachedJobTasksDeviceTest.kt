package com.helix.app.proot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.SessionPermissionMode
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class DetachedJobTasksDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Test fun tasksCollectsOriginalDispatcherJobWithoutNewTurnOrToolCall() {
        compose.resetDeterministicUiState()
        DetachedJobJourneyFixture(app).use { f ->
            f.start("printf tasks-result > result.txt")
            assertEquals(ProotJobState.SUCCEEDED, f.awaitTerminal().state)
            openTasks(f)
            assertEquals(BackgroundJobActionOutcome.TERMINAL_PENDING, click(f, BackgroundJobAction.QUERY))
            assertEquals(BackgroundJobActionOutcome.SETTLED, click(f, BackgroundJobAction.COLLECT))
            assertEquals("tasks-result", f.output.readText())
            assertFalse(
                f.container.chatService.backgroundJobs.value
                    .single { it.callId == f.id }
                    .settlementPending,
            )
            assertEquals(
                1,
                f.storage.turns
                    .listBySession(f.id)
                    .size,
            )
            assertEquals(
                1,
                f.storage.toolCalls
                    .listByTurn(f.id)
                    .size,
            )
            assertTrue(
                f.storage.auditEvents
                    .listByCorrelation(f.id)
                    .any { it.type == "proot.job_user_action" },
            )
        }
    }

    @Test fun tasksStopRequestsCancellationAndStillRequiresCollection() {
        compose.resetDeterministicUiState()
        DetachedJobJourneyFixture(app).use { f ->
            f.start("sleep 50; printf must-not-import > result.txt")
            openTasks(f)
            val result = click(f, BackgroundJobAction.CANCEL)
            assertTrue(
                result in setOf(BackgroundJobActionOutcome.STOP_REQUESTED, BackgroundJobActionOutcome.TERMINAL_PENDING),
            )
            assertEquals(ProotJobState.CANCELLED, f.awaitTerminal().state)
            assertEquals(BackgroundJobActionOutcome.TERMINAL_PENDING, click(f, BackgroundJobAction.QUERY))
            assertTrue(
                f.container.chatService.backgroundJobs.value
                    .single { it.callId == f.id }
                    .settlementPending,
            )
            assertEquals(BackgroundJobActionOutcome.SETTLED, click(f, BackgroundJobAction.COLLECT))
            assertFalse(f.output.exists())
        }
    }

    @Test fun tightenedPermissionKeepsOriginalResultPendingUntilUserRestoresIt() {
        compose.resetDeterministicUiState()
        DetachedJobJourneyFixture(app).use { f ->
            f.start("printf retained-result > result.txt")
            f.awaitTerminal()
            openTasks(f)
            f.denyOutput()
            assertEquals(BackgroundJobActionOutcome.FAILED, click(f, BackgroundJobAction.COLLECT))
            assertFalse(f.output.exists())
            assertNotNull(ExecutionOwnershipStore(File(app.filesDir, "execution-admission/owner")).read())
            f.permission(SessionPermissionMode.FULL_ACCESS)
            assertEquals(BackgroundJobActionOutcome.SETTLED, click(f, BackgroundJobAction.COLLECT))
            assertEquals("retained-result", f.output.readText())
            assertEquals(
                1,
                f.storage.toolCalls
                    .listByTurn(f.id)
                    .size,
            )
        }
    }

    private fun openTasks(f: DetachedJobJourneyFixture) {
        compose.navigateTo("tasks")
        compose.waitUntil(20_000) {
            f.container.chatService.backgroundJobs.value
                .any { it.callId == f.id }
        }
    }

    private fun click(
        f: DetachedJobJourneyFixture,
        action: BackgroundJobAction,
    ): BackgroundJobActionOutcome {
        val tag = "tasks-job-${action.name.lowercase()}-${f.id}"
        compose.onNodeWithTag("screen-tasks").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        compose.waitUntil(30_000) {
            val state = f.container.chatService.backgroundJobAction.value
            state?.callId == f.id && state.action == action && !state.busy && state.outcome != null
        }
        return requireNotNull(
            f.container.chatService.backgroundJobAction.value
                ?.outcome,
        )
    }
}

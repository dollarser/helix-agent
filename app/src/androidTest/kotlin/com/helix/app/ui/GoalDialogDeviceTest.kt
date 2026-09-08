package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual dialog -> ChatService -> Room, with no Provider so no network request can execute. */
@RunWith(AndroidJUnit4::class)
class GoalDialogDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun dismissalPreservesDraftAndCreationDoesNotRunUntilExplicitContinue() {
        val container = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val storage = container.storage
        val service = container.chatService
        val sessionId = "goal-ui-${UUID.randomUUID()}"
        val objective = "Goal UI fixture $sessionId"
        storage.sessions.create(sessionId, "Goal UI fixture", null, null, System.currentTimeMillis())
        service.openSession(sessionId)
        var draft by mutableStateOf(objective)
        var open by mutableStateOf(true)
        var continued = 0
        var goalId: String? = null
        try {
            compose.waitUntil(10_000) { service.screen.value.openSessionId == sessionId }
            compose.setContent {
                MaterialTheme {
                    if (open) {
                        GoalDialog(service, draft, { open = false }, {
                            draft = ""
                            continued++
                        })
                    }
                }
            }
            compose.onNodeWithTag("goal-close").performClick()
            compose.runOnIdle {
                assertEquals(objective, draft)
                assertEquals(0, continued)
                open = true
            }
            compose.onNodeWithTag("goal-create").performClick()
            compose.onNodeWithTag("goal-criteria").performTextInput("The output is verified")
            compose.onNodeWithTag("goal-save").performClick()
            compose.waitUntil(10_000) {
                goalId =
                    storage.goals
                        .list()
                        .singleOrNull { it.objective == objective }
                        ?.id
                goalId != null
            }
            val id = requireNotNull(goalId)
            assertNotStarted(storage, id, sessionId)
            compose.onNodeWithTag("goal-continue-$id").performScrollTo().performClick()
            compose.waitUntil(10_000) { service.screen.value.blockedReason != null }
            compose.runOnIdle {
                assertEquals("", draft)
                assertEquals(1, continued)
            }
            assertNotNull(service.screen.value.blockedReason)
            assertNotStarted(storage, id, sessionId)
        } finally {
            service.closeSession()
            service.dismissBlocked()
            goalId?.let(storage.goals::delete)
            storage.sessions.archive(sessionId, System.currentTimeMillis())
        }
    }

    private fun assertNotStarted(
        storage: com.helix.core.storage.HelixStorage,
        goalId: String,
        sessionId: String,
    ) {
        assertEquals("READY", storage.goals.resolve(goalId).state)
        assertEquals(0, storage.turns.listBySession(sessionId).size)
        assertEquals(0, storage.goalRuns.listByGoal(goalId).size)
    }
}

package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
    fun dismissalAndSettingsPreserveDraftAndContinueStillUsesAdmission() {
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
        var settingsOpened = false
        var goalId: String? =
            kotlinx.coroutines.runBlocking {
                service.createGoal(objective, emptyList(), com.helix.app.runcontrol.GoalBudgetDefaults.VALUE)
            }
        try {
            compose.waitUntil(10_000) { service.screen.value.openSessionId == sessionId }
            compose.setContent {
                MaterialTheme {
                    if (open) {
                        GoalDialog(service, draft, { open = false }, {
                            draft = ""
                            continued++
                        }, onSettings = { settingsOpened = true })
                    }
                }
            }
            compose.onNodeWithTag("goal-close").performClick()
            compose.runOnIdle {
                assertEquals(objective, draft)
                assertEquals(0, continued)
                open = true
            }
            compose.onNodeWithTag("goal-settings").performClick()
            compose.runOnIdle {
                assertEquals(true, settingsOpened)
                assertEquals(objective, draft)
                open = true
            }
            val id = requireNotNull(goalId)
            assertNotStarted(storage, id, sessionId)
            editObjective(service, storage, id)
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

    private fun editObjective(
        service: com.helix.app.chat.ChatService,
        storage: com.helix.core.storage.HelixStorage,
        id: String,
    ) {
        compose.onNodeWithTag("goal-objective-edit-$id").performScrollTo().performClick()
        compose.onNodeWithTag("goal-objective-$id").performScrollTo().performTextReplacement("Edited objective")
        compose.onNodeWithTag("goal-objective-save-$id").performScrollTo().performClick()
        compose.waitUntil(10_000) { storage.goals.resolve(id).objective == "Edited objective" }
        assertEquals(1L, storage.goalControls.find(id)?.revision)
        assertEquals(false, kotlinx.coroutines.runBlocking { service.editGoalObjective(id, 0, "Stale") })
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

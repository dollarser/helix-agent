package com.helix.app.chat

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.feature.conversation.application.ChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Main-thread facade calls with real Room; no model requests, grants or execution. */
class GoalQueryThreadingDeviceTest {
    @Test fun goalSummariesResolvePersistedSessionOffMain() =
        withPersistedSession { service ->
            assertTrue(service.goalSummaries().isEmpty())
            assertTrue(service.goalSummaries().isEmpty())
        }

    @Test fun blockerRecheckResolvesPersistedSessionOffMain() =
        withPersistedSession { service ->
            val goal =
                service.createGoal(
                    "Owned threading fixture",
                    emptyList(),
                    com.helix.core.model
                        .GoalBudgets(1),
                )
            try {
                assertFalse(service.recheckGoalBlocker(goal))
            } finally {
                withContext(Dispatchers.IO) {
                    ApplicationProvider
                        .getApplicationContext<HelixApplication>()
                        .appContainer.privacyDeletionService
                        .deleteGoal(goal)
                }
            }
        }

    private fun withPersistedSession(checkQuery: suspend (ChatService) -> Unit): Unit =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            check(app.packageName.endsWith(".asrvalidation"))
            val container = app.awaitAppContainer()
            val service = container.chatService
            val previous = service.screen.value
            val session = "goal-query-threading-${UUID.randomUUID()}"
            container.storage.sessions.create(session, "Goal query threading", null, null, System.currentTimeMillis())
            try {
                withContext(Dispatchers.Main) { service.openSession(session) }
                withTimeout(10_000) {
                    while (service.screen.value.openSessionId != session) kotlinx.coroutines.delay(10)
                }
                withContext(Dispatchers.Main) {
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    // Keep the real Room guard enabled; a permissive test database would hide the bug.
                    assertThrows(IllegalStateException::class.java) { container.storage.sessions.find(session) }
                    checkQuery(service)
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                }
                assertTrue(
                    container.storage.turns
                        .listBySession(session)
                        .isEmpty(),
                )
            } finally {
                withContext(Dispatchers.Main) {
                    if (previous.isDraft) {
                        service.newSessionDraft()
                    } else {
                        previous.openSessionId?.let(service::openSession) ?: service.closeSession()
                    }
                }
                container.storage.sessions.archive(session, System.currentTimeMillis())
            }
        }
}

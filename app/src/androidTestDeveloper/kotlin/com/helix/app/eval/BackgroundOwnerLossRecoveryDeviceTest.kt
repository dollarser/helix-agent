package com.helix.app.eval

import android.hardware.display.DisplayManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.TurnState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inspect restart recovery after the host and original helper were both terminated externally. */
class BackgroundOwnerLossRecoveryDeviceTest {
    @Test fun restartDoesNotReplayAndCleansOnlyTheOwnedInterruptedFixture() {
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val requested = args.getString("helixOwnerLossSession")
            org.junit.Assume.assumeTrue("Requires an externally terminated owned fixture", requested != null)
            val session = requireNotNull(requested)
            require(session.matches(Regex("background-fixture-[a-f0-9-]{36}")))
            val count = requireNotNull(args.getString("helixOwnerLossCalls")).toInt()
            val display = requireNotNull(args.getString("helixOwnerLossDisplay")).toInt()
            val dispatches = requireNotNull(args.getString("helixOwnerLossDispatches")).toInt()
            val before = requireNotNull(args.getString("helixOwnerLossBeforeEpoch")).toLong()
            require(count >= 0 && dispatches > 0 && display > 0 && before > 0)
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.appContainer
            app.runtimeRecoveryReady.await()
            val turn =
                c.storage.turns
                    .listBySession(session)
                    .single()
            assertTrue(
                "Old test turn must settle after restart: ${turn.state}",
                TurnState.valueOf(turn.state).isTerminal,
            )
            assertEquals(
                count,
                c.storage.toolCalls
                    .listByTurn(turn.id)
                    .size,
            )
            assertEquals(
                0,
                c.storage.modelCalls
                    .listByTurn(turn.id)
                    .size,
            )
            assertNull(app.getSystemService(DisplayManager::class.java).getDisplay(display))
            verifyOriginalDispatches(c, session, dispatches, before)
            assertTrue(
                c.chatService.displayExecutions.value
                    .none { it.sessionId == session },
            )
            println(
                "OWNER_LOSS_RECOVERY session=$session;state=${turn.state};" +
                    "calls=$count;dispatches=$dispatches;models=0;displayAbsent=$display",
            )
            c.storage.deleteSessionPermanently(session)
            assertNull(c.storage.sessions.find(session))
        }
    }

    private fun verifyOriginalDispatches(
        c: com.helix.app.AppContainer,
        session: String,
        count: Int,
        before: Long,
    ) {
        val events =
            (1..count).flatMap { ordinal ->
                c.storage.auditEvents
                    .listByCorrelation("$session-$ordinal")
                    .filter { it.type == "tool_dispatch" }
            }
        assertEquals("Exactly one audit per original direct dispatch", count, events.size)
        assertEquals(count, events.map { it.correlationId }.distinct().size)
        for (event in events) {
            val finished = org.json.JSONObject(event.redactedPayload).getLong("finishedAt")
            assertTrue("No dispatch may finish after the captured pre-death boundary", finished <= before)
        }
        assertTrue(
            c.storage.auditEvents
                .listByCorrelation("$session-${count + 1}")
                .isEmpty(),
        )
    }
}

package com.helix.app.proot

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.HelixApplication
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.policy.DataOrigin
import com.helix.tools.framework.ExecutionOwnership
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises the real production dispatcher and Android filesystem, without starting a Runtime. */
@RunWith(AndroidJUnit4::class)
class ExecutionOwnershipDeviceTest {
    @Test fun retainedOwnerBlocksProductionDispatchUntilMatchingSettlement() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val session = "ownership-${UUID.randomUUID()}"
        val turn = "$session-turn"
        val now = System.currentTimeMillis()
        container.storage.sessions.create(session, "ownership fixture", null, null, now)
        container.storage.turns.start(turn, session, now)
        val file = File(app.filesDir, "execution-admission/owner")
        val store = ExecutionOwnershipStore(file)
        assertNull(store.read())
        val owner = ExecutionOwnership.Owner("execution-$session", "fixture-generation")
        assertTrue(store.compareAndSet(null, owner))
        try {
            val request =
                ToolDispatchRequest(
                    toolCallId = "$session-blocked",
                    turnId = turn,
                    sessionId = session,
                    toolName = ToolName("time.now"),
                    toolVersion = ToolVersion(1),
                    args = buildJsonObject {},
                    mode = AgentMode.ACT,
                    profile = SafetyProfile.STANDARD,
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    dataOrigin = DataOrigin.WORKSPACE,
                    scope = null,
                    uiToken = "chat:$turn",
                )
            val blocked = container.toolPipeline.dispatcher.dispatch(request)
            assertTrue(blocked is ToolDispatchOutcome.ExecutionFailed)
            assertTrue((blocked as ToolDispatchOutcome.ExecutionFailed).sideEffectFree)
            val reopened = ExecutionOwnershipStore(file)
            assertFalse(reopened.compareAndSet(owner.copy(generation = "stale"), null))
            assertEquals(owner, reopened.read())
            assertTrue(reopened.compareAndSet(owner, null))
            val success = container.toolPipeline.dispatcher.dispatch(request.copy(toolCallId = "$session-released"))
            assertTrue(success is ToolDispatchOutcome.Succeeded)
            val blockedAudit = container.storage.auditEvents.listByCorrelation("$session-blocked")
            val releasedAudit = container.storage.auditEvents.listByCorrelation("$session-released")
            assertEquals(1, blockedAudit.count { it.type == "tool_dispatch" })
            assertEquals(1, releasedAudit.count { it.type == "tool_dispatch" })
        } finally {
            // Never remove a foreign owner's record, even on test failure.
            store.compareAndSet(owner, null)
        }
    }
}

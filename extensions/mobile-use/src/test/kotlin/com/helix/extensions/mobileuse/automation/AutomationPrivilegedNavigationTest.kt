package com.helix.extensions.mobileuse.automation

import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.config.MobileUseTestConfiguration
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationPrivilegedNavigationTest {
    private val records = mutableMapOf<String, List<String>>()
    private val store = MobileUseTestConfiguration({ records[it].orEmpty() }, { k, v -> records[k] = v })
    private val grant = store.authorize("chat", setOf("com.example.app"), false)
    private val call =
        ExecutableToolCall(
            "call",
            "ui.launch",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(60),
            NoCancellation,
            "chat",
            "turn",
            authorizationScopeRef = grant.scope.toScopeRef(),
        )
    private var revoked = false
    private var invalidations = 0
    private val host = AutomationPrivilegedNavigation({ grant.takeUnless { revoked } }, { invalidations++ })
    private val backend = Backend()

    @Test fun launchChecksScopeBeforeDispatchAndDoesNotReplayAnUnknownResult() {
        assertEquals(
            AutomationActionStatus.TARGET_NOT_ALLOWLISTED,
            host.execute(call, backend, launchPackage = "com.other.app").status,
        )
        assertTrue(backend.calls.isEmpty())
        assertEquals(
            AutomationActionStatus.ACTION_OUTCOME_UNKNOWN,
            host.execute(call, backend, launchPackage = "com.example.app").status,
        )
        assertEquals(listOf(AutomationDeviceOperation.LAUNCH), backend.calls)
        assertEquals(2, invalidations)
    }

    @Test fun revocationDuringReadPreventsNavigationAndReleasesPhysicalCapacity() {
        backend.after = { revoked = true }
        assertEquals(
            AutomationActionStatus.ACTION_NOT_DISPATCHED,
            host.execute(call, backend, globalAction = AutomationGlobalAction.BACK).status,
        )
        assertEquals(listOf(AutomationDeviceOperation.OBSERVE), backend.calls)
        revoked = false
        backend.after = {}
        host.execute(call, backend, globalAction = AutomationGlobalAction.HOME)
        assertEquals(AutomationDeviceOperation.GLOBAL_ACTION, backend.calls.last())
    }

    @Test fun restrictedScopeCannotOpenSystemPanelsAndBackendSelectionPrecedesEffects() {
        assertEquals(
            AutomationActionStatus.TARGET_NOT_ALLOWLISTED,
            host.execute(call, backend, globalAction = AutomationGlobalAction.NOTIFICATIONS).status,
        )
        assertTrue(backend.calls.isEmpty())
        val root = Backend()
        val shizuku = Backend()
        val selection = AutomationBackendSelection(root, shizuku)
        assertEquals(root, selection.select(AutomationDeviceOperation.LAUNCH))
        root.ready = false
        assertEquals(shizuku, selection.select(AutomationDeviceOperation.GLOBAL_ACTION))
        shizuku.ready = false
        assertNull(selection.select(AutomationDeviceOperation.LAUNCH))
    }

    private class Backend : AutomationPrivilegedBackend {
        var ready = true
        var after: () -> Unit = {}
        val calls = mutableListOf<AutomationDeviceOperation>()
        override val deviceOperations = AutomationDeviceOperation.entries.toSet()

        override fun state() = if (ready) AutomationBackendState.READY else AutomationBackendState.UNAVAILABLE

        override fun click(
            selector: AutomationPrivilegedSelector,
            allowed: (Int, Int, Int) -> Boolean,
            mayFinish: () -> Boolean,
        ): AutomationActionResult = error("No click fallback")

        override fun device(
            request: AutomationDeviceRequest,
            allowed: () -> Boolean,
        ): AutomationDeviceReply {
            assertTrue(allowed())
            calls += request.operation
            after()
            return AutomationDeviceReply(
                "READY",
                target =
                    AutomationDisplayTarget(
                        "com.example.app",
                        1,
                        0,
                        100,
                        100,
                        0,
                        AutomationNodeBounds(0, 0, 100, 100),
                        "r",
                    ),
                action = AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN),
            )
        }
    }
}

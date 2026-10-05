package com.helix.extensions.mobileuse.automation

import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.config.MobileUseTestConfiguration
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationPrivilegedSemanticHostTest {
    private val records = mutableMapOf<String, List<String>>()
    private val store = MobileUseTestConfiguration({ records[it].orEmpty() }, { k, v -> records[k] = v })
    private var grant = store.authorize("chat", setOf("com.example.app"), false)
    private var revoked = false
    private val call =
        ExecutableToolCall(
            "call",
            "ui.snapshot",
            "6",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(60),
            NoCancellation,
            "chat",
            "turn",
            authorizationScopeRef = grant.scope.toScopeRef(),
        )
    private val host = AutomationPrivilegedSemanticHost({ if (revoked) null else grant }, { "shizuku" })
    private val target =
        AutomationDisplayTarget(
            "com.example.app",
            7,
            0,
            100,
            100,
            0,
            AutomationNodeBounds(0, 0, 100, 100),
            "revision",
        )
    private val backend = Backend()

    @Test fun unrelatedPageChangesDoNotInvalidateAnUnchangedNodeButTargetChangesDo() {
        val node =
            host
                .snapshot(call, backend)
                .snapshot!!
                .nodes
                .single()
        val request =
            AutomationDeviceRequest(
                AutomationDeviceOperation.NODE_ACTION,
                target,
                nodeAction = AutomationNodeActionRequest(AutomationNodeAction.CLICK, node.token),
                nodeFingerprint = node.privilegedFingerprint(),
            )
        assertTrue(privilegedSemanticNodeMatches(request, target.copy(revision = "unrelated-change"), node))
        for (changed in listOf(
            node.copy(text = "Delete"),
            node.copy(enabled = false),
            node.copy(checked = true),
            node.copy(bounds = node.bounds.copy(top = 1)),
            node.copy(token = "other"),
        )) {
            assertFalse(privilegedSemanticNodeMatches(request, target, changed))
        }
        for (changed in listOf(
            target.copy(windowId = 8),
            target.copy(packageName = "other"),
            target.copy(rotation = 1),
            target.copy(bounds = target.bounds.copy(top = 1)),
        )) {
            assertFalse(privilegedSemanticNodeMatches(request, changed, node))
        }
        assertFalse(privilegedSemanticNodeMatches(request.copy(nodeFingerprint = null), target, node))
    }

    @Test fun freshTokensBindBackendAndAreConsumedEvenForUnknownEffects() {
        val result = host.snapshot(call, backend)
        assertEquals("shizuku", result.backend)
        val token =
            result.snapshot!!
                .nodes
                .single()
                .token
        assertTrue(token.startsWith("p"))
        assertNotEquals("remote", token)
        val action = AutomationNodeActionRequest(AutomationNodeAction.CLICK, token)
        assertEquals(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN, host.action(call, action).status)
        assertEquals("remote", backend.lastAction?.token)
        assertEquals(AutomationActionStatus.STALE_TOKEN, host.action(call, action).status)
        assertEquals(1, backend.actions)
    }

    @Test fun crossConversationAndReplacementSnapshotsCannotReuseTokens() {
        val first =
            host
                .snapshot(call, backend)
                .snapshot!!
                .nodes
                .single()
                .token
        val second =
            host
                .snapshot(call, backend)
                .snapshot!!
                .nodes
                .single()
                .token
        assertNotEquals(first, second)
        assertEquals(
            AutomationActionStatus.TOKEN_UNKNOWN,
            host.action(call, AutomationNodeActionRequest(AutomationNodeAction.CLICK, first)).status,
        )
        val fresh =
            host
                .snapshot(call, backend)
                .snapshot!!
                .nodes
                .single()
                .token
        assertEquals(
            AutomationActionStatus.NO_ACTIVE_SESSION,
            host
                .action(
                    call.copy(sessionId = "other"),
                    AutomationNodeActionRequest(AutomationNodeAction.CLICK, fresh),
                ).status,
        )
        assertEquals(0, backend.actions)
    }

    @Test fun revokedOrOutOfScopeObservationNeverPublishesNodes() {
        backend.afterSnapshot = { revoked = true }
        assertNull(host.snapshot(call, backend).snapshot)
        revoked = false
        backend.afterSnapshot = {}
        backend.observed = target.copy(packageName = "com.other.app")
        assertEquals(AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED, host.snapshot(call, backend).status)
    }

    @Test fun backendLossBeforeActionNeverFallsBackOrDispatches() {
        val token =
            host
                .snapshot(call, backend)
                .snapshot!!
                .nodes
                .single()
                .token
        backend.ready = false
        assertEquals(
            AutomationActionStatus.NO_ACTIVE_SESSION,
            host.action(call, AutomationNodeActionRequest(AutomationNodeAction.CLICK, token)).status,
        )
        assertEquals(0, backend.actions)
    }

    private inner class Backend : AutomationPrivilegedBackend {
        override val deviceOperations = AutomationDeviceOperation.entries.toSet()
        var observed = target
        var ready = true
        var actions = 0
        var lastAction: AutomationNodeActionRequest? = null
        var afterSnapshot: () -> Unit = {}

        override fun state() = if (ready) AutomationBackendState.READY else AutomationBackendState.LOST

        override fun click(
            selector: AutomationPrivilegedSelector,
            allowed: (Int, Int, Int) -> Boolean,
            mayFinish: () -> Boolean,
        ): AutomationActionResult = error("must not switch to exact click")

        override fun device(
            request: AutomationDeviceRequest,
            allowed: () -> Boolean,
        ): AutomationDeviceReply {
            assertTrue(allowed())
            return when (request.operation) {
                AutomationDeviceOperation.OBSERVE -> {
                    AutomationDeviceReply("READY", observed)
                }

                AutomationDeviceOperation.SNAPSHOT -> {
                    afterSnapshot()
                    AutomationDeviceReply(
                        "SUCCESS",
                        target,
                        snapshot =
                            AutomationSnapshotResult(
                                AutomationSnapshotStatus.SUCCESS,
                                AutomationSnapshot(
                                    target.packageName,
                                    7,
                                    0,
                                    Instant.now(),
                                    listOf(
                                        AutomationSnapshotNode(
                                            "remote",
                                            null,
                                            0,
                                            "Button",
                                            "Install",
                                            null,
                                            null,
                                            target.bounds,
                                            true,
                                            false,
                                            false,
                                            false,
                                            true,
                                        ),
                                    ),
                                    false,
                                ),
                            ),
                    )
                }

                AutomationDeviceOperation.NODE_ACTION -> {
                    assertEquals(32, request.nodeFingerprint?.length)
                    actions++
                    lastAction = request.nodeAction
                    AutomationDeviceReply(
                        "ACTION_OUTCOME_UNKNOWN",
                        action =
                            AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN),
                    )
                }

                else -> {
                    error("unexpected operation")
                }
            }
        }
    }
}

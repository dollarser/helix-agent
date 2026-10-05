package com.helix.extensions.mobileuse.automation

import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.config.MobileUseTestConfiguration
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationPrivilegedDeviceTest {
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
    private val stroke = AutomationStroke(listOf(AutomationPoint(10f, 10f), AutomationPoint(80f, 80f)), 0, 200)

    @Test fun semanticScrollAcceptsVisibleContainerAboveKeyboardButClickDoesNot() {
        val visible: (Int, Int) -> Boolean = { _, y -> y < 40 }
        assertTrue(
            privilegedSemanticAnchorPermitted(AutomationNodeAction.SCROLL_FORWARD, target.bounds, target, visible),
        )
        assertTrue(
            privilegedSemanticAnchorPermitted(AutomationNodeAction.SCROLL_BACKWARD, target.bounds, target, visible),
        )
        assertFalse(privilegedSemanticAnchorPermitted(AutomationNodeAction.CLICK, target.bounds, target, visible))
        assertFalse(
            privilegedSemanticAnchorPermitted(
                AutomationNodeAction.SCROLL_FORWARD,
                target.bounds,
                target,
            ) { _, _ -> false },
        )
    }

    @Test fun semanticScrollRejectsEmptyClippedBoundsWithoutCheckingPoints() {
        assertFalse(
            privilegedSemanticAnchorPermitted(
                AutomationNodeAction.SCROLL_FORWARD,
                AutomationNodeBounds(0, 120, 100, 100),
                target,
            ) { _, _ -> error("No visible geometry") },
        )
    }

    @Test fun replacementObservationAndDifferentConversationCannotReuseFrame() {
        val authority = Authority()
        val backend = Backend(target)
        val host = AutomationPrivilegedDeviceHost(authority)
        val first = requireNotNull(host.observe(authority.call, backend).frame)
        val second = requireNotNull(host.observe(authority.call, backend).frame)
        assertEquals(
            "FRAME_STALE",
            host
                .execute(
                    authority.call,
                    first.token,
                    AutomationDeviceOperation.GESTURE,
                    listOf(stroke),
                ).status,
        )
        assertEquals(
            "FRAME_STALE",
            host
                .execute(
                    authority.call.copy(sessionId = "other"),
                    second.token,
                    AutomationDeviceOperation.SCREENSHOT,
                ).status,
        )
        assertEquals(listOf(AutomationDeviceOperation.OBSERVE, AutomationDeviceOperation.OBSERVE), backend.calls)
    }

    @Test fun restrictedCaptureNeverInvokesWholeDisplayBackend() {
        val authority = Authority(whole = false)
        val backend = Backend(target)
        val host = AutomationPrivilegedDeviceHost(authority)
        val observation = host.observe(authority.call, backend)
        assertFalse(observation.screenshotSupported)
        assertTrue(observation.gestureSupported)
        val result =
            host.execute(
                authority.call,
                requireNotNull(observation.frame).token,
                AutomationDeviceOperation.SCREENSHOT,
            )
        assertEquals("WINDOW_CAPTURE_REQUIRES_ACCESSIBILITY", result.screenshot?.status)
        assertEquals(listOf(AutomationDeviceOperation.OBSERVE), backend.calls)
    }

    @Test fun revocationDuringCaptureDiscardsPixelsAndUnknownGestureNeverReplays() {
        val authority = Authority()
        val backend = Backend(target)
        val host = AutomationPrivilegedDeviceHost(authority)
        val frame = requireNotNull(host.observe(authority.call, backend).frame)
        backend.after = { authority.revoked = true }
        val result = host.execute(authority.call, frame.token, AutomationDeviceOperation.SCREENSHOT)
        assertNull(result.screenshot)
        assertEquals("AUTHORIZATION_CHANGED", result.status)
        authority.revoked = false
        backend.after = {}
        val fresh = requireNotNull(host.observe(authority.call, backend).frame)
        assertEquals(
            AutomationActionStatus.ACTION_OUTCOME_UNKNOWN,
            host.execute(authority.call, fresh.token, AutomationDeviceOperation.GESTURE, listOf(stroke)).action?.status,
        )
        assertFalse(host.owns(fresh.token))
        assertEquals(1, backend.calls.count { it == AutomationDeviceOperation.GESTURE })
    }

    @Test fun changedTargetOverlayAndInvalidStrokeRefuseInput() {
        val request = AutomationDeviceRequest(AutomationDeviceOperation.GESTURE, target, listOf(stroke))
        val own = AutomationWindowRegion(7, 1, target.packageName, target.bounds)
        assertTrue(privilegedGesturePermitted(request, target, listOf(own)))
        assertFalse(privilegedGesturePermitted(request, target.copy(rotation = 1), listOf(own)))
        assertFalse(privilegedGesturePermitted(request, target.copy(revision = "changed"), listOf(own)))
        assertFalse(privilegedGesturePermitted(request, target, emptyList()))
        val overlay = AutomationWindowRegion(8, 2, null, AutomationNodeBounds(20, 20, 40, 40))
        assertFalse(privilegedGesturePermitted(request, target, listOf(own, overlay)))
        assertThrows(IllegalArgumentException::class.java) {
            privilegedGesturePermitted(
                request.copy(strokes = listOf(stroke.copy(durationMillis = Long.MAX_VALUE))),
                target,
                listOf(own),
            )
        }
    }

    private class Authority(
        whole: Boolean = true,
    ) : AutomationDeviceAuthority {
        private val records = mutableMapOf<String, List<String>>()
        private val store = MobileUseTestConfiguration({ records[it].orEmpty() }, { k, v -> records[k] = v })
        private val grant = store.authorize("chat", setOf("com.example.app"), whole)
        var revoked = false
        val call =
            ExecutableToolCall(
                "call",
                "ui.device",
                "1",
                JsonObject(emptyMap()),
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(60),
                NoCancellation,
                "chat",
                "turn",
                authorizationScopeRef = grant.scope.toScopeRef(),
            )

        override fun liveDeviceGrant(call: ExecutableToolCall) =
            grant.takeIf {
                !revoked && privilegedCallAdmitted(call, it, "com.example.app", false)
            }

        override fun windowCaptureAvailable() = false

        override fun captureAuthorizedWindow(
            call: ExecutableToolCall,
            expected: AutomationDisplayTarget,
        ) = AutomationScreenshot("WINDOW_CAPTURE_REQUIRES_ACCESSIBILITY")

        override fun rootState() = AutomationBackendState.READY

        override fun shizukuState() = AutomationBackendState.UNAVAILABLE

        override fun preferredClickBackend() = AutomationClickBackend.ROOT
    }

    private class Backend(
        private val target: AutomationDisplayTarget,
    ) : AutomationPrivilegedBackend {
        override val deviceOperations = AutomationDeviceOperation.entries.toSet()
        val calls = mutableListOf<AutomationDeviceOperation>()
        var after: () -> Unit = {}

        override fun state() = AutomationBackendState.READY

        override fun click(
            selector: AutomationPrivilegedSelector,
            allowed: (Int, Int, Int) -> Boolean,
            mayFinish: () -> Boolean,
        ) = error("Unexpected click replay")

        override fun device(
            request: AutomationDeviceRequest,
            allowed: () -> Boolean,
        ): AutomationDeviceReply {
            assertTrue(allowed())
            calls.add(request.operation)
            after()
            return when (request.operation) {
                AutomationDeviceOperation.OBSERVE -> {
                    AutomationDeviceReply("READY", target)
                }

                AutomationDeviceOperation.SCREENSHOT -> {
                    AutomationDeviceReply(
                        "SAVED",
                        target,
                        AutomationScreenshot("SAVED", byteArrayOf(1)),
                    )
                }

                AutomationDeviceOperation.GESTURE -> {
                    AutomationDeviceReply(
                        "OUTCOME_UNKNOWN",
                        action =
                            AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN),
                    )
                }

                else -> {
                    error("Unexpected semantic operation in screen fixture")
                }
            }
        }
    }
}

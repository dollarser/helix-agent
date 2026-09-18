package com.helix.app.readiness

import com.helix.core.model.Capability
import com.helix.core.policy.CapabilityGrant
import com.helix.core.policy.GrantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * HXA-205 slice 1 (准备状态投影): the read-only readiness projection maps already-read facts to
 * per-goal missing items + the next action. These tests pin the per-goal prerequisites and the
 * distinct next actions (never one generic resume), including the consumer NOT_AVAILABLE Runtime
 * and the "files never wait for a model" invariant.
 */
class ReadinessProjectionTest {
    private fun grant(state: GrantState): CapabilityGrant =
        CapabilityGrant(
            capability = Capability.WEB_BROWSING,
            state = state,
            grantedBySystem = true,
            userScope = null,
            checkedAt = Instant.now(),
        )

    private fun neverCap() = { _: Capability -> grant(GrantState.GRANTED) }

    // --- CHAT: the model connection is the only prerequisite ---

    @Test
    fun chatWithConfiguredModelIsReady() {
        val p = CapabilityReadiness.project(ReadinessGoal.CHAT, true, neverCap(), RuntimeReadiness.NOT_AVAILABLE)
        assertTrue(p.ready)
        assertEquals(ReadinessState.READY, p.items.single().state)
        assertNull(p.nextAction)
    }

    @Test
    fun chatWithoutModelOffersConfigureAction() {
        val p = CapabilityReadiness.project(ReadinessGoal.CHAT, false, neverCap(), RuntimeReadiness.NOT_AVAILABLE)
        assertFalse(p.ready)
        assertEquals(ReadinessItemKind.MODEL, p.items.single().kind)
        assertEquals(ReadinessState.MISSING, p.items.single().state)
        assertEquals(ReadinessActionKind.ADD_MODEL, p.nextAction)
    }

    // --- FILES: the app workspace is always ready and never waits for a model ---

    @Test
    fun filesAreReadyWithoutAnyModelOrRuntime() {
        val p = CapabilityReadiness.project(ReadinessGoal.FILES, false, neverCap(), RuntimeReadiness.NOT_AVAILABLE)
        assertTrue(p.ready)
        assertEquals(ReadinessItemKind.WORKSPACE, p.items.single().kind)
        assertEquals(ReadinessState.READY, p.items.single().state)
        assertNull(p.nextAction)
    }

    // --- BROWSER: a device-presence capability, honestly unavailable when WebView is absent ---

    @Test
    fun browserIsReadyWhenWebViewPresent() {
        val p = CapabilityReadiness.project(ReadinessGoal.BROWSER, true, neverCap(), RuntimeReadiness.NOT_AVAILABLE)
        assertTrue(p.ready)
        assertEquals(ReadinessState.READY, p.items.single().state)
        assertNull(p.nextAction)
    }

    @Test
    fun browserIsHonestUnavailableWithoutWebView() {
        val p =
            CapabilityReadiness.project(
                ReadinessGoal.BROWSER,
                true,
                { _: Capability -> grant(GrantState.UNAVAILABLE) },
                RuntimeReadiness.NOT_AVAILABLE,
            )
        assertFalse(p.ready)
        assertEquals(ReadinessItemKind.WEB, p.items.single().kind)
        assertEquals(ReadinessState.NOT_AVAILABLE, p.items.single().state)
        // No in-app fix for a missing WebView — no action is offered (honest, not a fake repair).
        assertNull(p.nextAction)
    }

    // --- LINUX (developer-only): the Runtime states map to DISTINCT next actions ---

    @Test
    fun linuxReadyOffersNoAction() {
        val p = CapabilityReadiness.project(ReadinessGoal.LINUX, true, neverCap(), RuntimeReadiness.READY)
        assertTrue(p.ready)
        assertEquals(ReadinessState.READY, p.items.single().state)
        assertNull(p.nextAction)
    }

    @Test
    fun linuxNotVerifiedOffersVerifyAction() {
        val p = CapabilityReadiness.project(ReadinessGoal.LINUX, true, neverCap(), RuntimeReadiness.NOT_VERIFIED)
        assertFalse(p.ready)
        assertEquals(ReadinessItemKind.RUNTIME, p.items.single().kind)
        assertEquals(ReadinessState.MISSING, p.items.single().state)
        assertEquals(ReadinessActionKind.VERIFY_RUNTIME, p.nextAction)
    }

    @Test
    fun linuxNotInstalledOffersRepairNotASeparateInstallFlow() {
        val p = CapabilityReadiness.project(ReadinessGoal.LINUX, true, neverCap(), RuntimeReadiness.NOT_INSTALLED)
        assertFalse(p.ready)
        assertEquals(ReadinessActionKind.REPAIR_RUNTIME, p.nextAction)
    }

    @Test
    fun linuxDisabledOffersTheSameRepairAction() {
        val p =
            CapabilityReadiness.project(
                ReadinessGoal.LINUX,
                true,
                neverCap(),
                RuntimeReadiness.DISABLED_OR_FORCED_STOPPED,
            )
        assertFalse(p.ready)
        assertEquals(ReadinessState.MISSING, p.items.single().state)
        assertEquals(ReadinessActionKind.REPAIR_RUNTIME, p.nextAction)
    }

    @Test
    fun linuxConsumerIsHonestUnavailableWithNoAction() {
        // Consumer build: the Runtime is not available at all — the readiness view must not offer
        // an unfulfillable Runtime entry (HXA-205 consumer requirement).
        val p = CapabilityReadiness.project(ReadinessGoal.LINUX, true, neverCap(), RuntimeReadiness.NOT_AVAILABLE)
        assertFalse(p.ready)
        assertEquals(ReadinessState.NOT_AVAILABLE, p.items.single().state)
        assertNull(p.nextAction)
    }
}

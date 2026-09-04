package com.helix.core.policy

import com.helix.core.model.ProviderId
import com.helix.core.model.SafetyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * HXA-068 (ADR-0012): [LiveEgressRules] is the single fail-closed gate between the persisted
 * rule store and the Policy Engine. It must (1) hand the engine the FULL bound set while the
 * profile is ADVANCED, (2) yield NOTHING when the profile is STANDARD (Profile 切换 fails
 * closed — a consumer build can never be ADVANCED, so the same branch covers it), and (3) yield
 * NOTHING when the store cannot be read (a throwing loader must never auto-approve a partial,
 * possibly-wrong set). The validity window is deliberately NOT tested here — it lives in the
 * Policy Engine's `isLiveFor` (expiry + clock-rollback), covered in [PolicyEngineTest].
 */
class LiveEgressRulesTest {
    private val createdAt = Instant.parse("2026-09-01T00:00:00Z")
    private val rule =
        HighSensitivityRule.withDuration(
            EgressTarget.Provider(ProviderId("provider-1")),
            "https://api.example.com/v1",
            WorkspaceScope("ws-1"),
            RuleDuration.HOURS_24,
            createdAt,
        )
    private val mcpRule =
        HighSensitivityRule.withDuration(
            EgressTarget.Mcp(
                com.helix.core.model
                    .McpServerId("mcp-1"),
            ),
            "https://mcp.example.com",
            WorkspaceScope("ws-1"),
            RuleDuration.DAYS_7,
            createdAt,
        )

    @Test
    fun standardProfileYieldsNoRulesEvenWhenTheStoreHasThem() {
        val live = LiveEgressRules.current(SafetyProfile.STANDARD) { listOf(rule, mcpRule) }
        assertTrue("STANDARD must never auto-approve (Profile switch fails closed)", live.isEmpty())
    }

    @Test
    fun advancedProfileYieldsTheFullBoundSet() {
        val live = LiveEgressRules.current(SafetyProfile.ADVANCED) { listOf(rule, mcpRule, rule) }
        assertEquals(setOf(rule, mcpRule), live)
    }

    @Test
    fun anEmptyAdvancedStoreYieldsAnEmptySet() {
        val live = LiveEgressRules.current(SafetyProfile.ADVANCED) { emptyList() }
        assertTrue(live.isEmpty())
    }

    @Test
    fun aThrowingLoaderFailsClosedToAnEmptySet() {
        // A corrupt/undecodable row surfaces as a throw from the store; the gate must swallow it
        // and return NO rules rather than a partial, silently-wrong set (ADR-0012: 存储损坏 fail closed).
        val live =
            LiveEgressRules.current(SafetyProfile.ADVANCED) {
                throw IllegalStateException("corrupt egress rule row")
            }
        assertTrue("a failing store must fail closed to no rules", live.isEmpty())
    }

    @Test
    fun profileTakesPrecedenceOverTheLoader() {
        // Even if the loader would return rules, a non-ADVANCED profile short-circuits before
        // touching the store: the loader must not even run.
        var loaderInvoked = false
        val live =
            LiveEgressRules.current(SafetyProfile.STANDARD) {
                loaderInvoked = true
                listOf(rule)
            }
        assertTrue(live.isEmpty())
        assertTrue("a non-ADVANCED profile must not read the store at all", !loaderInvoked)
    }
}

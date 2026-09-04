package com.helix.core.policy

import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * HXA-033: ADR-0005 rule construction — only SENSITIVE data, only the four fixed TTLs
 * (1h/24h/7d/30d, default 24h, hard cap 30d), no permanent or custom values.
 */
class HighSensitivityRuleTest {
    private val createdAt = Instant.parse("2026-09-01T00:00:00Z")
    private val origin = NormalizedEndpoint.parse("https://api.example.com/v1")
    private val target = EgressTarget.Provider(ProviderId("provider-1"))
    private val scope = WorkspaceScope("ws-1")

    @Test
    fun onlySensitiveDataMayCarryARule() {
        assertIllegal { rule(DataSensitivity.NORMAL) }
        assertIllegal { rule(DataSensitivity.FORBIDDEN) }
        rule(DataSensitivity.SENSITIVE)
    }

    @Test
    fun onlyTheFourFixedTtlsAreConstructable() {
        rule(createdAt, createdAt.plus(Duration.ofHours(1)))
        rule(createdAt, createdAt.plus(Duration.ofHours(24)))
        rule(createdAt, createdAt.plus(Duration.ofDays(7)))
        rule(createdAt, createdAt.plus(Duration.ofDays(30)))
    }

    @Test
    fun customShorterOrLongerTtlsAreRejected() {
        assertIllegal { rule(createdAt, createdAt) }
        assertIllegal { rule(createdAt, createdAt.plus(Duration.ofHours(2))) }
        assertIllegal { rule(createdAt, createdAt.plus(Duration.ofHours(13))) }
        assertIllegal { rule(createdAt, createdAt.plus(Duration.ofDays(30)).plus(Duration.ofMinutes(1))) }
        assertIllegal { rule(createdAt, createdAt.plus(Duration.ofDays(31))) }
        assertIllegal { rule(createdAt, createdAt.plusSeconds(-1)) }
    }

    @Test
    fun defaultTtlFactoryIs24Hours() {
        val rule = HighSensitivityRule.withDefaultTtl(target, origin, scope, createdAt)
        assertEquals(RuleDuration.HOURS_24, rule.duration)
        assertEquals(createdAt.plus(Duration.ofHours(24)), rule.expiresAt)
        assertEquals(createdAt, rule.createdAt)
        assertEquals(DataSensitivity.SENSITIVE, rule.dataCategory)
    }

    @Test
    fun mcpTargetIsBoundById() {
        val mcp = EgressTarget.Mcp(McpServerId("mcp-server-1"))
        val rule = HighSensitivityRule.withDefaultTtl(mcp, origin, scope, createdAt)
        assertEquals(mcp, rule.target)
    }

    @Test
    fun scopeIsMandatoryAndPartOfTheValue() {
        val a = HighSensitivityRule.withDefaultTtl(target, origin, scope, createdAt)
        val b = HighSensitivityRule.withDefaultTtl(target, origin, WorkspaceScope("ws-2"), createdAt)
        assertTrue(a != b)
        assertEquals(Duration.ofHours(24), Duration.between(a.createdAt, a.expiresAt))
    }

    @Test
    fun withDurationAppliesEachOfTheFourFixedTtls() {
        // The developer/Advanced create-form entry (HXA-068): each of the four fixed TTLs must
        // land on exactly createdAt + TTL and round-trip through [RuleDuration].
        for (duration in RuleDuration.entries) {
            val rule =
                HighSensitivityRule.withDuration(
                    target,
                    "https://api.example.com/v1",
                    scope,
                    duration,
                    createdAt,
                )
            assertEquals(duration, rule.duration)
            assertEquals(createdAt.plus(duration.duration), rule.expiresAt)
            assertEquals(createdAt, rule.createdAt)
            assertEquals(target, rule.target)
            assertEquals(origin, rule.origin)
            assertEquals(scope, rule.scope)
            assertEquals(DataSensitivity.SENSITIVE, rule.dataCategory)
        }
    }

    @Test
    fun withDurationPreservesEveryBoundFacetFromRawStrings() {
        val mcp = EgressTarget.Mcp(McpServerId("mcp-server-1"))
        val rule =
            HighSensitivityRule.withDuration(
                mcp,
                "https://mcp.example.com:8443/rpc",
                WorkspaceScope("ws-9"),
                RuleDuration.DAYS_30,
                createdAt,
            )
        assertEquals(mcp, rule.target)
        assertEquals(NormalizedEndpoint.parse("https://mcp.example.com:8443/rpc"), rule.origin)
        assertEquals(WorkspaceScope("ws-9"), rule.scope)
        assertEquals(RuleDuration.DAYS_30, rule.duration)
    }

    @Test
    fun withDurationFailsClosedOnANonCanonicalOrigin() {
        // The raw-string factory re-parses the origin fail-closed (same rules as NormalizedEndpoint.parse).
        val badOrigins =
            listOf(
                "ftp://api.example.com",
                "https://user:pass@api.example.com",
                "https://api.example.com/?q=1",
                "not-a-url",
            )
        for (badOrigin in badOrigins) {
            assertIllegal {
                HighSensitivityRule.withDuration(
                    target,
                    badOrigin,
                    scope,
                    RuleDuration.HOURS_1,
                    createdAt,
                )
            }
        }
    }

    @Test
    fun withDurationCannotRepresentAWildcardOrBlankTarget() {
        // A wildcard/blank target is unrepresentable: ProviderId/McpServerId reject it at
        // construction, so no blanket grant is buildable through this factory (ADR-0005).
        assertIllegal { EgressTarget.Provider(ProviderId("provider-*")) }
        assertIllegal { EgressTarget.Provider(ProviderId("")) }
        assertIllegal { EgressTarget.Mcp(McpServerId("mcp/*")) }
    }

    private fun rule(category: DataSensitivity): HighSensitivityRule =
        HighSensitivityRule(target, origin, category, scope, createdAt, createdAt.plus(Duration.ofHours(24)))

    private fun rule(
        created: Instant,
        expires: Instant,
    ): HighSensitivityRule = HighSensitivityRule(target, origin, DataSensitivity.SENSITIVE, scope, created, expires)

    private fun assertIllegal(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }
}

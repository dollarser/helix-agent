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

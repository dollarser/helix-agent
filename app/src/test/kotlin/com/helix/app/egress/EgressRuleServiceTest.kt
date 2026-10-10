package com.helix.app.egress

import com.helix.app.APP_SCOPE_ID
import com.helix.core.model.McpServerId
import com.helix.core.model.ProviderId
import com.helix.core.policy.EgressTarget
import com.helix.core.policy.HighSensitivityRule
import com.helix.core.policy.RuleDuration
import com.helix.core.policy.WorkspaceScope
import com.helix.core.storage.repository.StoredEgressRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class EgressRuleServiceTest {
    private val now = Instant.parse("2026-10-10T00:00:00Z")

    private fun draft(
        provider: Boolean = true,
        id: String = "exact",
        origin: String = "https://example.com",
        duration: RuleDuration = RuleDuration.DEFAULT,
    ) = EgressRuleDraft(provider, id, origin, duration)

    @Test fun createProjectsExactTargetScopeAndFixedExpiryThenRevokes(): Unit =
        runBlocking {
            val rows = mutableListOf<StoredEgressRule>()
            val service =
                EgressRuleService(
                    { rows.toList() },
                    { rule -> StoredEgressRule("one", rule).also(rows::add) },
                    { id -> check(rows.removeAll { it.id == id }) },
                    { now },
                )
            for (provider in listOf(true, false)) {
                val request = draft(provider, origin = "https://example.com/v1", duration = RuleDuration.DAYS_7)
                assertTrue(service.create(request).isSuccess)
                val stored = rows.single().rule
                val expectedTarget =
                    if (provider) EgressTarget.Provider(ProviderId("exact")) else EgressTarget.Mcp(McpServerId("exact"))
                assertEquals(expectedTarget, stored.target)
                assertEquals(WorkspaceScope(APP_SCOPE_ID), stored.scope)
                assertEquals(now.plus(RuleDuration.DAYS_7.duration), stored.expiresAt)
                val row = service.load().getOrThrow().single()
                assertEquals(if (provider) "provider:exact" else "mcp:exact", row.target)
                assertEquals("https://example.com:443", row.origin)
                assertEquals(stored.scope.toScopeRef(), row.scope)
                assertEquals(stored.expiresAt, row.expiresAt)
                assertTrue(service.revoke(row.id).isSuccess)
                assertTrue(service.load().getOrThrow().isEmpty())
            }
        }

    @Test fun invalidTargetsAndOriginsNeverReachStorage(): Unit =
        runBlocking {
            var writes = 0
            val service =
                EgressRuleService({ emptyList() }, {
                    writes++
                    StoredEgressRule("unexpected", it)
                }, {}, { now })
            for (provider in listOf(true, false)) {
                for (id in listOf("", "*")) {
                    assertTrue(service.create(draft(provider, id)).isFailure)
                }
                val invalid =
                    listOf(
                        "file:///tmp/a",
                        "https://u:p@example.com",
                        "https://example.com?q=x",
                        "https://example.com#x",
                    )
                for (origin in invalid) {
                    assertTrue(service.create(draft(provider, origin = origin)).isFailure)
                }
            }
            assertEquals(0, writes)
        }

    @Test fun readWriteAndRevokeErrorsAreFailuresIncludingNullMessage(): Unit =
        runBlocking {
            val failure = IOException()
            val service = EgressRuleService({ throw failure }, { throw failure }, { throw failure })
            assertTrue(service.load().exceptionOrNull() is IOException)
            assertTrue(service.create(draft()).isFailure)
            assertTrue(service.revoke("missing").isFailure)
        }

    @Test fun committedCreationIsNotRolledBackOrReplayedWhenReloadFails(): Unit =
        runBlocking {
            var committed: HighSensitivityRule? = null
            var writes = 0
            val service =
                EgressRuleService({ throw IOException("reload failed") }, { rule ->
                    writes++
                    committed = rule
                    StoredEgressRule("durable", rule)
                }, {})
            assertTrue(service.create(draft()).isSuccess)
            assertTrue(service.load().isFailure)
            assertEquals(1, writes)
            assertEquals(EgressTarget.Provider(ProviderId("exact")), requireNotNull(committed).target)
        }

    @Test fun cancellationAndFatalFailuresPropagateFromEveryOperation(): Unit =
        runBlocking {
            for (failure in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
                val service = EgressRuleService({ throw failure }, { throw failure }, { throw failure })
                val actions =
                    listOf<suspend () -> Any>(
                        { service.load() },
                        { service.create(draft()) },
                        { service.revoke("one") },
                    )
                for (action in actions) {
                    val result = runCatching { action() }
                    assertFalse(result.isSuccess)
                    val propagated = requireNotNull(result.exceptionOrNull())
                    assertEquals(failure.javaClass, propagated.javaClass)
                    assertEquals(failure.message, propagated.message)
                }
            }
        }
}

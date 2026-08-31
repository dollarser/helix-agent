package com.helix.core.policy

import com.helix.core.model.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * HXA-032: the Capability Center invariant — 缓存不代替执行时检查. Every check consults the
 * live resolver; recorded (cached) grants are write-only audit and are never served.
 */
class CapabilityCenterTest {
    private val fixedTime = Instant.parse("2026-09-01T00:00:00Z")

    /** Answers from a mutable state map, counting calls — the production shape in a test. */
    private class ScriptedResolver(
        private val initial: Map<Capability, GrantState>,
        private val bySystem: Boolean = true,
        private val checkedAt: Instant = Instant.parse("2026-09-01T00:00:00Z"),
    ) : CapabilityResolver {
        var states: MutableMap<Capability, GrantState> = initial.toMutableMap()
        var calls = 0

        override fun resolve(capability: Capability): CapabilityGrant {
            calls += 1
            return CapabilityGrant(capability, states.getValue(capability), bySystem, null, checkedAt)
        }
    }

    private class SinkRecorder(
        private val sink: MutableList<CapabilityGrant>,
    ) : CapabilityGrantRecorder {
        override fun record(grant: CapabilityGrant) {
            sink += grant
        }
    }

    private class ThrowingRecorder : CapabilityGrantRecorder {
        override fun record(grant: CapabilityGrant) {
            error("audit store down")
        }
    }

    @Test
    fun checkAlwaysConsultsTheLiveResolver() {
        val sink = mutableListOf<CapabilityGrant>()
        val resolver = ScriptedResolver(mapOf(Capability.WEB_BROWSING to GrantState.GRANTED))
        val center = CapabilityCenter(resolver, SinkRecorder(sink))
        center.check(Capability.WEB_BROWSING)
        center.check(Capability.WEB_BROWSING)
        assertEquals(2, resolver.calls)
        assertEquals(2, sink.size)
    }

    @Test
    fun recordedGrantNeverSubstitutesForExecutionTimeCheck() {
        val sink = mutableListOf<CapabilityGrant>()
        val resolver = ScriptedResolver(mapOf(Capability.WEB_BROWSING to GrantState.GRANTED))
        val center = CapabilityCenter(resolver, SinkRecorder(sink))

        val first = center.check(Capability.WEB_BROWSING)
        assertEquals(GrantState.GRANTED, first.state)

        // the system revokes the capability; the audit rows still "say" GRANTED
        resolver.states[Capability.WEB_BROWSING] = GrantState.DENIED
        val second = center.check(Capability.WEB_BROWSING)

        assertEquals(GrantState.GRANTED, sink.first { it.capability == Capability.WEB_BROWSING }.state)
        assertEquals(GrantState.DENIED, second.state)
        assertEquals(2, resolver.calls)
    }

    @Test
    fun auditRehydratedGrantIsNeverUsable() {
        val rehydrated = CapabilityGrant(Capability.WEB_BROWSING, GrantState.GRANTED, false, null, fixedTime)
        assertFalse(rehydrated.isUsable)
        val fresh = CapabilityGrant(Capability.WEB_BROWSING, GrantState.GRANTED, true, null, fixedTime)
        assertTrue(fresh.isUsable)
    }

    @Test
    fun nonGrantedStatesFailClosed() {
        assertFalse(
            CapabilityGrant(Capability.WEB_BROWSING, GrantState.DENIED, true, null, fixedTime).isUsable,
        )
        assertFalse(
            CapabilityGrant(Capability.WEB_BROWSING, GrantState.UNAVAILABLE, true, null, fixedTime).isUsable,
        )
        assertFalse(
            CapabilityGrant(Capability.WEB_BROWSING, GrantState.LOST, true, null, fixedTime).isUsable,
        )
    }

    @Test
    fun recorderFailureFailsClosed() {
        val resolver = ScriptedResolver(mapOf(Capability.WEB_BROWSING to GrantState.GRANTED))
        val center = CapabilityCenter(resolver, ThrowingRecorder())
        assertThrows(IllegalStateException::class.java) { center.check(Capability.WEB_BROWSING) }
    }

    @Test
    fun checkPreservesTheResolverCheckedAt() {
        val resolver = ScriptedResolver(mapOf(Capability.WEB_BROWSING to GrantState.GRANTED))
        val center = CapabilityCenter(resolver)
        val grant = center.check(Capability.WEB_BROWSING)
        assertEquals(fixedTime, grant.checkedAt)
    }

    @Test
    fun recorderReceivesEveryCheckInOrder() {
        val sink = mutableListOf<CapabilityGrant>()
        val resolver = ScriptedResolver(mapOf(Capability.WEB_BROWSING to GrantState.GRANTED))
        val center = CapabilityCenter(resolver, SinkRecorder(sink))
        center.check(Capability.WEB_BROWSING)
        assertEquals(listOf(Capability.WEB_BROWSING), sink.map { it.capability })
    }

    @Test
    fun evaluateResolvesEverythingAndListsMissingSorted() {
        val resolver =
            ScriptedResolver(
                mapOf(
                    Capability.WEB_BROWSING to GrantState.GRANTED,
                    Capability.SAF_DOCUMENT_TREE to GrantState.GRANTED,
                    Capability.ROOT_SHELL to GrantState.UNAVAILABLE,
                    Capability.CALENDAR_WRITE to GrantState.DENIED,
                ),
            )
        val center = CapabilityCenter(resolver)

        val evaluation =
            center.evaluate(
                setOf(
                    Capability.CALENDAR_WRITE,
                    Capability.WEB_BROWSING,
                    Capability.ROOT_SHELL,
                    Capability.SAF_DOCUMENT_TREE,
                ),
            )

        assertFalse(evaluation.satisfied)
        assertEquals(listOf(Capability.ROOT_SHELL, Capability.CALENDAR_WRITE), evaluation.missing)
        assertEquals(4, evaluation.grants.size)
        assertEquals(4, resolver.calls)
    }

    @Test
    fun evaluateIsSatisfiedWhenEverythingIsGranted() {
        val resolver =
            ScriptedResolver(
                mapOf(
                    Capability.WEB_BROWSING to GrantState.GRANTED,
                    Capability.NOTIFICATION_READ to GrantState.GRANTED,
                ),
            )
        val center = CapabilityCenter(resolver)
        val evaluation = center.evaluate(setOf(Capability.WEB_BROWSING, Capability.NOTIFICATION_READ))
        assertTrue(evaluation.satisfied)
        assertTrue(evaluation.missing.isEmpty())
        assertEquals(2, evaluation.grants.size)
    }

    @Test
    fun evaluateWithNoRequiredCapabilitiesIsVacuouslySatisfied() {
        val resolver = ScriptedResolver(emptyMap())
        val center = CapabilityCenter(resolver)
        assertTrue(center.evaluate(emptySet()).satisfied)
        assertEquals(0, resolver.calls)
    }

    @Test
    fun evaluateTreatsAuditStyleGrantsAsMissing() {
        val resolver = ScriptedResolver(mapOf(Capability.WEB_BROWSING to GrantState.GRANTED), bySystem = false)
        val center = CapabilityCenter(resolver)
        val evaluation = center.evaluate(setOf(Capability.WEB_BROWSING))
        assertFalse(evaluation.satisfied)
        assertEquals(listOf(Capability.WEB_BROWSING), evaluation.missing)
    }
}

package com.helix.tools.automation

import com.helix.core.model.Clock
import com.helix.core.policy.MobileUseGrantStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationConversationRuntimeTest {
    private val records = mutableMapOf<String, List<String>>()
    private var id = 0
    private val clock =
        object : Clock {
            override fun now() = Instant.parse("2026-10-03T00:00:00Z")
        }

    private fun grants() = MobileUseGrantStore({ records[it].orEmpty() }, { k, v -> records[k] = v })

    private fun manager() = AutomationSessionManager(clock) { "runtime-${++id}" }

    @Test fun temporaryStopsNeverDeleteSavedAuthorizationAndRebuildFreshRuntimeIdentities() {
        val grant = grants().authorize("a", emptySet(), true)
        val manager = manager()
        var runtime = manager.activate(grant)
        for (reason in listOf(
            AutomationStopReason.DEVICE_LOCKED,
            AutomationStopReason.SERVICE_DISCONNECTED,
            AutomationStopReason.SERVICE_INTERRUPTED,
            AutomationStopReason.FOREGROUND_START_FAILED,
            AutomationStopReason.EXPIRED,
            AutomationStopReason.CLOCK_ROLLBACK,
        )) {
            manager.stop(reason)
            assertNull(manager.current())
            val saved = requireNotNull(grants().find("a"))
            assertEquals(grant, saved)
            val restored = manager.activate(saved)
            assertEquals(grant.scope, restored.scope)
            assertEquals("a", restored.conversationId)
            assertNotEquals(runtime.id, restored.id)
            runtime = restored
        }
        val processRestarted = manager().activate(requireNotNull(grants().find("a")))
        assertNotEquals(runtime.id, processRestarted.id)
        assertEquals(grant.scope, processRestarted.scope)
    }

    @Test fun switchingConversationsDoesNotShareScopeOrRuntimeTokens() {
        val a = grants().authorize("a", setOf("com.app.one"), false)
        val b = grants().authorize("b", setOf("com.app.two"), false)
        val manager = manager()
        val first = manager.activate(a)
        assertEquals(first, manager.activate(a))
        val second = manager.activate(b)
        assertNotEquals(first.id, second.id)
        assertFalse(second.scope.permitsPackage("com.app.one"))
        assertTrue(second.scope.permitsPackage("com.app.two"))
        val restoredA = manager.activate(a)
        assertNotEquals(first.id, restoredA.id)
        assertEquals(a.scope, restoredA.scope)
        assertEquals(b, grants().find("b"))
    }

    @Test fun runtimeActionCountersCannotExhaustPersistentConversationConfiguration() {
        val grant = grants().authorize("a", emptySet(), true)
        val manager = manager()
        manager.activate(grant)
        repeat(1000) {
            assertEquals(AutomationActionAdmission.ADMITTED, manager.admitAction())
            assertEquals(AutomationActionCompletion.CONTINUE, manager.completeAction())
        }
        assertEquals(grant, grants().find("a"))
        assertEquals(1000, manager.current()!!.attemptedActions)
    }

    @Test fun staleNotificationAndCrossConversationProofCannotRevokeCurrentPermission() {
        val store = grants()
        val a = store.authorize("a", emptySet(), true)
        val b = store.authorize("b", emptySet(), true)
        assertFalse(store.revokeIfMatching("b", a.scope.toScopeRef()))
        val replacement = store.authorize("a", setOf("com.app.one"), false)
        assertFalse(store.revokeIfMatching("a", a.scope.toScopeRef()))
        assertTrue(store.revokeIfMatching("a", replacement.scope.toScopeRef()))
        assertNull(store.find("a"))
        assertEquals(b, store.find("b"))
    }
}

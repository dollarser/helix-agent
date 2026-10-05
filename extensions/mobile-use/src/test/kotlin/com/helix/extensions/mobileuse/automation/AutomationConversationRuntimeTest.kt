package com.helix.extensions.mobileuse.automation

import com.helix.core.model.Clock
import com.helix.extensions.mobileuse.config.MobileUseTestConfiguration
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
    private val selections = mutableMapOf<String, String>()
    private val clock =
        object : Clock {
            override fun now() = Instant.parse("2026-10-03T00:00:00Z")
        }

    private fun grants() = MobileUseTestConfiguration({ records[it].orEmpty() }, { k, v -> records[k] = v }, selections)

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

    @Test fun oldNotificationCannotStopAnotherTurnAndStopPreservesSelection() {
        val store = grants()
        val grant = store.authorize("a", emptySet(), true)
        val manager = manager()
        val old = manager.activate(grant, "turn-1")
        val current = manager.activate(grant, "turn-2")
        assertFalse(current.matchesStop("a", grant.scope.toScopeRef(), old.id))
        assertFalse(current.matchesStop("b", grant.scope.toScopeRef(), current.id))
        assertTrue(current.matchesStop("a", grant.scope.toScopeRef(), current.id))
        manager.stop(AutomationStopReason.USER_STOP)
        assertEquals(grant, store.find("a"))
        assertNotEquals(current.id, manager.activate(grant, "turn-3").id)
    }
}

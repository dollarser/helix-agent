package com.helix.app.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileUseDialogStateTest {
    @Test fun openFreezesLiteralTargetRatherThanBuildingAPageRoute() {
        val state = MobileUseDialogState()
        val target = "draft: 中文/a?b#c+space "
        state.open(target, "source")
        assertEquals(target, state.request?.sessionId)
        assertTrue(state.matches(requireNotNull(state.request).id, "source", target))
        assertThrows(IllegalArgumentException::class.java) { state.open(" ", "source") }
    }

    @Test fun authorizationReturnRestoresSameDialogWithoutAnEnableIntent() {
        val state = MobileUseDialogState()
        state.open("session", "source")
        val id = requireNotNull(state.request).id
        state.repair(SETTINGS_SYSTEM_PERMISSIONS_ROUTE, "permissions")
        state.reconcile("permissions", "session", true)
        assertEquals(id, state.request?.id)
        assertFalse(state.matches(id, "permissions", "session"))
        val scope =
            object : SaverScope {
                override fun canBeSaved(value: Any) = true
            }
        val saved = with(MobileUseDialogState.Saver) { requireNotNull(scope.save(state)) }
        val restored = requireNotNull(MobileUseDialogState.Saver.restore(saved))
        restored.reconcile("source", "session", false)
        assertTrue(restored.matches(id, "source", "session"))
        assertEquals(5, (saved as List<*>).size)
    }

    @Test fun changingSessionWhileAuthorizingExpiresTheOriginalDialog() {
        val state = MobileUseDialogState()
        state.open("old", "source")
        state.repair(SETTINGS_SYSTEM_PERMISSIONS_ROUTE, "permissions")
        state.reconcile("permissions", "different", true)
        assertNull(state.request)
    }

    @Test fun unrelatedPageAndCancelledRepairCannotResurrectTheDialog() {
        val state = MobileUseDialogState()
        state.open("session", "source")
        state.reconcile("unrelated", "session", false)
        assertNull(state.request)
        state.reconcile("source", "session", false)
        assertNull(state.request)
    }

    @Test fun reopenUsesNewGenerationSoOldResultCannotActOnNewDialog() {
        val state = MobileUseDialogState()
        state.open("session", "source")
        val old = requireNotNull(state.request).id
        state.dismiss()
        state.open("session", "source")
        assertNotEquals(old, state.request?.id)
        assertFalse(state.matches(old, "source", "session"))
    }

    @Test fun rootNavigationCancelsPendingDialogOnlyAfterDraftHandlingSucceeds() {
        val state = MobileUseDialogState()
        state.open("session", "source")
        val navigation = NavigationDeparture(state::dismiss)
        var complete: ((Boolean) -> Unit)? = null
        navigation.register(Any()) { complete = it }
        navigation.request({ true }) { }
        requireNotNull(complete)(false)
        assertTrue(state.request != null)
        navigation.request({ true }) { }
        requireNotNull(complete)(true)
        assertNull(state.request)
    }
}

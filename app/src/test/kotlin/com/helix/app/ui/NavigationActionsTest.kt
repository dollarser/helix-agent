package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationActionsTest {
    @Test fun completionIsSourceBoundAndConsumedOnce() {
        var current = true
        var pops = 0
        val result = NavigationCompletion(isCurrent = { current })
        assertTrue(result.complete { pops++ })
        current = false
        assertFalse(result.complete { pops++ })
        current = true
        assertFalse(result.complete { pops++ })
        assertFalse(result.complete { pops++ })
        assertEquals(1, pops)
    }

    @Test fun pausedOperationCannotNavigateAfterReturningToTheSamePage() {
        var released = 0
        var navigations = 0
        val completion = NavigationCompletion({ true }, { released++ })
        completion.invalidate()
        assertFalse(completion.complete { navigations++ })
        completion.invalidate()
        assertEquals(0, navigations)
        assertEquals(1, released)
    }

    @Test fun staleCompletionIsConsumedEvenIfItsPageLaterReturns() {
        var current = false
        val completion = NavigationCompletion(isCurrent = { current })
        assertFalse(completion.complete { error("stale") })
        current = true
        assertFalse(completion.complete { error("replayed") })
    }

    @Test fun dirtyDraftDefersNavigationAndRepeatedClicksDoNotReplaceItsDestination() {
        val departure = NavigationDeparture()
        var complete: ((Boolean) -> Unit)? = null
        val result = mutableListOf<String>()
        departure.register(Any()) { complete = it }
        departure.request({ true }) { result += "original" }
        departure.request({ true }) { result += "duplicate" }
        assertTrue(result.isEmpty())
        requireNotNull(complete)(true)
        requireNotNull(complete)(true)
        assertEquals(listOf("original"), result)
    }

    @Test fun failedSaveOrCancelKeepsPageAndAllowsRetry() {
        val departure = NavigationDeparture()
        var complete: ((Boolean) -> Unit)? = null
        var navigated = false
        departure.register(Any()) { complete = it }
        departure.request({ true }) { navigated = true }
        requireNotNull(complete)(false)
        assertFalse(navigated)
        departure.request({ true }) { navigated = true }
        requireNotNull(complete)(true)
        assertTrue(navigated)
    }

    @Test fun leavingOrSwitchingSessionInvalidatesLateSave() {
        val departure = NavigationDeparture()
        var complete: ((Boolean) -> Unit)? = null
        val owner = Any()
        var current = true
        var navigations = 0
        departure.register(owner) { complete = it }
        departure.request({ current }) { navigations++ }
        current = false
        requireNotNull(complete)(true)
        assertEquals(0, navigations)
        current = true
        departure.request({ current }) { navigations++ }
        departure.unregister(owner)
        requireNotNull(complete)(true)
        assertEquals(0, navigations)
    }

    @Test fun newPageOwnerDoesNotInheritOldPendingNavigation() {
        val departure = NavigationDeparture()
        var old: ((Boolean) -> Unit)? = null
        val first = Any()
        val second = Any()
        var navigations = 0
        departure.register(first) { old = it }
        departure.request({ true }) { navigations++ }
        departure.register(second) { it(true) }
        departure.unregister(first)
        requireNotNull(old)(true)
        departure.request({ true }) { navigations++ }
        assertEquals(1, navigations)
    }

    @Test fun cancelConsumesExternalRequestExactlyOnceWithoutNavigating() {
        val departure = NavigationDeparture()
        var complete: ((Boolean) -> Unit)? = null
        var cancelled = 0
        departure.register(Any()) { complete = it }
        departure.request({ true }, cancelled = { cancelled++ }) { error("cancelled navigation") }
        requireNotNull(complete)(false)
        requireNotNull(complete)(false)
        assertEquals(1, cancelled)
    }

    @Test fun cleanPageNavigatesDirectlyAndStaleSourceDoesNothing() {
        val departure = NavigationDeparture()
        var navigations = 0
        departure.request({ false }) { navigations++ }
        departure.request({ true }) { navigations++ }
        assertEquals(1, navigations)
    }
}

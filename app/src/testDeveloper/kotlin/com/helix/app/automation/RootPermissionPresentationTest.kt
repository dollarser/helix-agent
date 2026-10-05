package com.helix.app.automation

import com.helix.app.R
import com.helix.extensions.mobileuse.automation.AutomationClickBackend
import com.helix.tools.root.RootAccessStatus
import com.helix.tools.root.RootGrantState
import com.helix.tools.root.RootServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPermissionPresentationTest {
    @Test fun cachedAuthorizationDoesNotMeanServiceConnected() {
        val view =
            rootPermissionPresentation(
                RootAccessStatus(RootGrantState.UNAVAILABLE, RootServiceState.DISCONNECTED),
                true,
            )
        assertEquals(R.string.mobile_root_grant_cached, view.authorization)
        assertEquals(R.string.automation_root_unavailable, view.connection)
        assertEquals(R.string.mobile_root_connect, view.action)
        assertTrue(view.canConnect)
    }

    @Test fun requestsAndConnectionsDisableDuplicateConnectActions() {
        val requesting =
            rootPermissionPresentation(RootAccessStatus(RootGrantState.REQUESTING, RootServiceState.DISCONNECTED), null)
        assertFalse(requesting.canConnect)
        assertEquals(R.string.mobile_root_grant_requesting, requesting.authorization)
        val connecting =
            rootPermissionPresentation(RootAccessStatus(RootGrantState.GRANTED, RootServiceState.CONNECTING), true)
        assertFalse(connecting.canConnect)
        assertEquals(R.string.mobile_root_connecting, connecting.connection)
        val connected =
            rootPermissionPresentation(RootAccessStatus(RootGrantState.GRANTED, RootServiceState.CONNECTED), true)
        assertFalse(connected.canConnect)
        assertEquals(R.string.automation_root_ready, connected.connection)
    }

    @Test fun revocationAndMissingServiceCannotAppearReady() {
        val denied =
            rootPermissionPresentation(RootAccessStatus(RootGrantState.GRANTED, RootServiceState.CONNECTED), false)
        assertEquals(R.string.mobile_root_grant_denied, denied.authorization)
        assertEquals(R.string.automation_root_unavailable, denied.connection)
        assertFalse(rootPermissionPresentation(null, true).canConnect)
    }

    @Test fun summaryDisplaysSelectedBackendAndPreservesPartialAvailability() {
        assertEquals(R.string.mobile_backend_root, backendSummary(AutomationClickBackend.ROOT))
        assertEquals(R.string.mobile_backend_shizuku, backendSummary(AutomationClickBackend.SHIZUKU))
        assertEquals(R.string.mobile_backend_accessibility, backendSummary(AutomationClickBackend.ACCESSIBILITY))
        assertEquals(R.string.mobile_backend_partial, backendSummary(null))
    }
}

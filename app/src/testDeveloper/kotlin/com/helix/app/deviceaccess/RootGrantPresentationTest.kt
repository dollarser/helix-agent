package com.helix.app.deviceaccess

import com.helix.app.R
import com.helix.tools.root.RootAccessStatus
import com.helix.tools.root.RootGrantState
import com.helix.tools.root.RootServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootGrantPresentationTest {
    @Test fun disconnectedHostCanVerifyCachedAppGrantWithoutClaimingPluginReadiness() {
        val view =
            rootGrantPresentation(RootAccessStatus(RootGrantState.UNAVAILABLE, RootServiceState.DISCONNECTED), true)
        assertEquals(R.string.device_root_granted, view.label)
        assertTrue(view.canRequest)
    }

    @Test fun currentRevocationOverridesPreviouslyConnectedService() {
        val view = rootGrantPresentation(RootAccessStatus(RootGrantState.GRANTED, RootServiceState.CONNECTED), false)
        assertEquals(R.string.device_root_denied, view.label)
        assertTrue(view.canRequest)
    }

    @Test fun pendingAuthorizationCannotLaunchDuplicateRequest() {
        val view =
            rootGrantPresentation(RootAccessStatus(RootGrantState.REQUESTING, RootServiceState.DISCONNECTED), null)
        assertEquals(R.string.device_root_requesting, view.label)
        assertFalse(view.canRequest)
    }
}

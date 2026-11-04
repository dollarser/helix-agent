package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt-in passive inspection; never selects a plugin, edits settings, or acquires system access. */
class MobileUseDefaultScopeDeviceTest {
    @Test fun emptyScopeRecordUsesWholePhoneAndReadyBackendAllowsConversationSelection() {
        check(InstrumentationRegistry.getArguments().getString("helixDefaultScopeInspection") == "true")
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
        val scope = lines.lines(MobileUseGrantStore.CONFIG_KEY)
        val display = lines.lines(MobileUseGrantStore.EXECUTION_KEY)
        check(scope.isEmpty()) { "Inspection requires an already empty scope record; no settings are changed" }
        val effective = checkNotNull(AutomationPermissionCenter(app).globalConfiguration())
        assertTrue(effective.scope.allApplications)
        assertTrue(effective.scope.allowedPackages.isEmpty())
        val row =
            container.pluginService
                .sessionRows("mobile-default-scope-passive-inspection", "bundled-plugin:mobile-use")
                .single()
        assertTrue(row.available)
        assertTrue(row.enabled)
        assertTrue("An existing authorized backend must be ready", row.readyComponents > 0)
        assertNull(row.setupReason)
        assertFalse(row.selected)
        assertEquals(scope, lines.lines(MobileUseGrantStore.CONFIG_KEY))
        assertEquals(display, lines.lines(MobileUseGrantStore.EXECUTION_KEY))
    }
}

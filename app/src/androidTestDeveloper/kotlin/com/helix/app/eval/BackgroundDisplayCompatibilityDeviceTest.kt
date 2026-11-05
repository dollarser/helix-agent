package com.helix.app.eval

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.core.model.SafetyProfile
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Invoked explicitly on API29 only. No shell connection, pairing or accessibility grant is needed. */
class BackgroundDisplayCompatibilityDeviceTest {
    @SdkSuppress(maxSdkVersion = 30)
    @Suppress("LongMethod") // One bounded original scope retains complete restoration of shared settings.
    @Test
    fun unsupportedPlatformReportsUnavailableWithoutMainScreenFallback() {
        check(Build.VERSION.SDK_INT < 31)
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        check(
            c.storage.turns
                .listActive()
                .isEmpty(),
        )
        val session = "display-old-platform-${UUID.randomUUID()}"
        val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
        val oldScope = lines.lines(MobileUseGrantStore.CONFIG_KEY)
        val oldRoute = lines.lines(MobileUseGrantStore.EXECUTION_KEY)

        val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
        val store = MobileUseGrantStore(lines::lines, lines::setLines, { null })
        val now = System.currentTimeMillis()
        c.storage.sessions.create(session, "Old platform synthetic display", null, null, now)
        c.storage.turns.start(session, session, now)
        try {
            check(c.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
            c.pluginService.setEnabled(plugin.id, true)
            store.configureGlobal(setOf("com.helix.agent.developer.test"), false)
            store.configureExecution(background = true, allowMoveFromMain = false)
            val refused =
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    c.pluginService.catalog.select(session, plugin.id, true)
                }
            assertEquals("MOBILE_USE_BACKEND_UNAVAILABLE", refused.message)
            val center = AutomationPermissionCenter(app)
            assertTrue(center.preferredBackend(AutomationDeviceOperation.OBSERVE) == null)
        } finally {
            lines.setLines(MobileUseGrantStore.CONFIG_KEY, oldScope)
            lines.setLines(MobileUseGrantStore.EXECUTION_KEY, oldRoute)
            c.storage.deleteSessionPermanently(session)
            c.pluginService.setEnabled(plugin.id, plugin.enabled)
        }
    }
}

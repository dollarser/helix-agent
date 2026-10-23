package com.helix.smoke

import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Configuration preservation on an owned, test-signed same-APK reinstall; not a database migration claim. */
class ReleaseReinstallTest {
    @Test fun languageSelectionSurvivesOwnedReinstall() {
        val ui = ReleaseUiTestSupport()
        assertEquals(0, ui.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        when (InstrumentationRegistry.getArguments().getString("reinstallPhase")) {
            "setup" -> {
                ui.ready()
                ui.click("应用导航", "App navigation")
                ui.click("设置", "Settings")
                ui.click("通用", "General")
                ui.click("English")
                ui.await("App navigation")
            }

            "verify" -> {
                ui.launch()
                ui.await("App navigation")
                assertNull(ui.node("应用导航"))
                assertNull(ui.node("I understand, start using", "我已知晓，开始使用"))
            }

            else -> {
                error("Explicit reinstallPhase setup or verify required")
            }
        }
    }
}

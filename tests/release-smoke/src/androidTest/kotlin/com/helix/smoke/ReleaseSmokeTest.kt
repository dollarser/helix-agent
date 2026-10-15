package com.helix.smoke

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs in the independent test APK; it never shares the target's class loader or UID. */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    private val ui = ReleaseUiTestSupport()

    @Before fun requireIsolatedRelease() {
        check(ui.context.packageName.startsWith("com.helix.validation."))
        assertEquals(0, ui.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        ui.ready()
    }

    @Test fun modelSetupNavigationReturnsToConversation() {
        ui.click("应用导航", "App navigation")
        ui.click("模型", "Models")
        ui.await("API 服务", "API services", "API")
        ui.back()
        ui.await("应用导航", "App navigation")
    }

    @Test fun settingsAndAboutShowInstalledVersion() {
        ui.click("应用导航", "App navigation")
        ui.click("设置", "Settings")
        ui.click("通用", "General")
        ui.await("关于 Helix", "About Helix")
        val info = ui.context.packageManager.getPackageInfo(ui.context.packageName, 0)
        ui.await(
            "版本：${info.versionName} (${info.longVersionCode})",
            "Version: ${info.versionName} (${info.longVersionCode})",
        )
        assertFalse(info.versionName.isNullOrBlank())
    }
}

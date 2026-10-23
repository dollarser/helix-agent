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

    @Test fun offlineResourcesAreVisibleBeforeCloudConfiguration() {
        ui.click("应用导航", "App navigation")
        ui.click("设置", "Settings")
        ui.click("通用", "General")
        ui.scrollTo("语音服务", "Speech services")
        ui.click("语音服务", "Speech services")
        ui.await("离线语音识别", "Offline speech recognition")
        ui.scrollTo("离线回复朗读", "Offline speech playback")
        ui.await("离线回复朗读", "Offline speech playback")
        ui.scrollTo("云端语音 · 主动启用", "Cloud voice · explicitly enabled")
        assertFalse("Cloud endpoint fields must stay hidden until enabled", ui.nodes().any { it.isEditable })
        ui.click(
            "使用配置的云端语音服务（关闭时使用现有离线语音）",
            "Use configured cloud speech services (off uses existing offline speech)",
        )
        ui.scrollTo("ASR 转写完整地址", "Full ASR transcription URL")
        ui.await("ASR 转写完整地址", "Full ASR transcription URL")
    }

    @Test fun settingsAndAboutShowInstalledVersion() {
        ui.click("应用导航", "App navigation")
        ui.click("设置", "Settings")
        ui.click("通用", "General")
        ui.scrollTo("关于 Helix", "About Helix")
        ui.await("关于 Helix", "About Helix")
        val info = ui.context.packageManager.getPackageInfo(ui.context.packageName, 0)
        val versionLabels =
            arrayOf(
                "版本：${info.versionName} (${info.longVersionCode})",
                "Version: ${info.versionName} (${info.longVersionCode})",
            )
        ui.scrollTo(*versionLabels)
        ui.await(*versionLabels)
        assertFalse(info.versionName.isNullOrBlank())
    }
}

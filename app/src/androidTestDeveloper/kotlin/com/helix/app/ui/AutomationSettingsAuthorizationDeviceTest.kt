package com.helix.app.ui

import android.app.UiAutomation
import android.provider.Settings
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.core.model.SafetyProfile
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AutomationSettingsAuthorizationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod")
    fun userGrantIsVisibleAndDoesNotCarryIntoTheNextSession() {
        compose.resetDeterministicUiState()
        val app = compose.activity.applicationContext
        val center = AutomationPermissionCenter(app)
        val automation =
            InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

        fun shell(command: String) {
            automation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor
                    .AutoCloseInputStream(descriptor)
                    .use { it.readBytes() }
            }
        }
        val services = Settings.Secure.getString(app.contentResolver, "enabled_accessibility_services")
        val enabled = Settings.Secure.getInt(app.contentResolver, "accessibility_enabled", 0)
        val allowlist = center.allowlistedPackages()
        val profile = compose.container().profileStore.profile
        try {
            val component = "${app.packageName}/com.helix.tools.automation.HelixAccessibilityService"
            val all = (services.orEmpty().split(':').filter(String::isNotBlank) + component).distinct()
            shell("settings put secure enabled_accessibility_services ${all.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            compose.waitUntil(15_000) { center.serviceState() == AutomationServiceState.CONNECTED }
            compose.container().profileStore.switchTo(SafetyProfile.ADVANCED)
            compose.navigateTo("settings/permissions")
            val grant = compose.onNodeWithTag("automation-system-settings")
            grant.performScrollTo().assertIsOff()
            compose
                .onNodeWithTag("automation-packages")
                .performScrollTo()
                .performTextReplacement("${app.packageName},com.android.settings,com.android.systemui")
            grant.performScrollTo().performClick().assertIsOn()
            compose.onNodeWithTag("automation-start").performScrollTo().performClick()
            compose.waitUntil(5_000) { center.activeSession() != null }
            assertTrue(center.activeSession()!!.allowSystemSettings)
            assertTrue(
                center
                    .activeSession()!!
                    .scope.allowedPackages
                    .containsAll(center.systemSettingsPackages()),
            )
            repeat(10) {
                center.performNodeAction(
                    com.helix.tools.automation.AutomationNodeActionRequest(
                        com.helix.tools.automation.AutomationNodeAction.CLICK,
                        "00000000000000000000000000000000",
                    ),
                )
            }
            compose.waitUntil(5_000) { center.pauseReason() != null }
            compose.onNodeWithTag("automation-resume-target").performScrollTo().performTextReplacement(app.packageName)
            compose.onNodeWithTag("automation-resume").performScrollTo().performClick()
            compose.waitUntil(5_000) { center.pauseReason() == null }

            grant.performScrollTo().assertIsOn()
            compose.onNodeWithTag("automation-stop").performScrollTo().performClick()
            compose.waitUntil(5_000) { center.activeSession() == null }
            grant.performScrollTo().assertIsOff()
            compose.onNodeWithTag("automation-start").performScrollTo().performClick()
            compose.waitUntil(5_000) { center.activeSession() != null }
            assertFalse(center.activeSession()!!.allowSystemSettings)
        } finally {
            center.stopSession()
            center.replaceAllowlist(allowlist)
            compose.container().profileStore.switchTo(profile)
            if (services.isNullOrBlank()) {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $services")
            }
            shell("settings put secure accessibility_enabled $enabled")
        }
    }
}

package com.helix.app.ui

import android.app.UiAutomation
import android.provider.Settings
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.core.model.SafetyProfile
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AutomationSettingsAuthorizationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun chooseApplications(vararg packages: String) {
        compose.onNodeWithTag("automation-select-apps").performScrollTo().performClick()
        packages.forEach { name ->
            compose.onNodeWithTag("automation-app-search").performTextReplacement(name)
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasTestTag("automation-app-loading")).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("automation-app-list").performScrollToNode(hasTestTag("automation-app-$name"))
            compose.onNodeWithTag("automation-app-$name").performClick()
        }
        compose.onNodeWithTag("automation-app-confirm").performClick()
    }

    @Test
    @Suppress("LongMethod")
    fun selectedApplicationsDefineTheExactGrantAndWholePhoneIsExplicit() {
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
        compose.waitUntil(5_000) {
            compose
                .container()
                .chatService.screen.value.openSessionId != null
        }
        val conversation =
            requireNotNull(
                compose
                    .container()
                    .chatService.screen.value.openSessionId,
            )
        val profile = compose.container().profileStore.profile
        try {
            val component = "${app.packageName}/com.helix.tools.automation.HelixAccessibilityService"
            val all = (services.orEmpty().split(':').filter(String::isNotBlank) + component).distinct()
            shell("settings put secure enabled_accessibility_services ${all.joinToString(":")}")
            shell("settings put secure accessibility_enabled 1")
            compose.waitUntil(15_000) { center.serviceState() == AutomationServiceState.CONNECTED }
            center.revokeConversation(conversation)
            compose.container().profileStore.switchTo(SafetyProfile.ADVANCED)
            compose.navigateTo("settings/permissions")
            chooseApplications(app.packageName, "com.android.settings")
            compose.onNodeWithTag("automation-start").performScrollTo().performClick()
            compose.waitUntil(5_000) { center.conversationGrant(conversation) != null }
            val saved = requireNotNull(center.conversationGrant(conversation))
            assertEquals(setOf(app.packageName, "com.android.settings"), saved.scope.allowedPackages)
            val call =
                com.helix.tools.framework.ExecutableToolCall(
                    "ui-grant-check",
                    "ui.snapshot",
                    "4",
                    kotlinx.serialization.json.JsonObject(emptyMap()),
                    com.helix.core.model.ExecutionTargetType.LOCAL_ANDROID,
                    java.time.Instant
                        .now()
                        .plusSeconds(30),
                    com.helix.tools.framework.NoCancellation,
                    conversation,
                    "fixture",
                    saved.scope.toScopeRef(),
                )
            val port =
                com.helix.tools.automation
                    .PermissionCenterAutomationToolPort(center)
                    .forCall(call)
            port.snapshot()
            assertTrue(center.conversationRuntime(conversation)!!.allowSystemSettings)
            repeat(10) {
                port.nodeAction(
                    com.helix.tools.automation.AutomationNodeActionRequest(
                        com.helix.tools.automation.AutomationNodeAction.CLICK,
                        "00000000000000000000000000000000",
                    ),
                )
            }
            assertNull(center.pauseReason())
            assertEquals(saved, center.conversationGrant(conversation))
            assertNull(center.conversationGrant("another-conversation"))
            compose.onNodeWithTag("automation-stop").performScrollTo().performClick()
            compose.waitUntil(5_000) { center.conversationGrant(conversation) == null }
            chooseApplications("com.android.settings") // Deselect the previously checked system app.
            compose.onNodeWithTag("automation-start").performScrollTo().performClick()
            compose.waitUntil(5_000) { center.conversationGrant(conversation) != null }
            assertEquals(setOf(app.packageName), center.conversationGrant(conversation)!!.scope.allowedPackages)
            compose.onNodeWithTag("automation-stop").performScrollTo().performClick()
            compose.waitUntil(5_000) { center.conversationGrant(conversation) == null }
            compose
                .onNodeWithTag(
                    "automation-all-applications",
                ).performScrollTo()
                .assertIsOff()
                .performClick()
                .assertIsOn()
            compose.onNodeWithTag("automation-start").performScrollTo().performClick()
            compose.waitUntil(5_000) { center.conversationGrant(conversation) != null }
            val wholePhone = requireNotNull(center.conversationGrant(conversation))
            assertTrue(wholePhone.scope.allApplications)
            assertTrue(wholePhone.scope.allowedPackages.isEmpty())
            shell("settings put secure accessibility_enabled 0")
            compose.waitUntil(5_000) { center.serviceState() != AutomationServiceState.CONNECTED }
            assertEquals(wholePhone, center.conversationGrant(conversation))
            assertEquals(wholePhone, AutomationPermissionCenter(app).conversationGrant(conversation))
            compose.onNodeWithTag("automation-stop").performScrollTo().performClick()
            compose.waitUntil(5_000) { center.conversationGrant(conversation) == null }
        } finally {
            center.revokeConversation(conversation)
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

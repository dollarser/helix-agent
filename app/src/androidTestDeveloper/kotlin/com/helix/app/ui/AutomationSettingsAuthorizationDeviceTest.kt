package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.helix.app.automation.MobileUseSettings
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AutomationSettingsAuthorizationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pluginSettingsAreGlobalAndSavingDoesNotSelectAConversation() {
        val records = mutableMapOf<String, List<String>>()
        val selections = mutableMapOf<String, String>()
        val store =
            MobileUseGrantStore({ records[it].orEmpty() }, { key, value -> records[key] = value }, selections::get)
        var permissionsOpened = false
        compose.setContent {
            MaterialTheme {
                MobileUseSettings(ApplicationProvider.getApplicationContext(), store) { permissionsOpened = true }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("permission-root-authorize").assertDoesNotExist()
        compose.onNodeWithTag("permission-shizuku-manage").assertDoesNotExist()
        compose.onNodeWithTag("permission-accessibility").assertDoesNotExist()
        compose.onNodeWithTag("automation-all-applications").performScrollTo().assertIsOn()
        assertNull(store.globalConfiguration())
        compose.onNodeWithTag("automation-all-applications").performScrollTo().performClick()
        compose.onNodeWithTag("mobile-use-save-settings").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("automation-all-applications").performClick()
        compose.onNodeWithTag("mobile-use-save-settings").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("mobile-use-save-settings").performClick()
        compose.waitUntil(5_000) { store.globalConfiguration() != null }
        assertTrue(store.globalConfiguration()!!.scope.allApplications)
        assertNull(store.find("session"))
        assertFalse(permissionsOpened)
        compose.onNodeWithTag("mobile-use-open-permissions").performScrollTo().performClick()
        assertTrue(permissionsOpened)
        selections["session"] = "selected-1"
        assertTrue(store.find("session")!!.scope.allApplications)
        store.configureGlobal(setOf("com.example.allowed"), false)
        assertFalse(store.find("session")!!.scope.allApplications)
        assertTrue(store.find("session")!!.scope.permitsPackage("com.example.allowed"))
    }

    @Test fun failedSaveKeepsScopeEditableAndDoesNotGrantAccess() {
        val store = MobileUseGrantStore({ emptyList() }, { _, _ -> error("write failed") }, { null })
        compose.setContent {
            MaterialTheme {
                MobileUseSettings(ApplicationProvider.getApplicationContext(), store) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("automation-all-applications").performScrollTo().assertIsOn()
        compose.onNodeWithTag("mobile-use-save-settings").performClick()
        compose.waitUntil(
            5_000,
        ) { compose.onAllNodesWithTag("mobile-use-save-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mobile-use-save-settings").assertIsDisplayed().assertIsEnabled()
        assertNull(store.find("session"))
    }

    @Test fun existingAppScopeIsNotExpandedByOpeningSettings() {
        val records = mutableMapOf<String, List<String>>()
        val store = MobileUseGrantStore({ records[it].orEmpty() }, { key, value -> records[key] = value }, { null })
        store.configureGlobal(setOf("com.example.allowed"), false)
        compose.setContent {
            MaterialTheme { MobileUseSettings(ApplicationProvider.getApplicationContext(), store) {} }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("mobile-use-advanced").performClick()
        compose.onNodeWithTag("automation-all-applications").performScrollTo().assertIsOff()
        assertFalse(store.globalConfiguration()!!.scope.allApplications)
        assertTrue(store.globalConfiguration()!!.scope.permitsPackage("com.example.allowed"))
    }

    @Test fun changedScopeRequiresSaveOrDiscardBeforeLeaving() {
        val records = mutableMapOf<String, List<String>>()
        val store = MobileUseGrantStore({ records[it].orEmpty() }, { key, value -> records[key] = value }, { null })
        store.configureGlobal(setOf("com.example.allowed"), false)
        var left = false
        compose.setContent {
            MaterialTheme {
                MobileUseSettings(ApplicationProvider.getApplicationContext(), store, onBack = { left = true }) {}
            }
        }
        compose.onNodeWithTag("mobile-use-advanced").performClick()
        waitForScope()
        compose.onNodeWithTag("automation-all-applications").performScrollTo().performClick()
        androidx.test.espresso.Espresso
            .pressBack()
        compose.onNodeWithTag("mobile-use-save-leave").assertIsDisplayed()
        assertFalse(left)
        assertFalse(store.globalConfiguration()!!.scope.allApplications)
        compose.onNodeWithTag("mobile-use-save-leave").performClick()
        compose.waitUntil(5_000) { left }
        assertTrue(store.globalConfiguration()!!.scope.allApplications)
    }

    @Test fun loadingFailureOffersRetryAndDoesNotShowSaved() {
        val fail =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val store =
            MobileUseGrantStore({ if (fail.get()) error("read failure") else emptyList() }, { _, _ -> }, { null })
        compose.setContent {
            MaterialTheme { MobileUseSettings(ApplicationProvider.getApplicationContext(), store) {} }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mobile-use-reload").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mobile-use-advanced").performClick()
        compose.onNodeWithTag("mobile-use-save-settings").assertIsNotEnabled()
        fail.set(false)
        compose.onNodeWithTag("mobile-use-reload").performClick()
        waitForScope()
        compose.onNodeWithTag("mobile-use-save-settings").assertIsEnabled()
    }

    private fun waitForScope() {
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("automation-all-applications") and
                        androidx.compose.ui.test
                            .isEnabled(),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}

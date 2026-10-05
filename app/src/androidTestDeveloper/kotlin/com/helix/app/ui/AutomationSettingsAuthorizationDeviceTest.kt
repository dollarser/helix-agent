package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
        compose.onNodeWithTag("automation-all-applications").performClick()
        compose.onNodeWithTag("mobile-use-save-settings").performClick()
        compose.waitUntil(5_000) { store.globalConfiguration() != null }
        assertTrue(store.globalConfiguration()!!.scope.allApplications)
        assertNull(store.find("session"))
        assertFalse(permissionsOpened)
        compose.onNodeWithTag("mobile-use-open-permissions").performClick()
        assertTrue(permissionsOpened)
        selections["session"] = "selected-1"
        assertTrue(store.find("session")!!.scope.allApplications)
        store.configureGlobal(setOf("com.example.allowed"), false)
        assertFalse(store.find("session")!!.scope.allApplications)
        assertTrue(store.find("session")!!.scope.permitsPackage("com.example.allowed"))
    }
}

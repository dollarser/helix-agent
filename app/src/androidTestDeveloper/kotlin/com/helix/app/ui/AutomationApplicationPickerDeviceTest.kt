package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.helix.app.automation.AutomationApplicationPicker
import com.helix.extensions.mobileuse.automation.AutomationApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AutomationApplicationPickerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val user = AutomationApplication("com.example.chat", "Chat app", false, true, true)
    private val system = AutomationApplication("com.android.systemui", "System UI", true, false, true)
    private val apps = listOf(user, system)

    @Test fun searchAndSystemFilterKeepExistingSelectionUntilConfirm() {
        var result: Set<String>? = null
        compose.setContent {
            MaterialTheme {
                AutomationApplicationPicker(setOf(user.packageName), { apps }, { result = it }, {})
            }
        }
        awaitApp(user.packageName)
        compose.onNodeWithTag("automation-app-${user.packageName}").assertIsOn()
        compose.onNodeWithTag("automation-app-filter-SYSTEM").performClick()
        awaitApp(system.packageName)
        compose.onNodeWithTag("automation-app-${system.packageName}").performClick().assertIsOn()
        compose.onNodeWithTag("automation-app-filter-ALL").performClick()
        compose.onNodeWithTag("automation-app-search").performTextReplacement("com.example.chat")
        awaitApp(user.packageName)
        compose.onNodeWithTag("automation-app-${user.packageName}").assertIsOn()
        assertEquals(null, result)
        compose.onNodeWithTag("automation-app-confirm").performClick()
        assertEquals(setOf(user.packageName, system.packageName), result)
    }

    @Test fun cancelDoesNotApplyDraftSelection() {
        var confirmed = false
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                AutomationApplicationPicker(emptySet(), { apps }, { confirmed = true }, { cancelled = true })
            }
        }
        awaitApp(user.packageName)
        compose.onNodeWithTag("automation-app-${user.packageName}").assertIsOff().performClick()
        compose.onNodeWithTag("automation-app-cancel").performClick()
        assertTrue(cancelled)
        assertEquals(false, confirmed)
    }

    @Test fun recoveryPickerCannotSeeTargetsOutsideItsGrant() {
        compose.setContent {
            MaterialTheme {
                AutomationApplicationPicker(
                    emptySet(),
                    { apps },
                    {},
                    {},
                    permittedPackages = setOf(user.packageName),
                    singleSelection = true,
                )
            }
        }
        awaitApp(user.packageName)
        compose.onNodeWithTag("automation-app-${system.packageName}").assertDoesNotExist()
    }

    @Test fun aFailedCatalogRefreshDoesNotTrapAnExistingSelection() {
        var result: Set<String>? = null
        compose.setContent {
            MaterialTheme {
                AutomationApplicationPicker(
                    setOf(user.packageName),
                    { error("Catalog temporarily unavailable") },
                    { result = it },
                    {},
                )
            }
        }
        awaitApp(user.packageName)
        compose.onNodeWithTag("automation-app-confirm").performClick()
        assertEquals(setOf(user.packageName), result)
    }

    private fun awaitApp(packageName: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("automation-app-loading")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("automation-app-list").performScrollToNode(hasTestTag("automation-app-$packageName"))
    }
}

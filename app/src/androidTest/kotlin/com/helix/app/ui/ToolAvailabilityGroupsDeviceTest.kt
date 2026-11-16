package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ToolAvailabilityGroupsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun twoLevelExpansionDoesNotToggleToolsAndKeepsOtherGroupsClosed() {
        val toggled = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ToolAvailabilityGroups(listOf("browser_open", "browser_click", "read"), { it }, {
                    if (it ==
                        "read"
                    ) {
                        "files"
                    } else {
                        "browser"
                    }
                }) { name ->
                    TextButton({ toggled += name }, Modifier.testTag(name)) { Text(name) }
                }
            }
        }
        compose.onNodeWithTag("settings-perm-group-browser").assertDoesNotExist()
        compose.onNodeWithTag("browser_open").assertDoesNotExist()
        compose.onNodeWithTag("settings-perm-tools").performClick()
        compose.onNodeWithTag("browser_open").assertDoesNotExist()
        compose.onNodeWithTag("settings-perm-group-browser").performClick()
        compose.onNodeWithTag("browser_open").assertExists()
        compose.onNodeWithTag("read").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), toggled) }
        compose.onNodeWithTag("browser_open").performClick()
        compose.onNodeWithTag("settings-perm-tools").performClick()
        compose.onNodeWithTag("browser_open").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("browser_open"), toggled) }
    }
}

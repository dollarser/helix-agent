package com.helix.app.ui

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.deviceaccess.AccessibilityAuthorization
import com.helix.app.deviceaccess.RootAuthorization
import com.helix.app.deviceaccess.ShizukuAuthorization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class ShizukuSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemAuthorizationDoesNotContainOrConnectMobileUse() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        app.appContainer
        val mobile =
            com.helix.tools.deviceaccess.DeviceAccess
                .root("mobile-use")
        val before = mobile?.status()
        var opened: Intent? = null
        val context =
            object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) {
                    opened = intent
                }
            }
        compose.setContent {
            MaterialTheme {
                Column {
                    AccessibilityAuthorization(context)
                    RootAuthorization(context)
                    ShizukuAuthorization(context)
                }
            }
        }
        compose.onNodeWithTag("permission-accessibility-status").assertExists()
        compose.onNodeWithTag("permission-root-authorize-status").assertExists()
        compose.onNodeWithTag("permission-shizuku-manage-status").assertExists()
        compose.onNodeWithTag("mobile-use-backend-summary").assertDoesNotExist()
        compose.onNodeWithTag("automation-root-disconnect").assertDoesNotExist()
        compose.onNodeWithTag("automation-all-applications").assertDoesNotExist()
        assertEquals(before, mobile?.status())
        assertEquals(null, opened)
        compose.onNodeWithTag("permission-shizuku-manage").assertIsEnabled().performClick()
        assertNotNull(opened)
        val target = requireNotNull(opened).component
        assertEquals(true, target?.packageName in setOf(app.packageName, "moe.shizuku.privileged.api"))
    }
}

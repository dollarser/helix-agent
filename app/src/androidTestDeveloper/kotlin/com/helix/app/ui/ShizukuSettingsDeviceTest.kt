package com.helix.app.ui

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.automation.ShizukuSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class ShizukuSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectionSurfaceOpensOnlyTheExplicitUserDestination() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        app.appContainer
        var opened: Intent? = null
        val context =
            object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) {
                    opened = intent
                }
            }
        compose.setContent { MaterialTheme { ShizukuSettings(context) } }
        compose.onNodeWithTag("automation-shizuku-status").assertExists()
        compose.onNodeWithTag("automation-root-status").assertExists()
        val root =
            com.helix.app.automation.shizuku.MobileUseRootConnection
                .access()
        val canConnect =
            com.helix.app.automation
                .rootPermissionPresentation(
                    root?.status(),
                    root?.cachedAppGrant,
                ).canConnect
        compose.waitUntil(5_000) {
            val disabled =
                compose
                    .onNodeWithTag("automation-root-authorize")
                    .fetchSemanticsNode()
                    .config
                    .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            disabled != canConnect
        }
        compose.onNodeWithTag("automation-root-grant").assertExists()
        if (canConnect) {
            compose.onNodeWithTag("automation-root-authorize").assertIsEnabled()
        } else {
            compose.onNodeWithTag("automation-root-authorize").assertIsNotEnabled()
        }
        compose.onNodeWithTag("automation-root-disconnect").assertIsEnabled()
        assertEquals(null, opened)
        compose.onNodeWithTag("automation-shizuku-authorize").assertIsEnabled().performClick()
        assertNotNull(opened)
        val target = requireNotNull(opened).component
        assertEquals(true, target?.packageName in setOf(app.packageName, "moe.shizuku.privileged.api"))
    }
}

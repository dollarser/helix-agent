package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.helix.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SystemBarInsetsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun allDestinationHeadersStayBelowSystemBarsAfterNavigationAndRecreation() {
        compose.resetDeterministicUiState()
        repeat(2) { pass ->
            listOf("files", "browser", "extensions", "permissions", "settings", "audit").forEach { route ->
                compose.navigateTo(route)
                val header = compose.onNodeWithTag("shell-top-bar").fetchSemanticsNode().boundsInWindow
                var top = 0
                compose.runOnIdle {
                    top =
                        requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
                            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                            .top
                }
                println("Safe header: pass=$pass route=$route top=${header.top} systemTop=$top")
                assertTrue("$route header ${header.top} overlaps status inset $top", header.top >= top - 1f)
            }
            if (pass == 0) compose.activityRule.scenario.recreate()
        }
    }
}

package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiagnosticReportDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failureCanRetryAndPreviewNeverCopiesUntilExplicitAction() {
        var reads = 0
        lateinit var clipboard: ClipboardManager
        val report = "{\"format\":\"helix.process-diagnostics\",\"appVersion\":\"fixture\"}"
        compose.setContent {
            clipboard = LocalClipboardManager.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    DiagnosticReportSection {
                        reads++
                        if (reads == 1) error("synthetic private failure detail")
                        report
                    }
                }
            }
        }
        compose.runOnIdle {
            clipboard.setText(AnnotatedString("unchanged clipboard"))
            assertEquals(0, reads)
        }
        compose.onNodeWithTag("diagnostics-open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("diagnostics-retry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("diagnostics-preview").assertTextContains(report)
        compose.runOnIdle {
            assertEquals(2, reads)
            assertEquals("unchanged clipboard", clipboard.getText()?.text)
        }
        compose.onNodeWithTag("diagnostics-copy").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(report, clipboard.getText()?.text) }
        compose.onNodeWithTag("diagnostics-close").assertIsDisplayed().performClick()
        compose.onNodeWithTag("diagnostics-preview").assertDoesNotExist()
    }
}

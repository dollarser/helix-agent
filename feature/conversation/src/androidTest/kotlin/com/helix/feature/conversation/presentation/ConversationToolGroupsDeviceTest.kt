package com.helix.feature.conversation.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ToolCallState
import org.junit.Rule
import org.junit.Test
import java.io.File

class ConversationToolGroupsDeviceTest : ConversationDeviceTestFixture(usesClipboard = true) {
    @get:Rule val compose = createComposeRule()
    private val intents =
        ConversationIntents(
            {},
            {},
            onDismissBlocked = {},
            onApproveApproval = {},
            onDenyApproval = {},
            onStageAttachment = {},
            onRemoveAttachment = {},
            onSetMode = {},
            onInspectToolResult = {
                _,
                _,
                ->
                ToolInspectionContent(
                    "{\"path\":\"fixture.txt\"}",
                    "{\"result\":true}",
                )
            },
        )
    private val rows =
        (0 until 40).map { i ->
            ToolTimelineRow(
                "turn",
                "call-$i",
                "read",
                "{\"path\":\"fixture-$i.txt\"}",
                "调用完成",
                "synthetic result",
                null,
                position = ToolTimelinePosition(1, i),
                state = ToolCallState.COMPLETED,
            )
        }

    @Test fun groupRestoresExpansionAndRepeatedTapsNeverExecuteAnything() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                Column(Modifier.height(500.dp).verticalScroll(rememberScrollState())) {
                    ToolTimelineGroup(rows, intents, null)
                }
            }
        }
        compose.onNodeWithTag("tool-row-call-0").assertDoesNotExist()
        compose.onNodeWithTag("tool-group-toggle-call-0").performClick()
        compose.onNodeWithTag("tool-row-call-0").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tool-row-call-0").assertIsDisplayed()
        compose.onNodeWithTag("tool-group-toggle-call-0").performClick().performClick()
        compose.onNodeWithTag("tool-row-call-0").assertIsDisplayed()
        screenshot("expanded-restored")
    }

    @Test fun searchHitAtEndOfLongGroupBecomesVisibleWithoutManualExpansion() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                    ToolTimelineGroup(rows, intents, "call-39")
                }
            }
        }
        compose.onNodeWithTag("tool-row-call-39").assertIsDisplayed()
    }

    @Test fun darkNarrowLargeTextKeepsFailuresUnknownAndObservedRouteVisible() {
        render(dark = true, scale = 1.6f)
        compose.onNodeWithTag("tool-group-toggle-call-0").assertIsDisplayed()
        compose.onNodeWithTag("tool-row-state-failed", true).assertIsDisplayed()
        compose.onNodeWithTag("tool-row-state-unknown", true).assertIsDisplayed()
        compose.onNodeWithTag("tool-row-state-approval", true).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag(
                "tool-route-display",
                true,
            ).performScrollTo()
            .assertTextContains("com.example.fixture", true)
        screenshot("dark-narrow-large")
    }

    @Test fun lightInspectorFormatsAndCopiesPresentationWithoutDispatch() {
        render(dark = false, scale = 1f)
        compose.onNodeWithTag("tool-details-failed").performClick()
        compose.onNodeWithTag("tool-inspector-failed").performScrollTo().performClick()
        compose.onNodeWithTag("tool-inspector-copy").assertIsDisplayed()
        screenshot("light-inspector")
        compose.onNodeWithTag("tool-inspector-copy").performClick()
    }

    private fun render(
        dark: Boolean,
        scale: Float,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Column(
                        Modifier
                            .width(320.dp)
                            .height(600.dp)
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ToolTimelineGroup(rows, intents, null)
                        listOf(
                            "failed" to ToolCallState.FAILED,
                            "unknown" to ToolCallState.NEEDS_REVIEW,
                            "approval" to ToolCallState.AWAITING_APPROVAL,
                            "cancelled" to ToolCallState.CANCELLED,
                        ).forEach { (id, state) ->
                            ToolTimelineItem(
                                rows.first().copy(callId = id, state = state, stateLabel = state.name),
                                intents,
                            )
                        }
                        ToolTimelineItem(
                            rows.first().copy(
                                callId = "display",
                                toolName = "ui.device",
                                resultSummary =
                                    """{
"displayId":7,"executionDisplay":"background",
"packageName":"com.example.fixture"
}""",
                            ),
                            intents,
                        )
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "conversation-ui").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

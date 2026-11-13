package com.helix.feature.conversation.presentation

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ToolCallState
import com.helix.feature.conversation.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.helix.ui.shared.R as SharedR

@RunWith(Parameterized::class)
class ToolDisclosureDeviceTest(
    private val state: ToolCallState,
) : ConversationDeviceTestFixture() {
    @get:Rule val compose = createComposeRule()

    @Test fun disclosureDirectionAndAccessibleStateFollowRepeatedTogglesAndRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme(colorScheme = lightColorScheme(onSurfaceVariant = Color.Black)) {
                Surface(color = Color.White, modifier = Modifier.width(320.dp)) {
                    ToolTimelineItem(
                        ToolTimelineRow(
                            "turn",
                            "call",
                            "read",
                            """{"path":"fixture.txt"}""",
                            state.name,
                            "content",
                            null,
                            state = state,
                        ),
                        ConversationIntents(
                            onSend = {},
                            onStop = {},
                            onDismissBlocked = {},
                            onApproveApproval = {},
                            onDenyApproval = {},
                            onStageAttachment = {},
                            onRemoveAttachment = {},
                            onSetMode = {},
                        ),
                    )
                }
            }
        }
        repeat(2) {
            assertDisclosure(expanded = false)
            compose.onNodeWithTag("tool-details-call").performClick()
            assertDisclosure(expanded = true)
            restoration.emulateSavedInstanceStateRestore()
            assertDisclosure(expanded = true)
            compose.onNodeWithTag("tool-details-call").performClick()
        }
        restoration.emulateSavedInstanceStateRestore()
        assertDisclosure(expanded = false)
    }

    private fun assertDisclosure(expanded: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stateLabel = context.getString(if (expanded) SharedR.string.nav_expanded else SharedR.string.nav_collapsed)
        val expectedState = if (state == ToolCallState.COMPLETED) "$stateLabel · ${state.name}" else stateLabel
        val summary = compose.onNodeWithTag("tool-details-call")
        summary.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        summary.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expectedState))
        assertEquals(
            context.getString(if (expanded) R.string.tool_details_hide else R.string.tool_details_show),
            summary.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )
        val details = compose.onNodeWithTag("tool-row-args-call")
        if (expanded) details.assertExists() else details.assertDoesNotExist()
        val icon = compose.onNodeWithTag("tool-chevron-call", useUnmergedTree = true)
        icon.assertHasNoClickAction()
        icon.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        val pixels = icon.captureToImage().toPixelMap()
        val ink =
            buildList {
                for (y in 0 until pixels.height) {
                    for (x in 0 until pixels.width) {
                        if (pixels[x, y].red < 0.5f) add(x to y)
                    }
                }
            }
        assertTrue("The disclosure must render visible ink", ink.isNotEmpty())
        // Check the actual rendered silhouette, not a test-only direction/rotation property.
        val along = if (expanded) ink.map { it.second } else ink.map { it.first }
        val across = if (expanded) ink.map { it.first } else ink.map { it.second }
        val depth = along.max() - along.min()
        val width = across.max() - across.min()
        assertTrue("Expected a ${if (expanded) "down" else "right"} chevron", width > depth * 1.3f)
        val arms = ink.filterIndexed { index, _ -> along[index] <= along.min() + depth / 3 }
        val tip = ink.filterIndexed { index, _ -> along[index] >= along.max() - depth / 3 }

        fun span(points: List<Pair<Int, Int>>): Int {
            val values = points.map { if (expanded) it.first else it.second }
            return values.max() - values.min()
        }
        assertTrue("The chevron tip must point ${if (expanded) "down" else "right"}", span(arms) > span(tip) * 1.3f)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun states(): List<Array<ToolCallState>> = ToolCallState.entries.map { arrayOf(it) }
    }
}

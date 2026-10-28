package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.helix.app.chat.ToolTimelineRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolTimelineLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longScopedPathKeepsFilenameAndExpandedOriginalArguments() {
        val path = "scope:fixture-workspace:" + "directory/".repeat(20) + "报告.txt"
        val args = """{"path":"$path"}"""
        val intent = "Read $path to verify its content"
        render(ToolTimelineRow("turn", "call", "read", args, "已执行成功", "content", null, modelIntent = intent))
        compose
            .onNodeWithTag("tool-row-intent-call", useUnmergedTree = true)
            .assertTextContains("报告.txt", substring = true)
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag("tool-row-intent-call", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("Directory leaked into the collapsed title", "directory/" !in layout.layoutInput.text.text)
        assertTrue("Scope leaked into the collapsed title", "scope:" !in layout.layoutInput.text.text)
        val filenameEnd =
            layout.layoutInput.text.text
                .indexOf("报告.txt") + "报告.txt".length
        assertTrue(
            "Filename is hidden by the collapsed title ellipsis",
            filenameEnd <= layout.getLineEnd(layout.lineCount - 1, true),
        )
        compose.onNodeWithTag("tool-details-call").performClick()
        compose.onNodeWithTag("tool-row-intent-detail-call").assertTextContains(intent, substring = true)
        verifyExpansion("tool-row-args-call", args)
    }

    @Test fun longToolNameKeepsItsStateVisibleOnANarrowScreen() {
        render(
            ToolTimelineRow("turn", "call", "mcp.documents.search_workspace_records", "{}", "执行失败", "No match", null),
        )
        val row = compose.onNodeWithTag("tool-row-call").getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag("tool-row-state-call", useUnmergedTree = true)
        status.assertIsDisplayed()
        val bounds = status.getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= row.left && bounds.right <= row.right)
    }

    @Test fun collapsedToolsKeepTouchTargetsWithoutExtraVerticalWhitespace() {
        render(ToolTimelineRow("turn", "call", "read", "{}", "已执行成功", "content", null), fontScale = 1f)
        val row = compose.onNodeWithTag("tool-row-call").getUnclippedBoundsInRoot()
        val summary = compose.onNodeWithTag("tool-details-call").getUnclippedBoundsInRoot()
        assertTrue("The tool disclosure must remain easy to tap", (summary.bottom - summary.top) >= 48.dp)
        assertTrue(
            "Collapsed tools must not add a second layer of vertical spacing",
            (row.bottom - row.top) - (summary.bottom - summary.top) <= 4.dp,
        )
        compose.onNodeWithTag("tool-details-call").performClick()
        compose.onNodeWithTag("tool-row-result-call").assertTextContains("content", substring = true)
    }

    @Test fun longArgumentsAndResultsCanBeExpandedWithoutLosingTheirContent() {
        val args = "{\"path\":\"workspace/long-document.txt\"}\n".repeat(20)
        val result = "A long tool result with its original content.\n".repeat(20)
        render(ToolTimelineRow("turn", "call", "read", args, "已执行成功", result, null))
        compose.onNodeWithTag("tool-row-args-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-result-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-details-call").performClick()
        verifyExpansion("tool-row-args-call", args)
        verifyExpansion("tool-row-result-call", result)
    }

    @Test
    fun detailsAreHiddenWhileHarnessStateRemainsVisible() {
        val intent = "检查构建失败原因"
        render(
            ToolTimelineRow(
                turnId = "turn",
                callId = "call",
                toolName = "bash",
                requestSummary = """{"command":["./gradlew","test"]}""",
                stateLabel = "需要审查",
                resultSummary = "effect uncertain",
                card = null,
                modelIntent = intent,
                durationMs = 400,
                prootRecoveryAvailable = true,
            ),
        )

        compose.onNodeWithTag("tool-row-name-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-intent-detail-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-inline-preview-call").assertDoesNotExist()
        compose.onNodeWithTag("command-detail-call").assertDoesNotExist()
        compose.onNodeWithTag("proot-query-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-state-call", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("tool-row-duration-call", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("tool-details-call").performClick()
        compose.onNodeWithTag("tool-row-name-call").assertExists()
        compose.onNodeWithTag("tool-row-intent-detail-call").assertTextContains(intent, substring = true)
        compose.onNodeWithTag("tool-row-status-detail-call").assertTextContains("需要审查", substring = true)
        compose.onNodeWithTag("tool-row-duration-call", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("tool-row-call-id-detail-call").assertTextContains("call", substring = true)
        compose.onNodeWithTag("tool-row-args-call").assertTextContains("./gradlew", substring = true)
        compose.onNodeWithTag("tool-row-result-call").assertTextContains("effect uncertain", substring = true)
        compose.onNodeWithTag("command-detail-call").assertExists()
        compose.onNodeWithTag("proot-query-call").assertExists()
        compose.onNodeWithTag("tool-details-call").performScrollTo().performClick()
        compose.onNodeWithTag("tool-row-name-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-intent-detail-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-args-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-result-call").assertDoesNotExist()
        compose.onNodeWithTag("command-detail-call").assertDoesNotExist()
        compose.onNodeWithTag("proot-query-call").assertDoesNotExist()
    }

    @Test fun modelIntentFits320dpAtLargeFont() = assertIntentLayout(320)

    @Test fun modelIntentFits360dpAtLargeFont() = assertIntentLayout(360)

    @Test fun modelIntentFits412dpAtLargeFont() = assertIntentLayout(412)

    @Test fun failureRemainsVisibleBesideModelIntent() {
        render(
            ToolTimelineRow(
                "turn",
                "call",
                "write",
                "{}",
                "执行失败",
                "executor failure",
                null,
                modelIntent = "修改发布配置",
            ),
        )
        compose.onNodeWithTag("tool-row-name-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-intent-detail-call").assertDoesNotExist()
        compose
            .onNodeWithTag("tool-row-state-call", useUnmergedTree = true)
            .assertIsDisplayed()
            .onChild()
            .assertTextContains("执行失败")
        compose.onNodeWithTag("tool-details-call").performClick()
        compose.onNodeWithTag("tool-row-result-call").assertTextContains("executor failure", substring = true)
    }

    @Test fun deniedStatusRemainsVisibleBesideModelIntent() {
        render(
            ToolTimelineRow(
                "turn",
                "call",
                "write",
                "{}",
                "已拒绝",
                "permission denied",
                null,
                modelIntent = "修改发布配置",
            ),
        )
        compose
            .onNodeWithTag("tool-row-state-call", useUnmergedTree = true)
            .assertIsDisplayed()
            .onChild()
            .assertTextContains("已拒绝")
    }

    @Test fun summaryAndChevronOnlyTogglePresentation() {
        var actions = 0
        val intents =
            timelineIntents().copy(
                onSend = { actions++ },
                onStop = { actions++ },
                onApproveApproval = { actions++ },
                onDenyApproval = { actions++ },
            )
        render(ToolTimelineRow("turn", "call", "read", "{}", "执行中", null, null), intents = intents)
        val title = compose.onNodeWithTag("tool-row-intent-call", useUnmergedTree = true)
        val chevron = compose.onNodeWithTag("tool-chevron-call", useUnmergedTree = true)
        val iconBounds = chevron.getUnclippedBoundsInRoot()
        val titleBounds = title.getUnclippedBoundsInRoot()
        assertTrue("Disclosure must sit immediately before the title", iconBounds.right <= titleBounds.left)
        assertTrue("Disclosure gap must stay compact", titleBounds.left - iconBounds.right <= 8.dp)
        chevron.assertHasNoClickAction()
        compose.onNodeWithTag("tool-row-args-call").assertDoesNotExist()
        title.performClick()
        compose.onNodeWithTag("tool-row-args-call").assertExists()
        chevron.performScrollTo().performClick()
        compose.onNodeWithTag("tool-row-args-call").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    private fun assertIntentLayout(widthDp: Int) {
        val intent = "Inspect the current isolated execution summary"
        render(
            ToolTimelineRow(
                "turn",
                "call",
                "mcp.documents.search_workspace_records",
                "{}",
                "执行中",
                null,
                null,
                modelIntent = intent,
            ),
            widthDp = widthDp,
            fontScale = 2f,
        )
        val row = compose.onNodeWithTag("tool-row-call").getUnclippedBoundsInRoot()
        compose
            .onNodeWithTag(
                "tool-row-intent-call",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            .assertTextContains(intent)
        compose.onNodeWithTag("tool-row-name-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-intent-detail-call").assertDoesNotExist()
        compose.onNodeWithTag("tool-row-state-call", useUnmergedTree = true).assertIsDisplayed()
        val details = compose.onNodeWithTag("tool-details-call")
        details.assertIsDisplayed().assertHasClickAction()
        val bounds = details.getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= row.left && bounds.right <= row.right)
    }

    private fun verifyExpansion(
        tag: String,
        text: String,
    ) {
        val node = compose.onNodeWithTag(tag)
        val before = node.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithTag("$tag-toggle").performScrollTo().performClick()
        node.assertTextContains(text, substring = true)
        val after = node.getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue(after > before)
        compose.onNodeWithTag("$tag-toggle").performScrollTo().performClick()
    }

    private fun render(
        row: ToolTimelineRow,
        widthDp: Int = 240,
        fontScale: Float = 1.8f,
        intents: ConversationIntents = timelineIntents(),
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(widthDp.dp).verticalScroll(rememberScrollState())) {
                        ToolTimelineItem(row, intents)
                    }
                }
            }
        }
    }
}

private fun timelineIntents() =
    ConversationIntents(
        onSend = {},
        onStop = {},
        onDismissBlocked = {},
        onApproveApproval = {},
        onDenyApproval = {},
        onStageAttachment = {},
        onRemoveAttachment = {},
        onSetMode = {},
    )

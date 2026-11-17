package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic announcement; browser and clipboard callbacks are intercepted, never real account notices. */
class AccountNoticeLinksDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun webLinkNavigatesOnlyOnClickAndBothCopyActionsPreserveExactText() {
        val body = "Plain <b>notice</b> https://example.test/path?q=1&x=2.\nSecond line."
        val opened = mutableListOf<String>()
        val copied = mutableListOf<String>()
        compose.setContent { MaterialTheme { AccountNoticeBody(body, opened::add, copied::add) } }
        assertEquals(emptyList<String>(), opened)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("account-notice-body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        val point = layouts.single().getBoundingBox(body.indexOf("https") + 2).center
        compose.onNodeWithTag("account-notice-body").performTouchInput { click(point) }
        assertEquals(listOf("https://example.test/path?q=1&x=2"), opened)
        compose.onNodeWithTag("notice-copy-link-menu").performClick()
        compose.onNodeWithTag("notice-copy-link-0").performClick()
        compose.onNodeWithTag("notice-copy-body").performClick()
        assertEquals(listOf("https://example.test/path?q=1&x=2", body), copied)
        assertEquals(1, opened.size)
    }

    @Test fun nonWebSchemesRemainTextWithoutLinkActions() {
        compose.setContent {
            MaterialTheme {
                AccountNoticeBody(
                    "javascript:alert(1) file:///private intent://app <b>text</b>",
                    { error("No navigation") },
                )
            }
        }
        compose.onNodeWithTag("notice-copy-link-menu").assertDoesNotExist()
        compose.onNodeWithTag("notice-copy-body").assertExists()
    }
}

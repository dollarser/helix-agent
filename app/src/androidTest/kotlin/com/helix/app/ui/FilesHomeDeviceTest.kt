package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.helix.app.MainActivity
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FilesHomeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun managesLocalFilesWithoutAModelOrConversation() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        deleteEditableProviders(container)
        val root = compose.activity.filesDir.resolve("workspaces/app/work")
        val folder = root.resolve("standalone-check")
        folder.mkdirs()
        folder.resolve("alpha.txt").writeText("local file")
        folder.resolve("beta.txt").writeText("other file")
        try {
            compose.navigateTo("files")
            compose.onNodeWithTag("files-quick-work").assertIsDisplayed().performClick()
            compose.waitUntil {
                compose
                    .onAllNodesWithTag(
                        "files-entry-standalone-check",
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("files-entry-standalone-check").performScrollTo().performClick()
            compose.waitUntil { compose.onAllNodesWithTag("files-entry-alpha.txt").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("files-search-open").performClick()
            compose.onNodeWithTag("files-search-query").performTextReplacement("ALPHA")
            compose.onNodeWithTag("files-entry-alpha.txt").assertIsDisplayed()
            compose.onNodeWithTag("files-entry-beta.txt").assertDoesNotExist()
            compose.onNodeWithTag("files-entry-alpha.txt").performTouchInput { longClick() }
            compose.onNodeWithTag("files-batch-copy").assertIsDisplayed()
            compose.onNodeWithTag("files-batch-clear").performScrollTo().performClick()
            compose.onNodeWithTag("files-search-open").performClick()
            compose.onNodeWithTag("files-entry-beta.txt").assertIsDisplayed()
            compose.onNodeWithTag("files-controls-open").performClick()
            compose.onNodeWithTag("files-newfolder").performScrollTo().performClick()
            compose.onNodeWithTag("files-newfolder-field").performTextReplacement("Created without AI")
            compose.onNodeWithTag("files-newfolder-confirm").performClick()
            compose.waitUntil { folder.resolve("Created without AI").isDirectory }
            assertTrue(folder.resolve("Created without AI").isDirectory)
            assertNull(container.chatService.screen.value.openSessionId)
            compose.onNodeWithTag("files-home-open").performClick()
            compose.onNodeWithTag("files-home-source-app").assertIsDisplayed()
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun systemBackReturnsToParentThenHome() {
        compose.resetDeterministicUiState()
        compose.navigateTo("files")
        compose.onNodeWithTag("files-quick-work").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil { compose.onAllNodesWithTag("files-entry-work").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("files-entry-work").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("files-home-source-app").assertIsDisplayed()
    }
}

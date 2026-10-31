package com.helix.app.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** One owned synthetic workspace file; never resets settings, conversations or other workspace files. */
class SvgFilePreviewDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualScopedFilePreviewShowsPassiveSvgThenSourceWithoutChangingTheFile() {
        require(compose.activity.packageName == "com.helix.agent.developer.asrvalidation")
        val name = "helix-svg-preview-${UUID.randomUUID()}.svg"
        val file = File(compose.activity.filesDir, "workspaces/app/work/$name")
        val text =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 160 90\">" +
                "<rect width=\"160\" height=\"90\" fill=\"#2277dd\"/>" +
                "<text x=\"10\" y=\"50\" fill=\"white\">合成文件</text></svg>"
        check(!file.exists())
        file.parentFile?.mkdirs()
        file.writeText(text)
        try {
            compose.dismissFirstLaunchIfNeeded()
            compose.navigateTo("files")
            compose.onNodeWithTag("files-home-source-app").performScrollTo().performClick()
            compose.waitUntil(
                10_000,
            ) { compose.onAllNodes(hasTestTag("files-entry-work")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("files-entry-work").performClick()
            compose.waitUntil(
                10_000,
            ) { compose.onAllNodes(hasTestTag("files-entry-$name")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("files-entry-$name").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("inline-diagram-image")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("inline-diagram-toggle").performClick()
            assertEquals(text, file.readText())
            assertEquals(1, InlineDiagramCache.renderer.availablePermits)
        } finally {
            assertTrue("Only the owned synthetic fixture is removed", file.delete())
        }
    }
}

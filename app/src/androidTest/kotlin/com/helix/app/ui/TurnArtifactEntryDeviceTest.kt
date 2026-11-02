package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.core.storage.content.FileContentStore
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class TurnArtifactEntryDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun replyEntryOpensOnlyItsOriginalTurnArtifacts() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        val session = "artifact-entry-${UUID.randomUUID()}"
        val turn = "turn-$session"
        val root = File(app.filesDir, "workspaces/app/output/$session").also { it.mkdirs() }
        c.storage.sessions.create(session, "Synthetic artifact entry", null, null, 1)
        c.storage.turns.start(turn, session, 2)
        try {
            for ((id, owner) in listOf("mine" to turn, "other" to null)) {
                val file = File(root, "$id.txt").also { it.writeText("Synthetic $id") }
                c.storage.artifacts.registerOrRefresh(
                    "$session-$id",
                    session,
                    "scope:app:output/$session/$id.txt",
                    "text/plain",
                    file.length(),
                    FileContentStore.sha256Hex(file),
                    owner,
                    file,
                )
            }
            compose.setContent { MaterialTheme { TurnArtifactEntry(c.chatService, c.fileManager, turn) } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("turn-artifacts-$turn").fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNodeWithTag("turn-artifacts-$turn")
                .assertTextContains("mine.txt", substring = true)
                .performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("artifact-file-row-$session-mine").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("artifact-file-row-$session-other").assertDoesNotExist()
            compose.onNodeWithTag("artifact-file-row-$session-mine").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("artifact-file-preview", true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("artifact-file-preview", true).assertTextContains("Synthetic mine", substring = true)
        } finally {
            c.storage.deleteSessionPermanently(session)
            root.deleteRecursively()
        }
    }
}

package com.helix.app.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.helix.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The P0-B Git status / diff / changed-files page (doc section 29): a JGit-initialized repository
 * one level below the workspace root shows its staged / unstaged / untracked changes and branch
 * header, and opens a unified diff for a file. The page reads the on-device repository with JGit
 * (no PRoot runtime required), so this runs in the consumer flavor.
 */
class GitStatusDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun unreadableRepositoryShowsConciseFailureWithoutExceptionOrPrivatePath() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val session = "git-error-${java.util.UUID.randomUUID()}"
            container.storage.sessions.create(session, "Synthetic Git error", null, null, System.currentTimeMillis())
            container.chatService.openSession(session)
            compose.waitUntil(10_000) { container.chatService.screen.value.openSessionId == session }
            val binding = requireNotNull(container.storage.workspaces.binding(session))
            val root =
                container.storage.workspaces
                    .managedDirectory(binding.workspaceId)
                    .toFile()
            Git
                .init()
                .setDirectory(root)
                .call()
                .close()
            File(root, ".git/index").writeBytes(byteArrayOf(1, 2, 3, 4))
            compose.navigateTo("git")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("git-error").fetchSemanticsNodes().isNotEmpty()
            }
            val message = compose.activity.getString(com.helix.app.R.string.git_error_title)
            compose.onNodeWithTag("git-error").assertTextEquals(message)
            Unit
        }

    @Test
    fun gitPageShowsStagedUnstagedUntrackedAndOpensDiff() {
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val session = "git-fixture-${java.util.UUID.randomUUID()}"
            container.storage.sessions.create(session, "Git fixture", null, null, System.currentTimeMillis())
            container.chatService.openSession(session)
            compose.waitUntil(10_000) { container.chatService.screen.value.openSessionId == session }
            val binding = requireNotNull(container.storage.workspaces.binding(session))
            val workspaceRoot =
                container.storage.workspaces
                    .managedDirectory(binding.workspaceId)
                    .toFile()
            File(workspaceRoot, "demo").deleteRecursively() // fresh repo on every run
            val repoDir = File(workspaceRoot, "demo").apply { mkdirs() }
            val git = Git.init().setDirectory(repoDir).call()
            File(repoDir, "a.txt").writeText("one\n")
            File(repoDir, "b.txt").writeText("B\n")
            git
                .add()
                .addFilepattern("a.txt")
                .addFilepattern("b.txt")
                .call()
            git
                .commit()
                .setMessage("init")
                .setAuthor("t", "t@example.com")
                .setCommitter("t", "t@example.com")
                .call()
            File(repoDir, "a.txt").writeText("one\ntwo\n") // unstaged modification
            File(repoDir, "c.txt").writeText("C\n") // staged addition
            git.add().addFilepattern("c.txt").call()
            File(repoDir, "d.txt").writeText("D\n") // untracked

            compose.navigateTo("git")
            compose.onNodeWithTag("screen-git").assertExists()
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithTag("git-change-path-a.txt", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            // The repo sits one level below the workspace root, so it is named "demo".
            compose.onNodeWithTag("git-repo", useUnmergedTree = true).assertTextContains("demo", substring = true)
            compose.onNodeWithTag("git-change-path-c.txt", useUnmergedTree = true).assertExists()
            scrollToChange("d.txt")
            compose.onNodeWithTag("git-change-path-d.txt", useUnmergedTree = true).assertExists()

            // Open the diff for the unstaged-modified a.txt: the working-tree diff shows the add.
            scrollToChange("a.txt")
            compose.onNodeWithTag("git-change-row-a.txt").performClick()
            compose.waitUntil(5_000) {
                compose
                    .onAllNodesWithTag("git-diff", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag("git-diff", useUnmergedTree = true).assertTextContains("two", substring = true)

            // Dismissing the diff returns cleanly to the list.
            compose.onNodeWithTag("git-diff-close", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("screen-git").assertExists()
        }
    }

    private fun scrollToChange(path: String) {
        compose
            .onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("screen-git")))
            .performScrollToNode(hasTestTag("git-change-row-$path"))
    }
}

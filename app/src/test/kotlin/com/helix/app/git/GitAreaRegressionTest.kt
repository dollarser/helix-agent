package com.helix.app.git

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitAreaRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun samePathHasIndependentStagedAndUnstagedDiffs() {
        val root = tmp.newFolder()
        Git.init().setDirectory(root).call().use { git ->
            val file = File(root, "a.txt")
            file.writeText("one\n")
            git.add().addFilepattern("a.txt").call()
            git
                .commit()
                .setMessage("initial")
                .setAuthor("test", "test@example.com")
                .call()
            file.writeText("two\n")
            git.add().addFilepattern("a.txt").call()
            file.writeText("three\n")
            val reader = GitWorkspaceReader(root)
            assertTrue(reader.diffFor("a.txt", GitChangeArea.STAGED).contains("+two"))
            assertFalse(reader.diffFor("a.txt", GitChangeArea.STAGED).contains("three"))
            assertTrue(reader.diffFor("a.txt", GitChangeArea.UNSTAGED).contains("+three"))
            assertFalse(reader.diffFor("a.txt", GitChangeArea.UNSTAGED).contains("one"))
        }
    }

    @Test fun unbornHeadShowsIndexAndSubsequentWorktreeEdit() {
        val root = tmp.newFolder()
        Git.init().setDirectory(root).call().use { git ->
            val file = File(root, "a.txt")
            file.writeText("staged\n")
            git.add().addFilepattern("a.txt").call()
            val reader = GitWorkspaceReader(root)
            val first = reader.readStatus() as GitWorkspaceResult.Ready
            assertEquals(1, first.status.staged.size)
            assertTrue(first.status.unstaged.isEmpty())
            assertTrue(reader.diffFor("a.txt", GitChangeArea.STAGED).contains("+staged"))
            file.writeText("later\n")
            val second = reader.readStatus() as GitWorkspaceResult.Ready
            assertEquals(1, second.status.staged.size)
            assertEquals(1, second.status.unstaged.size)
            assertTrue(reader.diffFor("a.txt", GitChangeArea.UNSTAGED).contains("+later"))
        }
    }

    @Test fun unreadableRepositoryAndMissingObjectAreErrorsNotEmptyDiffs() {
        val root = tmp.newFolder()
        val reader = GitWorkspaceReader(root)
        assertEquals(GitDiffResult.Error, reader.readDiff("a.txt", GitChangeArea.STAGED))
        Git.init().setDirectory(root).call().use { git ->
            File(root, "a.txt").writeText("body\n")
            git.add().addFilepattern("a.txt").call()
            val id =
                git.repository
                    .readDirCache()
                    .getEntry("a.txt")
                    .objectId.name
            assertTrue(File(root, ".git/objects/${id.take(2)}/${id.drop(2)}").delete())
            assertEquals(GitDiffResult.Error, reader.readDiff("a.txt", GitChangeArea.STAGED))
        }
    }

    @Test fun stagedLargeAndBinaryFilesUseTheSameBoundsAsWorktree() {
        val root = tmp.newFolder()
        Git.init().setDirectory(root).call().use { git ->
            File(root, "big").writeText("x".repeat(1024 * 1024 + 1))
            File(root, "binary").writeBytes(byteArrayOf(0, 1, 2))
            git.add().addFilepattern(".").call()
            val reader = GitWorkspaceReader(root)
            assertTrue(reader.diffFor("big", GitChangeArea.STAGED).contains("per-side limit"))
            assertTrue(reader.diffFor("binary", GitChangeArea.STAGED).contains("Binary files"))
        }
    }
}

package com.helix.app.git

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM unit tests for the JGit-backed [GitWorkspaceReader] (P0-B "Git status / diff / changed
 * files"): a real on-disk repository is built with JGit in a temp dir, and the reader's
 * staged / unstaged / untracked mapping, the non-repo state, the depth-1 repo location and the
 * per-file diff are asserted.
 */
class GitWorkspaceReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Init a repo at [dir] with an initial commit of a.txt ("one") and b.txt ("B"). */
    private fun initRepo(dir: File): Git {
        val git = Git.init().setDirectory(dir).call()
        File(dir, "a.txt").writeText("one\n")
        File(dir, "b.txt").writeText("B\n")
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
        return git
    }

    @Test
    fun `an empty directory is not a repository`() {
        val root = tmp.newFolder("ws")
        assertEquals(GitWorkspaceResult.NotARepository, GitWorkspaceReader(root).readStatus())
    }

    @Test
    fun `a clean repository at the workspace root is located and read`() {
        val root = tmp.newFolder("ws")
        initRepo(root)
        val ready = GitWorkspaceReader(root).readStatus() as GitWorkspaceResult.Ready
        assertTrue(ready.status.isClean)
    }

    @Test
    fun `a repository one level below the workspace root is located`() {
        val root = tmp.newFolder("ws")
        val repoDir = File(root, "myrepo").apply { mkdirs() }
        initRepo(repoDir)
        val ready = GitWorkspaceReader(root).readStatus() as GitWorkspaceResult.Ready
        assertEquals("myrepo", ready.repoName)
    }

    @Test
    fun `staged, unstaged and untracked changes are separated`() {
        val root = tmp.newFolder("ws")
        val git = initRepo(root)

        File(root, "a.txt").writeText("one\ntwo\n") // unstaged modification
        File(root, "c.txt").writeText("C\n") // staged addition
        git.add().addFilepattern("c.txt").call()
        File(root, "d.txt").writeText("D\n") // untracked

        val status = (GitWorkspaceReader(root).readStatus() as GitWorkspaceResult.Ready).status
        assertEquals(listOf(GitChange("c.txt", null, GitChangeKind.ADDED, GitChangeArea.STAGED)), status.staged)
        assertEquals(listOf(GitChange("a.txt", null, GitChangeKind.MODIFIED, GitChangeArea.UNSTAGED)), status.unstaged)
        assertEquals(listOf(GitChange("d.txt", null, GitChangeKind.ADDED, GitChangeArea.UNTRACKED)), status.untracked)
        assertFalse(status.isClean)
    }

    @Test
    fun `diffFor returns the working-tree diff for a changed file`() {
        val root = tmp.newFolder("ws")
        initRepo(root)
        File(root, "a.txt").writeText("one\ntwo\n")
        val diff = GitWorkspaceReader(root).diffFor("a.txt")
        assertTrue(diff.contains("two"))
        assertTrue(diff.contains("a.txt"))
    }

    @Test
    fun `diffFor is empty for an untracked file`() {
        val root = tmp.newFolder("ws")
        initRepo(root)
        File(root, "d.txt").writeText("D\n")
        assertEquals("", GitWorkspaceReader(root).diffFor("d.txt"))
    }
}

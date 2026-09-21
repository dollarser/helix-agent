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
    fun `configured filters cannot execute in root or nested directory reads`() {
        val root = tmp.newFolder("hostile")
        initRepo(root).use { git ->
            File(root, "nested").mkdirs()
            File(root, "nested/data.txt").writeText("before\n")
            git.add().addFilepattern("nested/data.txt").call()
            git
                .commit()
                .setMessage("nested")
                .setAuthor("t", "t@example.com")
                .setCommitter("t", "t@example.com")
                .call()
            File(root, "a.txt").writeText("one\nchanged\n")
            File(root, "nested/data.txt").writeText("before\nchanged\n")
            File(root, ".gitattributes").writeText("*.txt filter=hostile\n")
            git.repository.config.apply {
                setString("filter", "hostile", "clean", "echo invoked > executed; cat")
                setString("filter", "hostile", "smudge", "echo invoked > executed; cat")
                setBoolean("filter", "hostile", "required", true)
                save()
            }
            val config = File(root, ".git/config").readText()
            val reader = GitWorkspaceReader(root)
            val status = reader.readStatus()
            assertTrue("Unexpected status: $status", status is GitWorkspaceResult.Ready)
            assertTrue(reader.diffFor("a.txt").contains("+changed"))
            assertTrue(reader.diffFor("nested/data.txt").contains("+changed"))
            assertFalse(File(root, "executed").exists())
            assertEquals(config, File(root, ".git/config").readText())
        }
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
    fun `diffFor renders a git-style unified diff`() {
        val root = tmp.newFolder("ws")
        initRepo(root)
        File(root, "a.txt").writeText("one\nTWO\nthree\n")
        val expected =
            "diff --git a/a.txt b/a.txt\n" +
                "--- a/a.txt\n" +
                "+++ b/a.txt\n" +
                "@@ -1,1 +1,3 @@\n" +
                " one\n" +
                "+TWO\n" +
                "+three\n"
        assertEquals(expected, GitWorkspaceReader(root).diffFor("a.txt"))
    }

    @Test
    fun `diffFor never writes to the object database`() {
        val root = tmp.newFolder("ws")
        initRepo(root)
        File(root, "a.txt").writeText("one\ntwo\n")
        val objectsDir = File(root, ".git/objects")
        val before =
            objectsDir
                .walkTopDown()
                .filter { it.isFile }
                .map { it.path }
                .toList()
                .sorted()
        val diff = GitWorkspaceReader(root).diffFor("a.txt")
        assertTrue(diff.contains("+two"))
        val after =
            objectsDir
                .walkTopDown()
                .filter { it.isFile }
                .map { it.path }
                .toList()
                .sorted()
        assertEquals(before, after)
    }

    @Test
    fun `diffFor falls back to the staged diff when the worktree is clean`() {
        val root = tmp.newFolder("ws")
        val git = initRepo(root)
        File(root, "a.txt").writeText("one\nstaged-line\n")
        git.add().addFilepattern("a.txt").call()
        val diff = GitWorkspaceReader(root).diffFor("a.txt")
        assertTrue(diff.contains("+staged-line"))
    }

    @Test
    fun `diffFor shows a worktree deletion against dev-null`() {
        val root = tmp.newFolder("ws")
        initRepo(root)
        File(root, "b.txt").delete()
        val diff = GitWorkspaceReader(root).diffFor("b.txt")
        assertTrue(diff.contains("+++ /dev/null"))
        assertTrue(diff.contains("-B"))
    }

    @Test
    fun `diffFor names binary content instead of rendering it`() {
        val root = tmp.newFolder("ws")
        val git = initRepo(root)
        File(root, "img.bin").writeBytes(byteArrayOf(0, 1, 2))
        git
            .add()
            .addFilepattern("img.bin")
            .call()
        git
            .commit()
            .setMessage("bin")
            .setAuthor("t", "t@example.com")
            .setCommitter("t", "t@example.com")
            .call()
        File(root, "img.bin").writeBytes(byteArrayOf(0, 1, 2, 3))
        assertEquals(
            "Binary files a/img.bin and b/img.bin differ\n",
            GitWorkspaceReader(root).diffFor("img.bin"),
        )
    }

    @Test
    fun `diffFor reports a too-large file honestly instead of diffing it`() {
        val root = tmp.newFolder("ws")
        val git = initRepo(root)
        File(root, "big.txt").writeText("small\n")
        git
            .add()
            .addFilepattern("big.txt")
            .call()
        git
            .commit()
            .setMessage("big")
            .setAuthor("t", "t@example.com")
            .setCommitter("t", "t@example.com")
            .call()
        File(root, "big.txt").writeText("x".repeat(1024 * 1024 + 1))
        assertTrue(GitWorkspaceReader(root).diffFor("big.txt").contains("per-side limit"))
    }

    @Test
    fun `diffFor reports a too-many-lines file honestly instead of diffing it`() {
        val root = tmp.newFolder("ws")
        val git = initRepo(root)
        File(root, "lines.txt").writeText("small\n")
        git
            .add()
            .addFilepattern("lines.txt")
            .call()
        git
            .commit()
            .setMessage("lines")
            .setAuthor("t", "t@example.com")
            .setCommitter("t", "t@example.com")
            .call()
        File(root, "lines.txt").writeText("line\n".repeat(50_001))
        assertTrue(GitWorkspaceReader(root).diffFor("lines.txt").contains("line limit"))
    }

    @Test
    fun `diffFor shows a lost trailing newline as a last-line change`() {
        val root = tmp.newFolder("ws")
        initRepo(root)
        File(root, "a.txt").writeBytes("one".toByteArray(Charsets.UTF_8))
        val diff = GitWorkspaceReader(root).diffFor("a.txt")
        assertTrue(diff.contains("-one"))
        assertTrue(diff.contains("+one"))
        assertTrue(diff.contains("No newline at end of file"))
    }

    @Test
    fun `diffFor is empty for an untracked file`() {
        val root = tmp.newFolder("ws")
        initRepo(root)
        File(root, "d.txt").writeText("D\n")
        assertEquals("", GitWorkspaceReader(root).diffFor("d.txt"))
    }
}

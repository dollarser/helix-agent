package com.helix.app.ui

import androidx.test.core.app.ApplicationProvider
import com.helix.app.git.GitWorkspaceReader
import com.helix.app.git.GitWorkspaceResult
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/** Actual status/diff against hostile repository configuration; no remote Git operation. */
class GitReadOnlyBoundaryDeviceTest {
    @Test
    fun statusAndDiffDoNotExecuteConfiguredFiltersHooksOrTransport() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "git-boundary-${System.nanoTime()}").apply { mkdirs() }
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 250
                seed(root, listener.localPort)
                val reader = GitWorkspaceReader(root)
                assertTrue("Status must remain usable", reader.readStatus() is GitWorkspaceResult.Ready)
                assertTrue("Working tree diff must remain usable", reader.diffFor("a.txt").contains("changed"))
                assertFalse("Repository configured command executed", File(root, "executed").exists())
                assertNoConnection(listener)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertNoConnection(listener: ServerSocket) {
        try {
            listener.accept().use { error("Read-only status/diff initiated a network connection") }
        } catch (_: SocketTimeoutException) {
            // The live loopback listener received no connection during the completed reads.
        }
    }

    internal fun seed(
        root: File,
        port: Int,
    ) {
        Git.init().setDirectory(root).call().use { git ->
            File(root, "a.txt").writeText("original\n")
            git.add().addFilepattern("a.txt").call()
            git
                .commit()
                .setMessage("fixture")
                .setAuthor("fixture", "fixture@example.test")
                .setCommitter("fixture", "fixture@example.test")
                .call()
            File(root, "a.txt").writeText("original\nchanged\n")
            File(root, ".gitattributes").writeText("*.txt filter=hostile diff=hostile\n")
            val command = "echo invoked > '${File(root, "executed").absolutePath}'; cat"
            git.repository.config.apply {
                setString("filter", "hostile", "clean", command)
                setString("filter", "hostile", "smudge", command)
                setString("filter", "hostile", "process", command)
                setBoolean("filter", "hostile", "required", true)
                setString("diff", "hostile", "command", command)
                setString("core", null, "sshCommand", command)
                setString("remote", "origin", "url", "http://127.0.0.1:$port/hostile.git")
                setString("core", null, "hooksPath", File(root, "hooks").absolutePath)
                save()
            }
            File(root, "hooks").mkdirs()
            File(root, "hooks/post-checkout").apply {
                writeText("#!/system/bin/sh\n$command\n")
                setExecutable(true)
            }
        }
    }
}

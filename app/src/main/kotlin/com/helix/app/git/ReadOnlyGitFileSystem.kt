package com.helix.app.git

import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FS_POSIX
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A repository-local FS, not a global JGit override; Android's viewer never launches Git or hooks. */
internal class ReadOnlyGitFileSystem : FS_POSIX() {
    override fun newInstance(): FS = ReadOnlyGitFileSystem()

    override fun discoverGitExe(): File? = null

    override fun discoverGitSystemConfig(): File? = null

    override fun runInShell(
        command: String,
        args: Array<out String>,
    ): ProcessBuilder = throw UnsupportedOperationException("Commands are unavailable in the read-only Git viewer")

    override fun runProcess(
        builder: ProcessBuilder,
        output: OutputStream?,
        error: OutputStream?,
        input: String?,
    ): Int = throw UnsupportedOperationException("Processes are unavailable in the read-only Git viewer")

    override fun runProcess(
        builder: ProcessBuilder,
        output: OutputStream?,
        error: OutputStream?,
        input: InputStream?,
    ): Int = throw UnsupportedOperationException("Processes are unavailable in the read-only Git viewer")

    override fun execute(
        builder: ProcessBuilder,
        input: InputStream?,
    ): FS.ExecutionResult = throw UnsupportedOperationException("Processes are unavailable in the read-only Git viewer")
}

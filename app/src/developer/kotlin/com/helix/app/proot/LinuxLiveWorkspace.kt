package com.helix.app.proot

import com.helix.core.workspace.FileScopePath
import com.helix.runtime.proot.core.JobPath
import com.helix.runtime.proot.core.LiveJobWorkspace
import java.io.File

/** Resolve only the approval-bound resource, never the session's later selected directory. */
internal class LinuxLiveWorkspace(
    private val resolve: (FileScopePath) -> File,
    private val allowedRoots: List<File>,
) {
    @Suppress("TooGenericExceptionCaught") // All preparation precedes submission; failure never reports execution.
    fun prepareResult(call: LinuxRunTool.ParsedLinuxCall): LinuxRunTool.ParsedResult =
        try {
            LinuxRunTool.ParsedResult.Ok(prepare(call))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LinuxRunTool.ParsedResult.ParseFailure(
                "Workspace unavailable or unsupported for in-place execution; check the bound directory and paths. " +
                    "Use explicit snapshot mode for non-POSIX sources. Nothing was submitted.",
            )
        }

    fun prepare(call: LinuxRunTool.ParsedLinuxCall): LinuxRunTool.ParsedLinuxCall {
        if (call.snapshot) {
            require(call.artifactPaths.isEmpty()) { "Snapshot deliverables use output/" }
            return call
        }
        require(
            call.outputReference == null,
        ) { "In-place execution keeps files in place; use artifacts, not output import" }
        val reference =
            FileScopePath.fromModelReference(
                requireNotNull(call.workspaceReference) { "Workspace required" },
            )
        val root = resolve(reference).canonicalFile
        require(allowedRoots.any { it.path == it.canonicalPath && root.toPath().startsWith(it.toPath()) }) {
            "This workspace has no supported local POSIX mapping; explicitly select snapshot mode"
        }
        val witness = LiveJobWorkspace.witness(root)
        if (call.cwd.isNotEmpty()) require(LiveJobWorkspace.resolve(root, call.cwd).isDirectory) { "cwd unavailable" }
        call.inputReferences.forEach { input ->
            val path = FileScopePath.fromModelReference(input)
            require(path.scopeId == reference.scopeId && within(reference.relativePath, path.relativePath)) {
                "Input is outside the mounted workspace; copy it explicitly or select snapshot mode"
            }
        }
        require(call.artifactPaths.size <= 64 && call.artifactPaths.distinct().size == call.artifactPaths.size)
        call.artifactPaths.forEach { path ->
            JobPath.validate(path)
            require(path.split('/').none { it == ".helix" }) { "Internal files cannot be artifacts" }
            LiveJobWorkspace.resolve(root, path)
        }
        return call.copy(resolvedWorkspacePath = root.path, workspaceWitness = witness)
    }

    private fun within(
        root: String,
        path: String,
    ): Boolean = root.isEmpty() || path == root || path.startsWith("$root/")
}

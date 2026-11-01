package com.helix.app.proot

import com.helix.core.workspace.FileScopePath
import com.helix.runtime.proot.core.JobPath
import com.helix.runtime.proot.core.LiveJobWorkspace
import java.io.File

/** Resolve only the approval-bound resource, never the session's later selected directory. */
internal class LinuxLiveWorkspace(
    private val resolve: (FileScopePath) -> File,
    private val allowedRoots: () -> List<File>,
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
                    "Native file tools still access document sources in place. " +
                    "Only choose explicit snapshot mode when CLI needs it. Nothing was submitted or copied.",
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
        require(allowedRoots().any { it.path == it.canonicalPath && root.toPath().startsWith(it.toPath()) }) {
            "This workspace has no supported local POSIX mapping; explicitly select snapshot mode"
        }
        java.nio.file.Files
            .newDirectoryStream(root.toPath())
            .use { it.iterator().hasNext() }
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

/** Re-resolve only the frozen reference, never silently adopt another path or a replacement inode. */
internal fun samePreparedWorkspace(
    original: LinuxRunTool.ParsedLinuxCall,
    prepare: (LinuxRunTool.ParsedLinuxCall) -> LinuxRunTool.ParsedResult,
): Boolean {
    val refreshed = (prepare(original) as? LinuxRunTool.ParsedResult.Ok)?.call ?: return false
    return refreshed.resolvedWorkspacePath == original.resolvedWorkspacePath &&
        refreshed.workspaceWitness == original.workspaceWitness
}

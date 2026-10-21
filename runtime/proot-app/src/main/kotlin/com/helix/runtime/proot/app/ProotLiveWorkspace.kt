package com.helix.runtime.proot.app

import android.content.Context
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.LiveJobWorkspace
import com.helix.runtime.proot.ipc.ProotJobSpec
import java.io.File

/** Defense in depth: private IPC cannot turn an arbitrary absolute path into a mounted workspace. */
internal object ProotLiveWorkspace {
    fun resolve(
        context: Context,
        spec: ProotJobSpec,
    ): File? {
        val path = spec.workspacePath ?: return null
        val workspace = File(path)
        val allowed = listOf("app", "managed").map { File(context.filesDir.canonicalFile, "workspaces/$it") }
        require(allowed.any { it.path == it.canonicalPath && workspace.toPath().startsWith(it.toPath()) }) {
            "Unsupported workspace mount"
        }
        LiveJobWorkspace.verify(workspace, requireNotNull(spec.workspaceWitness))
        if (spec.relativeWorkingDirectory.isNotEmpty()) {
            require(LiveJobWorkspace.resolve(workspace, spec.relativeWorkingDirectory).isDirectory)
        }
        return workspace
    }

    fun manifest(
        context: Context,
        spec: ProotJobSpec,
        target: File,
        isCancelled: () -> Boolean,
    ): File {
        val workspace = requireNotNull(resolve(context, spec))
        val deadline = System.nanoTime() + 10_000_000_000L
        val entries =
            spec.artifactPaths.sorted().mapNotNull { relative ->
                if (isCancelled()) {
                    null
                } else {
                    try {
                        LiveJobWorkspace.observeAvailable(workspace, relative, deadline, isCancelled)
                    } catch (cancelled: java.io.InterruptedIOException) {
                        if (!isCancelled()) throw cancelled
                        null // Stop artifact hashing, but still deliver the original command's logs.
                    }
                }
            }
        target.writeText(JobManifestCodec.encode(JobManifest(entries)))
        return target
    }
}

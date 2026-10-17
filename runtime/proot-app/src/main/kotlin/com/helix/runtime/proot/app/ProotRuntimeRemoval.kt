package com.helix.runtime.proot.app

import android.content.Context
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.RuntimeActivation
import com.helix.runtime.proot.core.RuntimeTreeDeletion
import java.io.File

/**
 * The complete-removal action (roadmap HXA-087 完整删除; doc 10: 卸载删除 RootFS、
 * 凭据和临时文件，但不误删 Workspace).
 *
 * Scoping is the security property: only the runtime state root (`filesDir/runtime`
 * — version dirs + `state/`, per architecture doc section 6.3) may be deleted. The
 * path name is re-checked before ANY deletion (fail closed) and the deletion never
 * follows symlinks outside it. Workspace files live outside this subtree in the
 * same app package. Job journals, evidence and temp dirs under the runtime root
 * are removed with it.
 */
object ProotRuntimeRemoval {
    /** The only directory name this routine may ever delete (defense against a wrong root). */
    private const val RUNTIME_DIR_NAME = "runtime"

    /** Outcome of a removal: what was found and what is left. */
    data class Result(
        val removed: Boolean,
        val hadActive: Boolean,
        val activeInstallId: String?,
    )

    /**
     * Deletes the whole runtime state tree. [runtimeRoot] must be a directory named
     * `runtime` (the adapter always passes `filesDir/runtime`); anything else is a
     * programming error and is refused, not deleted. An already absent root is successful;
     * a partially deleted tree remains a failure.
     */
    fun remove(runtimeRoot: File): Result {
        require(runtimeRoot.name == RUNTIME_DIR_NAME) {
            "refusing to delete a directory that is not the runtime state root: ${runtimeRoot.name}"
        }
        val active: RuntimeActivation? =
            runCatching { RootFsInstaller.currentActive(runtimeRoot) }.getOrNull()
        return Result(
            removed = RuntimeTreeDeletion.remove(runtimeRoot),
            hadActive = active != null,
            activeInstallId = active?.installId,
        )
    }

    /** The Android adapter root: the companion's `filesDir/runtime` (section 6.3). */
    fun runtimeRootOf(context: Context): File = ProotRuntimeInstaller.runtimeRoot(context)
}

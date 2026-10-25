package com.helix.tools.files

import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.framework.ToolRegistry

object GlobTool {
    fun descriptor() = SearchToolContract.descriptor(content = false)

    fun executor(store: WorkspaceArtifactStore) = SearchToolContract.executor(store, content = false)

    fun register(
        registry: ToolRegistry,
        store: WorkspaceArtifactStore,
    ) = registry.register(descriptor(), executor(store))
}

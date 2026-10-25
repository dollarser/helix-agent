package com.helix.tools.files

import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.framework.ToolRegistry

object GrepTool {
    fun descriptor() = SearchToolContract.descriptor(content = true)

    fun executor(store: WorkspaceArtifactStore) = SearchToolContract.executor(store, content = true)

    fun register(
        registry: ToolRegistry,
        store: WorkspaceArtifactStore,
    ) = registry.register(descriptor(), executor(store))
}

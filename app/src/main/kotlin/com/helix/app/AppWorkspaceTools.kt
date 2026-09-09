package com.helix.app

import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.files.EditTool
import com.helix.tools.files.FilesArchiveTool
import com.helix.tools.files.FilesCopyTool
import com.helix.tools.files.FilesDeleteTool
import com.helix.tools.files.FilesExtractTool
import com.helix.tools.files.FilesListTool
import com.helix.tools.files.FilesMkdirTool
import com.helix.tools.files.FilesMoveTool
import com.helix.tools.files.FilesSearchTool
import com.helix.tools.files.FilesStatTool
import com.helix.tools.files.ReadTool
import com.helix.tools.files.WriteTool
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry

internal object AppWorkspaceTools {
    fun register(
        toolRegistry: ToolRegistry,
        toolImplementations: ToolImplementationRegistry,
        workspaceStore: WorkspaceArtifactStore,
    ) {
        // HXA-042: the first non-time.now business tools enter the production tool table. The
        // contractHash gate (ContractHashGateTest / ADR-0011) is the mechanical proof that a
        // security-descriptor change invalidates any approval minted for the old contract.
        ReadTool.register(toolRegistry, toolImplementations, workspaceStore)
        WriteTool.register(toolRegistry, toolImplementations, workspaceStore)
        EditTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesListTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesSearchTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesStatTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesMkdirTool.register(toolRegistry, toolImplementations, workspaceStore)
        // HXA-043: explicit conflict policy (copy/move refuse an existing destination without
        // overwrite) and delete-into-trash; restore and purge stay store seams, not model
        // tools.
        FilesCopyTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesMoveTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesDeleteTool.register(toolRegistry, toolImplementations, workspaceStore)
        // HXA-047: restricted zip/tar create + extract. The format codec and the Zip Slip /
        // expansion / entry-type defenses are shared; the tools only admit scope + region and
        // route containment/quota through the store. Archive writes into work/ only.
        FilesArchiveTool.register(toolRegistry, toolImplementations, workspaceStore)
        FilesExtractTool.register(toolRegistry, toolImplementations, workspaceStore)
    }
}

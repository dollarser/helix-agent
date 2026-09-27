package com.helix.app.chat

import com.helix.core.agent.ProjectInstructions
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ReadWindow

/** Bounded request-bound instructions through the same Path/document backend as file tools. */
internal fun readProjectInstructionsText(
    scope: FileScopePath,
    read: (FileScopePath, Long, Long) -> ReadWindow,
): String =
    ProjectInstructions.render(
        ProjectInstructions.DISCOVERY_ORDER.mapNotNull { name ->
            val relative = listOf(scope.relativePath, name).filter(String::isNotEmpty).joinToString("/")
            runCatching {
                read(FileScopePath(scope.scopeId, relative), 0, MAX_INSTRUCTION_BYTES).text?.let {
                    ProjectInstructions.Source(name, it)
                }
            }.getOrNull()
        },
    )

private const val MAX_INSTRUCTION_BYTES = 64_000L

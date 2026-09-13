package com.helix.app.chat

import com.helix.core.agent.ProjectInstructions
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.resolveFileScopePath
import java.nio.file.Files
import java.nio.file.Path

/**
 * P1 (research doc section 8) — the thin app-layer seam between the pure [ProjectInstructions]
 * core and the governed workspace read. Resolves the session's `directoryRef` (a `scope:` model
 * reference) and, in [ProjectInstructions.DISCOVERY_ORDER], reads the instruction files present
 * at the workspace root (AGENTS.md / CLAUDE.md / HELIX.md) through the SAME containment- and
 * symlink-enforced [resolveFileScopePath] the file tools use — no raw path is ever read directly.
 * Every failure (no workspace, a revoked/unresolvable scope, a missing or unreadable file)
 * degrades to "": a missing instruction file is never an error, the model just gets no project
 * section. Reads are bounded to [MAX_INSTRUCTION_BYTES]; the core then re-bounds the text.
 */
internal fun readProjectInstructionsText(
    storage: HelixStorage,
    rootResolver: ScopeRootResolver,
    sessionId: String,
): String =
    runCatching {
        val reference = storage.sessions.resolve(sessionId).directoryRef ?: return@runCatching ""
        val scope = FileScopePath.fromModelReference(reference)
        ProjectInstructions.render(
            ProjectInstructions.DISCOVERY_ORDER.mapNotNull { name -> sourceFor(scope, name, rootResolver) },
        )
    }.getOrDefault("")

/** Reads one candidate instruction file through the governed scope boundary; null when absent. */
private fun sourceFor(
    scope: FileScopePath,
    name: String,
    rootResolver: ScopeRootResolver,
): ProjectInstructions.Source? =
    runCatching {
        val relative = if (scope.relativePath.isEmpty()) name else "${scope.relativePath}/$name"
        val real: Path = resolveFileScopePath(FileScopePath(scope.scopeId, relative), rootResolver)
        if (Files.isRegularFile(real)) {
            val bytes = Files.newInputStream(real).use { it.readNBytes(MAX_INSTRUCTION_BYTES) }
            ProjectInstructions.Source(name, bytes.decodeToString())
        } else {
            null
        }
    }.getOrNull()

private const val MAX_INSTRUCTION_BYTES = 64_000

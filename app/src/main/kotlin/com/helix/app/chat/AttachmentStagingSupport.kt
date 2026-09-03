package com.helix.app.chat

import com.helix.core.workspace.FileScopePath
import com.helix.feature.files.AttachmentImporter
import com.helix.feature.files.SafSourceMetadata
import java.nio.file.Path

/**
 * The production seams for chat-attachment staging (HXA-049, ADR-0014): the one-time
 * private SAF import through the EXISTING pipeline ([AttachmentImporter] over the shared
 * [com.helix.feature.files.SafImportPipeline]), the source-metadata reader, the workspace
 * scope id the attachments are pinned into, and the containment-enforced scope-path
 * resolver.
 *
 * Trust note: [resolveWorkspacePath] returns a REAL filesystem path. It is consumed
 * ONLY inside the chat service for hashing / re-materialization of staged attachments;
 * the value must never be copied into UI state, logs, audit rows or model context
 * (those carry the scope-relative path instead — doc 10).
 */
class AttachmentStagingSupport(
    val importer: AttachmentImporter,
    val workspaceScopeId: String,
    val sourceMetadata: (String) -> SafSourceMetadata,
    val resolveWorkspacePath: (FileScopePath) -> Path,
)

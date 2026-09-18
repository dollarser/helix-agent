package com.helix.app.proot

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.WorkspaceLayout
import com.helix.runtime.proot.core.ZipJobExtractor
import java.io.File

/** Shared verified result.txt import for synchronous execution and original detached-result collection. */
internal object LinuxOutputImport {
    fun read(
        extraction: ZipJobExtractor.Extraction,
        expectedManifestSha: String,
        extractedDir: File,
    ): Pair<ByteArray, String>? {
        val file = File(extractedDir, "result.txt")
        if (!file.isFile || file.length() > LinuxRunTool.MAX_IMPORT_BYTES) return null
        val bytes = file.readBytes()
        val sha = LinuxRunTool.sha256Hex(bytes)
        val entry = extraction.manifest.entries.firstOrNull { it.path == "result.txt" }
        return if (extraction.manifestSha256 == expectedManifestSha && entry?.sha256 == sha &&
            entry.size == bytes.size.toLong()
        ) {
            bytes to sha
        } else {
            null
        }
    }

    fun write(
        store: WorkspaceArtifactStore,
        reference: String,
        imported: Pair<ByteArray, String>,
    ) {
        val path = FileScopePath.fromModelReference(reference)
        val region =
            path.relativePath
                .split('/')
                .firstOrNull()
                ?.takeIf { it in WorkspaceLayout.regions }
                ?: throw IllegalArgumentException("output has no workspace region")
        store.writeArtifact(path, imported.first, region)
    }
}

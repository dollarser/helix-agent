package com.helix.app.proot

import com.helix.app.proot.LinuxRunTool.MAX_IMPORT_BYTES
import com.helix.app.proot.LinuxRunTool.sha256Hex
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.core.JobArchiveException
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** Builds the bounded input archive through the scoped store; never submits a job. */
internal class LinuxInputSnapshot(
    private val store: WorkspaceArtifactStore,
) {
    /**
     * Builds the bounded input zip from the model references (read through the store);
     * returns the manifest SHA-256. Basenames are deduplicated inside the archive (the
     * Workspace directory structure is not revealed to the guest).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    fun build(
        references: List<String>,
        target: File,
    ): String? {
        if (references.isEmpty()) {
            // An empty input archive is still a valid, hashable snapshot.
            return buildZip(target, emptyList())
        }
        val entries =
            references.mapIndexed { index, ref -> loadInputEntry(index, ref) }
        return buildZip(target, entries)
    }

    /**
     * Loads one input reference THROUGH THE STORE (containment-enforced) with the
     * per-file cap; the archive name is the basename, deduplicated by index (the
     * Workspace directory structure is not revealed to the guest).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun loadInputEntry(
        index: Int,
        ref: String,
    ): Triple<String, ByteArray, String> {
        val path =
            try {
                FileScopePath.fromModelReference(ref)
            } catch (e: IllegalArgumentException) {
                throw JobArchiveException(
                    "invalid input reference: ${e.message?.take(120)}",
                    e,
                )
            }
        val bytes = store.readAll(path)
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_IMPORT_BYTES) {
            throw JobArchiveException(
                "input file is missing, empty or exceeds the per-file cap: " + path.toModelReference(),
            )
        }
        val name = path.name + (if (index == 0) "" else "-" + (index + 1))
        return Triple(name, bytes, sha256Hex(bytes))
    }

    /**
     * One bounded zip: manifest first (canonical), then entries; caps enforced by the
     * writer. Any failure returns null (the caller maps it to the stable
     * `INPUT_BUILD_FAILED` — no partial artifact is submitted).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun buildZip(
        target: File,
        entries: List<Triple<String, ByteArray, String>>,
    ): String? {
        if (target.parentFile != null) target.parentFile.mkdirs()
        return try {
            var sha: String? = null
            FileOutputStream(target).use { out ->
                JobZipWriter(out).use { writer ->
                    val manifest =
                        JobManifest(
                            entries
                                .map { (name, bytes, digest) ->
                                    JobManifestEntry(name, digest, bytes.size.toLong())
                                }.sortedBy { it.path },
                        )
                    writer.writeManifest(JobManifestCodec.encode(manifest))
                    sha = sha256Hex(JobManifestCodec.encode(manifest).encodeToByteArray())
                    entries.forEachIndexed { index, (name, bytes, _) ->
                        val entryFile = File(target.parentFile, "in-$index.tmp")
                        Files.write(entryFile.toPath(), bytes)
                        try {
                            writer.writeEntry(name, entryFile)
                        } finally {
                            entryFile.delete()
                        }
                    }
                }
            }
            sha
        } catch (e: Exception) {
            null
        }
    }
}

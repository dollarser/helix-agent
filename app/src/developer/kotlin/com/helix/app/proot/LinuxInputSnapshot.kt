package com.helix.app.proot

import com.helix.app.proot.LinuxRunTool.sha256Hex
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
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
    private fun writeZip(
        target: File,
        entries: List<Pair<com.helix.runtime.proot.core.JobManifestEntry, File>>,
        manifest: String,
        reader: LinuxInputStreams,
    ) {
        JobZipWriter(FileOutputStream(target)).use { writer ->
            writer.writeManifest(manifest)
            entries.sortedBy { it.first.path }.forEach { (entry, file) ->
                reader.checkActive()
                writer.writeEntry(entry.path, file)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    fun build(
        references: List<String>,
        target: File,
        cancelled: () -> Boolean = { false },
    ): String? {
        require(references.size <= 64) { "Too many input references" }
        val parent = requireNotNull(target.absoluteFile.parentFile)
        check(parent.mkdirs() || parent.isDirectory)
        val staging = Files.createTempDirectory(parent.toPath(), "input-stream-").toFile()
        try {
            val reader = LinuxInputStreams(store, cancelled)
            val entries =
                references.mapIndexed { index, reference ->
                    val path = FileScopePath.fromModelReference(reference)
                    // Keep the established snapshot-name contract; do not silently retarget scripts.
                    val name = path.name + if (index == 0) "" else "-${index + 1}"
                    val file = File(staging, "input-$index")
                    reader.read(reference, name, file) to file
                }
            val manifest = JobManifestCodec.encode(JobManifest(entries.map { it.first }.sortedBy { it.path }))
            writeZip(target, entries, manifest, reader)
            reader.checkActive()
            return sha256Hex(manifest.encodeToByteArray())
        } catch (failure: Exception) {
            target.delete()
            throw failure
        } finally {
            staging.deleteRecursively()
        }
    }
}

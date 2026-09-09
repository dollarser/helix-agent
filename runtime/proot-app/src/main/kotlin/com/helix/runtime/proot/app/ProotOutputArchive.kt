package com.helix.runtime.proot.app

import com.helix.runtime.proot.core.JobArchiveException
import com.helix.runtime.proot.core.JobArchiveLimits
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobPath
import com.helix.runtime.proot.core.JobZipWriter
import java.io.File

/**
 * Writes the output archive (manifest entry first, then every artifact in
 * manifest order) into [archive]; returns the canonical manifest document
 * whose SHA-256 is the record's `outputManifestSha256`. Caps come from
 * [JobArchiveLimits].
 */
@Suppress("ThrowsCount") // one throw per distinct output failure
internal fun buildOutputArchive(
    archive: File,
    workspace: File,
    vararg extra: Pair<String, File>,
): String {
    val files = LinkedHashMap<String, File>()
    if (workspace.isDirectory) {
        workspace.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(workspace).path
            JobPath.validate(rel)
            files[rel] = file
        }
    }
    extra.forEach { (name, file) -> files[name] = file }
    if (files.size > JobArchiveLimits.MAX_FILES) throw JobArchiveException("output exceeds the file count cap")
    val entries =
        files.entries
            .map { (rel, file) ->
                if (file.length() > JobArchiveLimits.MAX_SINGLE_FILE_BYTES) {
                    throw JobArchiveException("output file exceeds the per-file cap: $rel")
                }
                JobManifestEntry(rel, sha256OfFile(file), file.length())
            }.sortedBy { it.path }
    val totalBytes = entries.sumOf { it.size } + entries.sumOf { (it.path.length + 130).toLong() }
    if (totalBytes > JobArchiveLimits.MAX_TOTAL_BYTES) {
        throw JobArchiveException("output exceeds the total cap")
    }
    val manifestDocument = JobManifestCodec.encode(JobManifest(entries))
    JobZipWriter(archive.outputStream()).use { writer ->
        writer.writeManifest(manifestDocument)
        entries.forEach { entry -> writer.writeEntry(entry.path, files.getValue(entry.path)) }
        // close() re-checks written == manifest and flushes the PFD
    }
    return manifestDocument
}

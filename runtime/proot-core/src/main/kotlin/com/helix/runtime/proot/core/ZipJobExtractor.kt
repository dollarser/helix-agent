package com.helix.runtime.proot.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Strict, bounded extraction of a job input archive (HXA-084). The archive is
 * a ZIP whose first entry is the canonical manifest ([JobArchiveLimits.MANIFEST_ENTRY]);
 * every file is extracted into [destDir] (created if needed) with the SAME
 * fail-closed checks the writer applies, plus:
 *
 * - symlink/hardlink/other special entries are REJECTED (the unix mode bits in
 *   the zip external attributes);
 * - the on-disk result is re-hashed and compared with the manifest, so a
 *   truncated or tampered archive is a [JobArchiveException], never a partial
 *   workspace;
 * - the extraction is atomic in its contract: on ANY failure the caller deletes
 *   [destDir]; this class never leaves a success-looking half-tree on success.
 *
 * The extracted manifest is returned so the caller can compute the input
 * manifest hash and compare it with the one the job request promised.
 */
object ZipJobExtractor {
    data class Extraction(
        val manifest: JobManifest,
        val manifestSha256: String,
        val totalBytes: Long,
    )

    // One throw per distinct structural/limits violation; the extract is a
    // single strict, fail-closed pass (long by necessity, not to be fragmented).
    @Suppress(
        "ThrowsCount",
        "LongMethod",
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "LoopWithTooManyJumpStatements",
    )
    fun extract(
        archive: File,
        destDir: File,
    ): Extraction {
        if (!archive.isFile) throw JobArchiveException("input archive is missing")
        if (archive.length() > JobArchiveLimits.MAX_TOTAL_BYTES) {
            throw JobArchiveException("input archive exceeds the total size cap")
        }
        // The JDK's ZipEntry API does not expose the unix mode bits (external
        // attributes), but they are the ONLY carrier of a symlink request in a
        // hostile archive: a "symlink" entry extracted by the JDK would silently
        // become a regular file containing the target path. Scan the central
        // directory ourselves and reject any non-regular entry type up front.
        ZipCentralDirectoryScan.rejectNonRegularEntries(archive)
        val zip = ZipFile(archive)
        try {
            val manifestEntry =
                zip.getEntry(JobArchiveLimits.MANIFEST_ENTRY)
                    ?: throw JobArchiveException("input archive is missing its manifest")
            val manifestText =
                zip.getInputStream(manifestEntry).use { it.readBytes().toString(Charsets.UTF_8) }
            val manifest = JobManifestCodec.parse(manifestText)
            val expected =
                mutableMapOf<String, JobManifestEntry>().apply {
                    manifest.entries.forEach { put(it.path, it) }
                }
            if (expected.isEmpty()) {
                // An empty input is legal, but the manifest must be the ONLY entry.
                if (zip.size() != 1) throw JobArchiveException("empty manifest with non-empty archive")
                destDir.mkdirs()
                return Extraction(manifest, sha256HexBytes(manifestText.toByteArray(Charsets.UTF_8)), 0L)
            }
            if (!destDir.mkdirs() && !destDir.isDirectory) {
                throw JobArchiveException("cannot create the workspace directory")
            }
            var totalBytes = 0L
            for (entry in zip.entries()) {
                val name = entry.name
                if (entry.isDirectory) {
                    if (name == JobArchiveLimits.MANIFEST_ENTRY) continue
                    JobPath.validate(name.trimEnd('/'))
                    File(destDir, name).mkdirs()
                    continue
                }
                if (name == JobArchiveLimits.MANIFEST_ENTRY) continue
                JobPath.validate(name)
                val manifestEntry =
                    expected.remove(name)
                        ?: throw JobArchiveException("archive entry is not in its manifest")
                val target = File(destDir, name)
                if (target.exists()) throw JobArchiveException("duplicate archive entry")
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var fileBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            fileBytes += read
                            if (fileBytes > JobArchiveLimits.MAX_SINGLE_FILE_BYTES) {
                                throw JobArchiveException("archive entry exceeds the single-file cap")
                            }
                            totalBytes += read
                            if (totalBytes > JobArchiveLimits.MAX_TOTAL_BYTES) {
                                throw JobArchiveException("archive exceeds the total size cap")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                        }
                        val actual = JobManifestEntry(name, sha256Hex(digest), fileBytes)
                        if (actual != manifestEntry) {
                            throw JobArchiveException("archive entry does not match its manifest")
                        }
                    }
                }
            }
            if (expected.isNotEmpty()) {
                throw JobArchiveException("manifest lists files the archive does not contain")
            }
            return Extraction(
                manifest,
                sha256HexBytes(manifestText.toByteArray(Charsets.UTF_8)),
                totalBytes,
            )
        } finally {
            zip.close()
        }
    }

    private fun sha256HexBytes(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

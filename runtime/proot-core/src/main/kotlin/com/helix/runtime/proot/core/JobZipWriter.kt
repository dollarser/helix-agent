package com.helix.runtime.proot.core

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bounded job-archive writer (HXA-084). The manifest entry is ALWAYS written
 * first, then each file in sorted path order, streamed and hashed as it goes.
 * Size caps are enforced while writing; a violation throws [JobArchiveException]
 * and the caller must treat the archive as failed (delete the partial output).
 * [close] re-checks that the written entries exactly match the manifest, so an
 * archive can never drift from its own manifest.
 */
class JobZipWriter(
    private val out: OutputStream,
) : AutoCloseable {
    private val zip = ZipOutputStream(out)
    private var manifestText: String? = null
    private val written = mutableListOf<JobManifestEntry>()
    private var totalBytes = 0L

    /** Writes the manifest entry; it must precede every file entry. */

    @Suppress("ThrowsCount") // one throw per distinct misuse
    fun writeManifest(manifestJson: String) {
        if (manifestText != null) throw JobArchiveException("manifest written twice")
        if (written.isNotEmpty()) throw JobArchiveException("manifest must be the first entry")
        if (manifestJson.length > JobArchiveLimits.MAX_MANIFEST_BYTES) {
            throw JobArchiveException("manifest exceeds the size cap")
        }
        JobManifestCodec.parse(manifestJson) // fail fast on a malformed manifest
        zip.putNextEntry(ZipEntry(JobArchiveLimits.MANIFEST_ENTRY))
        zip.write(manifestJson.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        manifestText = manifestJson
    }

    /** Streams [file] under [relativePath], hashing it while copying. */

    @Suppress("ThrowsCount") // one throw per distinct misuse
    fun writeEntry(
        relativePath: String,
        file: File,
    ): JobManifestEntry {
        if (manifestText == null) throw JobArchiveException("manifest must be written first")
        if (written.size >= JobArchiveLimits.MAX_FILES) {
            throw JobArchiveException("archive exceeds the file count cap")
        }
        JobPath.validate(relativePath)
        if (!file.isFile) throw JobArchiveException("archive entry is not a regular file")
        if (file.length() > JobArchiveLimits.MAX_SINGLE_FILE_BYTES) {
            throw JobArchiveException("archive entry exceeds the single-file cap")
        }
        if (totalBytes + file.length() > JobArchiveLimits.MAX_TOTAL_BYTES) {
            throw JobArchiveException("archive exceeds the total size cap")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(relativePath))
        var fileBytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
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
                zip.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
        }
        zip.closeEntry()
        val actual = JobManifestEntry(relativePath, sha256Hex(digest), fileBytes)
        written += actual
        return actual
    }

    /**
     * Closes the archive. The written entries must exactly match the manifest
     * (same paths, hashes, sizes, order) — otherwise the archive is corrupt and
     * [JobArchiveException] is thrown (the zip may already be half-closed; the
     * caller deletes the partial file).
     */
    override fun close() {
        val manifest =
            manifestText
                ?: throw JobArchiveException("archive is missing its manifest")
        val expected = JobManifestCodec.parse(manifest).entries
        if (written != expected) {
            throw JobArchiveException("archive entries do not match the manifest")
        }
        zip.close()
        out.close()
    }
}

/** sha256 lowercase hex of a digest (house helper, mirrors RuntimeLockCodec). */
fun sha256Hex(digest: MessageDigest): String =
    digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

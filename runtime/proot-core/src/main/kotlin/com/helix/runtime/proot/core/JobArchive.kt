package com.helix.runtime.proot.core

/**
 * HXA-084 bounded job transfer: the main app copies job inputs into a ZIP with a
 * fixed first manifest entry; the Runtime extracts into its private
 * `jobs/<jobId>/workspace` and, after execution, writes an output ZIP of the same
 * shape (manifest first). Both archives are bounded and path-checked on BOTH ends;
 * a violation is a protocol failure, never a partial archive.
 *
 * The same [JobManifestEntry] type describes both sides, so the input manifest
 * hash the client promises in the request can be re-verified after extraction,
 * and the output manifest hash the terminal record carries can be re-verified
 * against the archive the client received (architecture doc section 6.5/6.6).
 */
object JobArchiveLimits {
    /** Fixed name of the manifest entry; it is ALWAYS the first entry of the archive. */
    const val MANIFEST_ENTRY = "__helix_job_manifest.json"

    /** Max number of (non-directory) files per archive. */
    const val MAX_FILES = 4096

    /** Max size of a single file (uncompressed). */
    const val MAX_SINGLE_FILE_BYTES = 64L * 1024L * 1024L

    /** Max total uncompressed size of all files in one archive. */
    const val MAX_TOTAL_BYTES = 128L * 1024L * 1024L

    /** Max serialized size of the manifest entry itself. */
    const val MAX_MANIFEST_BYTES = 512L * 1024L

    /** Max length of one relative entry path. */
    const val MAX_PATH_LENGTH = 512
}

/** One file of a job archive (input or output); paths are strict relative paths. */
data class JobManifestEntry(
    val path: String,
    val sha256: String,
    val size: Long,
)

/** The manifest payload of one archive (strict JSON, see [JobManifestCodec]). */
data class JobManifest(
    val entries: List<JobManifestEntry>,
)

/** A bounded archive read/parse/extract failure. The message is user-safe (no paths). */
class JobArchiveException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Strict relative-path validation shared by writing and extracting. A valid path
 * is a forward-slash-separated, normalized, non-empty sequence of segments;
 * absolute paths, drive letters, `..`, `.` and empty segments are violations.
 */
object JobPath {
    // One throw per distinct path violation; the checks are the security core.
    @Suppress("ThrowsCount", "CyclomaticComplexMethod")
    fun validate(path: String) {
        if (path.isEmpty()) throw JobArchiveException("empty archive path")
        if (path.length > JobArchiveLimits.MAX_PATH_LENGTH) {
            throw JobArchiveException("archive path too long")
        }
        if (path.startsWith("/")) throw JobArchiveException("archive path is absolute")
        if (path.indexOf('\\') >= 0) throw JobArchiveException("archive path has a backslash")
        if (path.endsWith("/")) throw JobArchiveException("archive path has a trailing slash")
        // Windows drive letters ("C:foo") — the JVM's Zip handling never creates
        // them, but a hostile archive might; reject before any File use.
        if (isDriveLetterPath(path)) throw JobArchiveException("archive path is a drive-letter path")
        for (segment in path.split('/')) {
            if (segment.isEmpty() || segment == ".") {
                throw JobArchiveException("archive path has an empty or '.' segment")
            }
            if (segment == "..") throw JobArchiveException("archive path traverses up")
        }
    }

    private fun isDriveLetterPath(path: String): Boolean =
        path.length >= 2 && path[1] == ':' && path[0].let { it in 'A'..'Z' || it in 'a'..'z' }
}

package com.helix.core.storage.content

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * File-backed store for large content bodies (architecture doc 9.2: 大型正文和二进制存文件,
 * Room stores references, hashes and metadata only). Content is addressed by its SHA-256 so
 * reads can be verified against the reference; writes are idempotent.
 */
interface ContentStore {
    /** Stores [content] and returns its verified reference. */
    fun write(content: String): ContentRef

    /** Reads and hash-verifies the referenced content. */
    fun read(ref: ContentRef): String

    fun exists(ref: ContentRef): Boolean
}

/**
 * Default [ContentStore] on the local file system: files live under
 * `<root>/content/<first 2 hex chars>/<sha256>`. Pure JVM (no Android dependency) so it is
 * unit-testable; the app hands it a directory under `context.filesDir`.
 */
class FileContentStore(
    private val root: File,
) : ContentStore {
    override fun write(content: String): ContentRef {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val hash = sha256Hex(bytes)
        val dir = File(root, ContentRef.expectedPath(hash).substringBeforeLast('/'))
        val file = File(root, ContentRef.expectedPath(hash))
        if (!file.exists()) {
            require(dir.mkdirs() || dir.exists()) { "cannot create content dir: ${dir.absolutePath}" }
            // A unique temp name per attempt: concurrent writers of identical content must
            // never share a temp file (interleaved bytes corrupt the verify-read), and a
            // rename that fails because the target already exists resolves by re-verifying
            // the winner's bytes instead of throwing (idempotent write).
            val tmp = File(dir, "$hash.tmp-${UUID.randomUUID()}")
            tmp.writeBytes(bytes)
            try {
                require(sha256Hex(tmp.readBytes()) == hash) { "content hash mismatch after write" }
                require(tmp.renameTo(file) || readHashOrNull(file) == hash) {
                    "cannot finalize content file at ${file.absolutePath}"
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
        val ref = ContentRef(ContentRef.expectedPath(hash), bytes.size.toLong(), hash)
        require(exists(ref)) { "content reference not readable after write: $ref" }
        return ref
    }

    override fun read(ref: ContentRef): String {
        val file = fileFor(ref)
        require(file.isFile) { "content not found: ${ref.relativePath}" }
        val bytes = file.readBytes()
        require(bytes.size.toLong() == ref.size) { "content size mismatch for ${ref.relativePath}" }
        require(sha256Hex(bytes) == ref.sha256) { "content hash mismatch for ${ref.relativePath}" }
        return bytes.toString(Charsets.UTF_8)
    }

    override fun exists(ref: ContentRef): Boolean = fileFor(ref).isFile

    private fun readHashOrNull(file: File): String? =
        if (file.isFile) {
            runCatching {
                sha256Hex(file.readBytes())
            }.getOrNull()
        } else {
            null
        }

    private fun fileFor(ref: ContentRef): File {
        // The path must be exactly the content-addressed layout derived from the hash;
        // anything else is a traversal/foreign path and is rejected.
        require(ref.relativePath == ContentRef.expectedPath(ref.sha256)) {
            "content path is not content-addressed: ${ref.relativePath}"
        }
        return File(root, ref.relativePath)
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

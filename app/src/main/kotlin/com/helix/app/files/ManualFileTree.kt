package com.helix.app.files

import com.helix.app.files.ManualFileOperations.Companion.join
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Streaming, verification and recursive deletion, with cancellation between chunks/nodes. */
internal class ManualFileTree {
    @Suppress("NestedBlockDepth") // Nested input/output resources close independently on cancellation.
    fun copyTree(
        from: ManualFileBackend,
        source: String,
        to: ManualFileBackend,
        target: String,
        cancel: () -> Boolean,
        depth: Int,
    ) {
        checkDepth(depth)
        checkCancelled(cancel)
        val info = requireNotNull(from.stat(source)) { "Source is missing" }
        to.create(target, info.directory)
        if (info.directory) {
            from.children(source).forEach { copyTree(from, join(source, it), to, join(target, it), cancel, depth + 1) }
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            from.read(source).use { input ->
                to.write(target).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        checkCancelled(cancel)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            check(digest.digest().contentEquals(hash(to, target, cancel))) { "Copied content verification failed" }
        }
    }

    @Suppress("ReturnCount") // Missing and mismatched nodes fail comparison immediately.
    fun sameTree(
        from: ManualFileBackend,
        source: String,
        to: ManualFileBackend,
        target: String,
        cancel: () -> Boolean,
        depth: Int,
    ): Boolean {
        checkDepth(depth)
        checkCancelled(cancel)
        val left = from.stat(source) ?: return false
        val right = to.stat(target) ?: return false
        if (left.directory != right.directory) return false
        if (!left.directory) return hash(from, source, cancel).contentEquals(hash(to, target, cancel))
        val names = from.children(source).sorted()
        return names == to.children(target).sorted() &&
            names.all { sameTree(from, join(source, it), to, join(target, it), cancel, depth + 1) }
    }

    fun fingerprint(
        fs: ManualFileBackend,
        path: String,
        cancel: () -> Boolean = { false },
        depth: Int = 0,
    ): String {
        checkDepth(depth)
        checkCancelled(cancel)
        val info = requireNotNull(fs.stat(path)) { "File is missing" }
        val digest = MessageDigest.getInstance("SHA-256")
        if (info.directory) {
            digest.update(1.toByte())
            fs.children(path).sorted().forEach { name ->
                val bytes = name.toByteArray(Charsets.UTF_8)
                digest.update(
                    java.nio.ByteBuffer
                        .allocate(4)
                        .putInt(bytes.size)
                        .array(),
                )
                digest.update(bytes)
                digest.update(fingerprint(fs, join(path, name), cancel, depth + 1).toByteArray(Charsets.UTF_8))
            }
        } else {
            digest.update(0.toByte())
            digest.update(hash(fs, path, cancel))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hash(
        fs: ManualFileBackend,
        path: String,
        cancel: () -> Boolean,
    ): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        fs.read(path).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkCancelled(cancel)
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }

    fun deleteTree(
        fs: ManualFileBackend,
        path: String,
        cancel: () -> Boolean,
        depth: Int,
    ) {
        checkDepth(depth)
        checkCancelled(cancel)
        if (fs.stat(path)?.directory ==
            true
        ) {
            fs.children(path).forEach { deleteTree(fs, join(path, it), cancel, depth + 1) }
        }
        fs.delete(path)
    }

    private fun checkCancelled(cancel: () -> Boolean) {
        if (cancel()) throw CancellationException("File operation cancelled")
    }

    private fun checkDepth(depth: Int) {
        require(depth <= MAX_DEPTH) { "Folder nesting exceeds the supported depth" }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_DEPTH = 128
    }
}

package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal data class SyncRoot(
    val scope: String,
    val path: String,
) {
    init {
        require(FileScopePath(scope, path).relativePath == path && (path.isNotEmpty() || scope.startsWith("saf-")))
    }

    fun child(relative: String) = if (path.isEmpty()) relative else "$path/$relative"
}

internal data class SyncNode(
    val hash: String,
    val directory: Boolean = false,
)

internal data class SyncCopy(
    val relative: String,
    val fromLeft: Boolean,
    val source: SyncNode,
    val target: SyncNode?,
)

internal data class SyncPlan(
    val copies: List<SyncCopy>,
    val conflicts: List<String>,
)

/** Common baseline, not timestamps. Deletions/types are reviewed manually; never propagated silently. */
internal object DirectorySyncPlan {
    fun build(
        base: Map<String, SyncNode>,
        left: Map<String, SyncNode>,
        right: Map<String, SyncNode>,
    ): SyncPlan {
        val copies = mutableListOf<SyncCopy>()
        val conflicts = mutableListOf<String>()
        for (path in (base.keys + left.keys + right.keys).sorted()) {
            val before = base[path]
            val a = left[path]
            val b = right[path]
            if (a != b) {
                val direction = direction(before, a, b)
                if (direction == null) {
                    conflicts += path
                } else {
                    copies +=
                        SyncCopy(path, direction, requireNotNull(if (direction) a else b), if (direction) b else a)
                }
            }
        }
        return SyncPlan(copies, conflicts)
    }

    private fun direction(
        before: SyncNode?,
        a: SyncNode?,
        b: SyncNode?,
    ): Boolean? =
        when {
            a == null && before == null -> false
            b == null && before == null -> true
            a?.directory == true || b?.directory == true -> null
            a == before && b != null -> false
            b == before && a != null -> true
            else -> null
        }
}

/** Bounded actual-byte hashing. No provider timestamps, links, content URI or cached permissions. */
internal class DirectorySyncScan(
    private val backend: (String) -> ManualFileBackend,
) {
    // A bounded tree walk owns its quota and nested streams.
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    fun scan(
        root: SyncRoot,
        cancel: () -> Boolean,
    ): Map<String, SyncNode> {
        val fs = backend(root.scope)
        require(fs.stat(root.path)?.directory == true) { "SYNC_ROOT_UNAVAILABLE" }
        val found = linkedMapOf<String, SyncNode>()
        var bytes = 0L

        fun visit(
            relative: String,
            depth: Int,
        ) {
            if (cancel()) throw CancellationException("SYNC_CANCELLED")
            require(depth <= 32 && found.size < 4096) { "SYNC_TREE_LIMIT" }
            val absolute = root.child(relative)
            val info = requireNotNull(fs.stat(absolute)) { "SYNC_SOURCE_CHANGED" }
            if (info.directory) {
                found[relative] = SyncNode("directory", true)
                for (name in fs.children(absolute).sorted()) {
                    require(validName(name)) { "SYNC_UNSAFE_NAME" }
                    visit("$relative/$name", depth + 1)
                }
            } else {
                require(info.size <= MAX_FILE) { "SYNC_FILE_LIMIT" }
                var size = 0L
                val raw = MessageDigest.getInstance("SHA-256")
                fs.read(absolute).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (cancel()) throw CancellationException("SYNC_CANCELLED")
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        bytes += n
                        require(size <= MAX_FILE && bytes <= MAX_TREE) { "SYNC_BYTE_LIMIT" }
                        raw.update(buffer, 0, n)
                    }
                }
                check(fs.stat(absolute) == info && (info.size < 0 || info.size == size)) { "SYNC_SOURCE_CHANGED" }
                val fingerprint =
                    MessageDigest
                        .getInstance("SHA-256")
                        .apply {
                            update(0.toByte())
                            update(raw.digest())
                        }.digest()
                        .joinToString("") { "%02x".format(it) }
                found[relative] = SyncNode(fingerprint)
            }
        }
        for (name in fs.children(root.path).sorted()) {
            require(validName(name)) { "SYNC_UNSAFE_NAME" }
            visit(name, 1)
        }
        return found
    }

    private fun validName(name: String) =
        name.isNotBlank() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.code < 32 } &&
            !name.startsWith(".helix-transfer-") && !name.startsWith(".helix-backup-")

    companion object {
        private const val MAX_FILE = 32L * 1024 * 1024
        private const val MAX_TREE = 128L * 1024 * 1024
    }
}

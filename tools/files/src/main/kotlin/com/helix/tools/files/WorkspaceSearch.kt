package com.helix.tools.files

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.SymlinkEscapesRoot
import com.helix.core.workspace.SymlinkInPath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.time.Instant

/** Bounded re-scan pages, not a directory snapshot. All access re-enters the scoped store. */
internal class WorkspaceSearch(
    private val store: WorkspaceArtifactStore,
    private val call: ExecutableToolCall,
    private val content: Boolean,
) {
    private val base = requireNotNull(filesMetaToolsParsePath(call.args))
    private val pattern = call.args["pattern"]?.jsonPrimitive?.content ?: "**/*"
    private val query =
        call.args["query"]
            ?.jsonPrimitive
            ?.content
            .orEmpty()
    private val ignoreCase = call.args["ignoreCase"]?.jsonPrimitive?.booleanOrNull ?: false
    private val skip = filesMetaToolsIntArg(call.args, "offset") ?: 0
    private val limit = filesMetaToolsIntArg(call.args, "maxResults") ?: 40
    private val excludes = (call.args["exclude"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    private val matches = mutableListOf<JsonObject>()
    private var seen = 0
    private var scanned = 0
    private var bytes = 0L
    private var unreadable = 0
    private var omitted = 0
    private var truncated = false
    private var more = false

    fun run(): JsonObject {
        require(pattern.isNotBlank() && pattern.length <= 256)
        require(!content || (query.isNotEmpty() && query.length <= 256 && '\n' !in query && '\r' !in query))
        require(skip in 0..20_000 && limit in 1..100 && excludes.size <= 8)
        require(store.stat(base).isDirectory) { "Search root is not a directory" }
        PathGlob.matches(pattern, "", ignoreCase)
        excludes.forEach { PathGlob.matches(it, "", ignoreCase) }
        walk()
        return buildJsonObject {
            put("path", JsonPrimitive(base.toModelReference()))
            put("matches", JsonArray(matches))
            put("truncated", JsonPrimitive(truncated || more))
            put("scannedEntries", JsonPrimitive(scanned))
            put("unreadableEntries", JsonPrimitive(unreadable))
            put("skippedContentFiles", JsonPrimitive(omitted))
            put("complete", JsonPrimitive(!truncated && !more && unreadable == 0 && omitted == 0))
            put("offset", JsonPrimitive(skip))
            if (more) put("nextOffset", JsonPrimitive(skip + matches.size))
            put("pagination", JsonPrimitive("rescan; changes may shift results; narrow root/pattern at scan limits"))
        }
    }

    private fun checkpoint() {
        if (call.cancel.isCancelled()) throw SearchCancelled()
        if (Instant.now().isAfter(call.deadline)) throw SearchDeadlineExceeded()
    }

    private fun walk() {
        val pending = ArrayDeque<FileScopePath>()
        pending.add(base)
        while (pending.isNotEmpty() && !more && scanned < MAX_SCAN) {
            checkpoint()
            val directory = pending.removeLast()
            val listing = store.listDir(directory, MAX_SCAN - scanned)
            truncated = truncated || listing.truncated
            for (name in listing.entries) {
                checkpoint()
                if (more || scanned >= MAX_SCAN) break
                scanned++
                visit(directory, name, pending)
            }
        }
        truncated = truncated || pending.isNotEmpty()
    }

    private fun visit(
        directory: FileScopePath,
        name: String,
        pending: ArrayDeque<FileScopePath>,
    ) {
        val path =
            try {
                FileScopePath(
                    base.scopeId,
                    listOf(directory.relativePath, name).filter { it.isNotEmpty() }.joinToString("/"),
                )
            } catch (_: IllegalArgumentException) {
                unreadable++
                return
            }
        val prefix = if (base.isRoot) "" else "${base.relativePath}/"
        val relative = path.relativePath.removePrefix(prefix)
        if (!excludes.any { PathGlob.matches(it, relative, ignoreCase) }) {
            inspect(path, relative, pending)
        }
    }

    private fun inspect(
        path: FileScopePath,
        relative: String,
        pending: ArrayDeque<FileScopePath>,
    ) {
        try {
            val info = store.stat(path)
            when {
                info.isSymlink -> {
                    unreadable++
                }

                info.isDirectory -> {
                    pending.add(path)
                }

                info.isRegularFile && PathGlob.matches(pattern, relative, ignoreCase) -> {
                    if (content) searchContent(path, info.sizeBytes) else emit(path, null, null, null)
                }

                !info.exists -> {
                    unreadable++
                }
            }
        } catch (
            _: IOException,
        ) {
            unreadable++
        } catch (_: SymlinkInPath) {
            unreadable++
        } catch (_: SymlinkEscapesRoot) {
            unreadable++
        }
    }

    @Suppress("ReturnCount") // Size, stream and encoding omissions must remain distinguishable from empty matches.
    private fun searchContent(
        path: FileScopePath,
        size: Long,
    ) {
        if (size !in 0..MAX_FILE || bytes + size > MAX_BYTES) {
            omitted++
            return
        }
        val data =
            store.openRead(path).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkpoint()
                    val count =
                        input.read(
                            buffer,
                            0,
                            minOf(
                                buffer.size.toLong(),
                                MAX_FILE + 1 - output.size(),
                                MAX_BYTES + 1 - bytes,
                            ).toInt(),
                        )
                    if (count < 0) break
                    if (count == 0) throw IOException("File read made no progress")
                    bytes += count
                    if (output.size() + count > MAX_FILE || bytes > MAX_BYTES) {
                        omitted++
                        return
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(data))
                    .toString()
            } catch (_: CharacterCodingException) {
                omitted++
                return
            }
        if ('\u0000' in text) {
            omitted++
            return
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        text.lineSequence().forEachIndexed { index, line ->
            checkpoint()
            val column = line.indexOf(query, ignoreCase = ignoreCase)
            if (!more && column >= 0) {
                val start = (column - 80).coerceAtLeast(0)
                emit(path, index + 1, line.drop(start).take(240), hash)
            }
        }
    }

    private fun emit(
        path: FileScopePath,
        line: Int?,
        text: String?,
        hash: String?,
    ) {
        if (seen++ < skip) return
        if (matches.size == limit) {
            more = true
            return
        }
        matches +=
            buildJsonObject {
                put("path", JsonPrimitive(path.toModelReference()))
                if (line != null) put("line", JsonPrimitive(line))
                if (text != null) put("text", JsonPrimitive(text))
                if (hash != null) put("sha256", JsonPrimitive(hash))
                put("versionKnown", JsonPrimitive(hash != null))
            }
    }

    private companion object {
        const val MAX_SCAN = 20_000
        const val MAX_FILE = 262_144L
        const val MAX_BYTES = 8_388_608L
    }
}

internal class SearchCancelled : RuntimeException()

internal class SearchDeadlineExceeded : RuntimeException()

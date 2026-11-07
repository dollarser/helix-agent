package com.helix.core.storage.backup

import com.helix.core.workspace.AtomicFileWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** Local replacement journal. Recover before any consumer opens a selected database/configuration root. */
@Suppress("TooManyFunctions") // One journal authority owns sealing, commit and recovery.
class RestoreFileTransaction(
    base: File,
    private val staging: File,
    private val permittedTarget: (String) -> Boolean,
    private val checkpoint: (String, Int) -> Unit = { _, _ -> },
) {
    private val base = base.canonicalFile.toPath()
    private val journal = File(staging, "transaction.json")

    enum class Outcome { NONE, RESTORED, ROLLED_BACK }

    fun queue(roots: Map<String, File?>) =
        locked {
            require(roots.isNotEmpty() && roots.size <= 20_000 && !journal.exists())
            syncTree(staging.toPath())
            val rows =
                roots.entries.mapIndexed { index, (target, source) ->
                    target(target)
                    val relative =
                        source?.let {
                            val path = it.canonicalFile.toPath()
                            val stageRoot = staging.canonicalFile.toPath()
                            require(path.startsWith(stageRoot) && path != stageRoot)
                            stageRoot.relativize(path).toString()
                        }
                    JsonObject(
                        mapOf(
                            "target" to JsonPrimitive(target),
                            "new" to (relative?.let(::JsonPrimitive) ?: JsonNull),
                            "seal" to (source?.let { JsonPrimitive(seal(it)) } ?: JsonNull),
                            "index" to JsonPrimitive(index),
                            "existed" to JsonPrimitive(false),
                        ),
                    )
                }
            write("PREPARED", JsonArray(rows))
        }

    @Suppress("TooGenericExceptionCaught") // Ordinary failures roll back and retain their original cause.
    fun recover(): Outcome =
        locked {
            if (!journal.exists()) return@locked Outcome.NONE
            val plan = read()
            val phase = plan.getValue("phase").jsonPrimitive.content
            val rows = plan.getValue("roots") as JsonArray
            if (phase == "COMMITTED") return@locked Outcome.RESTORED
            if (phase == "ROLLED_BACK") return@locked Outcome.ROLLED_BACK
            if (phase in setOf("APPLYING", "ROLLBACK_PENDING")) {
                rollback(rows)
                write("ROLLED_BACK", rows)
                return@locked Outcome.ROLLED_BACK
            }
            require(phase == "PREPARED")
            validatePrepared(rows)
            val observed =
                JsonArray(
                    rows.map { value ->
                        val row = value as JsonObject
                        val destination = target(row.getValue("target").jsonPrimitive.content)
                        Files.createDirectories(destination.parent)
                        if (Files.exists(destination, NOFOLLOW_LINKS)) syncTree(destination)
                        syncParents(destination.parent)
                        val existed = Files.exists(destination, NOFOLLOW_LINKS)
                        JsonObject(row + ("existed" to JsonPrimitive(existed)))
                    },
                )
            write("APPLYING", observed)
            try {
                observed.forEachIndexed { index, value ->
                    val row = value as JsonObject
                    val destination = target(row.getValue("target").jsonPrimitive.content)
                    val original = old(index).toPath()
                    require(!Files.exists(original, NOFOLLOW_LINKS))
                    Files.createDirectories(destination.parent)
                    if (row.getValue("existed").jsonPrimitive.boolean) move(destination, original)
                    checkpoint("after-original", index)
                    source(row)?.let { move(it.toPath(), destination) }
                    checkpoint("after-new", index)
                }
                write("COMMITTED", observed)
                Outcome.RESTORED
            } catch (failure: Exception) {
                try {
                    rollback(observed)
                    write("ROLLED_BACK", observed)
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                    throw failure
                }
                throw RestoreRolledBackException(failure)
            }
        }

    @Suppress("TooGenericExceptionCaught") // Keep the stage failure and durably prevent applying it later.
    private fun validatePrepared(rows: JsonArray) {
        try {
            rows.forEach { value ->
                val row = value as JsonObject
                target(row.getValue("target").jsonPrimitive.content)
                source(
                    row,
                )?.let { check(seal(it) == row.getValue("seal").jsonPrimitive.content) { "RESTORE_STAGE_CHANGED" } }
            }
        } catch (failure: Exception) {
            write("ROLLED_BACK", rows)
            throw failure
        }
    }

    fun requestRollback() =
        locked {
            val plan = read()
            require(plan.getValue("phase").jsonPrimitive.content == "COMMITTED")
            write("ROLLBACK_PENDING", plan.getValue("roots") as JsonArray)
        }

    fun phase(): String? = locked { if (journal.exists()) read().getValue("phase").jsonPrimitive.content else null }

    fun abortPrepared() =
        locked {
            val plan = read()
            require(plan.getValue("phase").jsonPrimitive.content == "PREPARED")
            write("ROLLED_BACK", plan.getValue("roots") as JsonArray)
        }

    private fun rollback(rows: JsonArray) {
        rows.indices.reversed().forEach { index ->
            val row = rows[index] as JsonObject
            val destination = target(row.getValue("target").jsonPrimitive.content)
            val previous = old(index).toPath()
            val new = source(row)?.toPath()
            val hadOriginal = row.getValue("existed").jsonPrimitive.boolean
            if (Files.exists(previous, NOFOLLOW_LINKS)) {
                if (Files.exists(destination, NOFOLLOW_LINKS)) {
                    check(new != null && !Files.exists(new, NOFOLLOW_LINKS)) { "RESTORE_ROLLBACK_CONFLICT" }
                    move(destination, new)
                }
                move(previous, destination)
            } else if (!hadOriginal && Files.exists(destination, NOFOLLOW_LINKS)) {
                check(new != null && !Files.exists(new, NOFOLLOW_LINKS)) { "RESTORE_ROLLBACK_CONFLICT" }
                move(destination, new)
            } else if (hadOriginal) {
                check(Files.exists(destination, NOFOLLOW_LINKS) && (new == null || Files.exists(new, NOFOLLOW_LINKS)))
            }
        }
    }

    private fun move(
        from: java.nio.file.Path,
        to: java.nio.file.Path,
    ) {
        Files.move(from, to, ATOMIC_MOVE)
        syncParents(from.parent)
        syncParents(to.parent)
    }

    private fun syncParents(start: java.nio.file.Path) {
        var current: java.nio.file.Path? = start
        while (current != null && current.startsWith(base)) {
            FileChannel.open(current, READ).use { it.force(true) }
            current = current.parent
        }
    }

    private fun syncTree(root: java.nio.file.Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                require(!Files.isSymbolicLink(path))
                FileChannel.open(path, READ).use { it.force(true) }
            }
        }
        syncParents(requireNotNull(root.parent))
    }

    private fun target(name: String): java.nio.file.Path {
        require(permittedTarget(name) && !name.startsWith('/') && name.split('/').none { it in setOf("", ".", "..") })
        val path = base.resolve(name).normalize()
        require(path.startsWith(base) && path != base)
        var current = base
        base.relativize(path).forEach { part ->
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "RESTORE_TARGET_SYMLINK" }
        }
        return path
    }

    private fun source(row: JsonObject): File? {
        val value = row.getValue("new")
        if (value == JsonNull) return null
        val relative = value.jsonPrimitive.content
        require(!relative.startsWith('/') && relative.split('/').none { it in setOf("", ".", "..") })
        return File(staging, relative).also {
            require(it.canonicalFile.toPath().startsWith(staging.canonicalFile.toPath()))
        }
    }

    private fun old(index: Int): File = File(staging, "old-$index")

    private fun read(): JsonObject {
        require(journal.length() <= 16 * 1024 * 1024)
        val obj = Json.parseToJsonElement(journal.readText()) as JsonObject
        require(obj.keys == setOf("phase", "roots"))
        require((obj.getValue("roots") as JsonArray).size <= 20_000)
        return obj
    }

    private fun write(
        phase: String,
        roots: JsonArray,
    ) {
        val data = JsonObject(mapOf("phase" to JsonPrimitive(phase), "roots" to roots)).toString().toByteArray()
        AtomicFileWriter.writeAtomic(journal.toPath(), data)
    }

    private fun <T> locked(block: () -> T): T =
        synchronized(
            locks.computeIfAbsent(staging.canonicalPath) { Any() },
        ) {
            check(staging.isDirectory || staging.mkdirs())
            FileChannel.open(File(staging, "transaction.lock").toPath(), CREATE, WRITE).use { channel ->
                channel.lock().use { block() }
            }
        }

    private companion object {
        val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }

    private fun digestFile(
        path: java.nio.file.Path,
        digest: MessageDigest,
    ) {
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }

    private fun seal(root: File): String {
        require(Files.exists(root.toPath(), NOFOLLOW_LINKS))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root.toPath()).use { paths ->
            paths.sorted().forEach { path ->
                require(!Files.isSymbolicLink(path))
                val name =
                    root
                        .toPath()
                        .relativize(path)
                        .toString()
                        .toByteArray()
                digest.update(
                    java.nio.ByteBuffer
                        .allocate(4)
                        .putInt(name.size)
                        .array(),
                )
                digest.update(name)
                if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    digest.update(1.toByte())
                    digest.update(
                        java.nio.ByteBuffer
                            .allocate(8)
                            .putLong(Files.size(path))
                            .array(),
                    )
                    digestFile(path, digest)
                } else {
                    require(Files.isDirectory(path, NOFOLLOW_LINKS))
                    digest.update(2.toByte())
                }
                digest.update(0.toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class RestoreRolledBackException(
    cause: Exception,
) : java.io.IOException("RESTORE_FAILED_ROLLED_BACK", cause)

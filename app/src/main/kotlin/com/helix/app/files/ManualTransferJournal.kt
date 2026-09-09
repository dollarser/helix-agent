package com.helix.app.files

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** App-private, versioned write-ahead records. Never stored under a user/Agent file root. */
internal class ManualTransferJournal(
    private val directory: Path,
) {
    @Synchronized
    fun save(record: ManualTransferRecord) {
        Files.createDirectories(directory)
        val properties =
            Properties().apply {
                setProperty("version", "1")
                setProperty("id", record.id)
                setProperty("sourceScope", record.sourceScope)
                setProperty("source", record.source)
                setProperty("targetScope", record.targetScope)
                setProperty("target", record.target)
                setProperty("move", record.move.toString())
                setProperty("phase", record.phase.name)
                setProperty("newHash", record.newHash)
                setProperty("oldHash", record.oldHash)
            }
        val pending = directory.resolve("${record.id}.pending")
        FileOutputStream(pending.toFile()).use {
            properties.store(it, null)
            it.fd.sync()
        }
        Files.move(pending, path(record.id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    fun list(): List<ManualTransferRecord> {
        if (!Files.exists(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".record") }
                .map { file ->
                    val p = Properties().apply { Files.newInputStream(file).use { load(it) } }
                    check(p.getProperty("version") == "1") { "Unsupported transfer record" }
                    ManualTransferRecord(
                        id = p.getProperty("id"),
                        sourceScope = p.getProperty("sourceScope"),
                        source = p.getProperty("source"),
                        targetScope = p.getProperty("targetScope"),
                        target = p.getProperty("target"),
                        move = p.getProperty("move").toBooleanStrict(),
                        phase = ManualTransferPhase.valueOf(p.getProperty("phase")),
                        newHash = p.getProperty("newHash"),
                        oldHash = p.getProperty("oldHash"),
                    ).also { check(path(it.id) == file) { "Transfer record identity mismatch" } }
                }.iterator()
                .asSequence()
                .toList()
        }
    }

    @Synchronized
    fun remove(id: String) {
        Files.deleteIfExists(path(id))
    }

    private fun path(id: String): Path {
        require(UUID.fromString(id).toString() == id)
        return directory.resolve("$id.record")
    }
}

internal enum class ManualTransferPhase { COPYING, PREPARED, PUBLISHING, PUBLISHED, DELETING_SOURCE }

internal data class ManualTransferRecord(
    val id: String,
    val sourceScope: String,
    val source: String,
    val targetScope: String,
    val target: String,
    val move: Boolean,
    val phase: ManualTransferPhase = ManualTransferPhase.COPYING,
    val newHash: String = "",
    val oldHash: String = "",
) {
    val temporary: String get() = sibling(".helix-transfer-$id")
    val backup: String get() = sibling(".helix-backup-$id")

    private fun sibling(name: String): String = ManualFileOperations.join(target.substringBeforeLast('/', ""), name)
}

/** Public presentation has only scoped paths; recovery always loads the private record by id. */
data class FileTransferRecovery(
    val id: String,
    val source: String,
    val destination: String,
    val move: Boolean,
)

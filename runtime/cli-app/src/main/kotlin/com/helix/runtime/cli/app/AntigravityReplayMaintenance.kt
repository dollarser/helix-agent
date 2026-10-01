package com.helix.runtime.cli.app

import com.helix.runtime.cli.client.CliReplayEntry
import com.helix.runtime.cli.client.CliReplayMaintenance
import com.helix.runtime.cli.client.CliReplayPage
import com.helix.runtime.cli.client.CliReplayPruneResult
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.TreeSet

/** Runtime owns private evidence. Only a trusted, reference-checked host may submit these candidates. */
internal class AntigravityReplayMaintenance(
    private val directory: File,
) {
    fun page(after: String?): CliReplayPage =
        synchronized(AntigravityReplayStore.LOCK) {
            after?.let(CliReplayMaintenance::requireHash)
            if (!Files.exists(
                    directory.toPath(),
                    LinkOption.NOFOLLOW_LINKS,
                )
            ) {
                return@synchronized CliReplayPage(emptyList(), null)
            }
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            val names = TreeSet<String>()
            var visited = 0
            Files.newDirectoryStream(directory.toPath()).use { stream ->
                for (path in stream) {
                    check(++visited <= MAX_SCAN_ENTRIES) { "REPLAY_SCAN_LIMIT" }
                    val name = path.fileName.toString()
                    if (!RECORD_NAME.matches(name)) continue
                    val key = name.removeSuffix(".json")
                    if (after == null || key > after) {
                        names.add(key)
                        if (names.size > CliReplayMaintenance.PAGE_SIZE + 1) names.pollLast()
                    }
                }
            }
            val more = names.size > CliReplayMaintenance.PAGE_SIZE
            val entries = names.take(CliReplayMaintenance.PAGE_SIZE).map(::inspect)
            CliReplayPage(entries, if (more) entries.last().key else null)
        }

    /** Caller holds the runner's quiescence gate across this operation, never a host Room transaction. */
    fun prune(candidates: List<CliReplayEntry>): CliReplayPruneResult =
        synchronized(AntigravityReplayStore.LOCK) {
            require(candidates.size <= CliReplayMaintenance.PAGE_SIZE)
            require(candidates.map { it.key }.distinct().size == candidates.size)
            require(candidates.all { it.fingerprint != null })
            if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@synchronized CliReplayPruneResult(0, candidates.size, 0, 0)
            }
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            var deleted = 0
            var retained = 0
            var failed = 0
            var bytes = 0L
            for (candidate in candidates) {
                val current = inspect(candidate.key)
                if (current.fingerprint == null || current != candidate) {
                    retained++
                } else {
                    try {
                        Files.delete(File(directory, "${candidate.key}.json").toPath())
                        deleted++
                        bytes += candidate.bytes
                    } catch (_: java.io.IOException) {
                        failed++
                    }
                }
            }
            CliReplayPruneResult(deleted, retained, failed, bytes)
        }

    @Suppress("TooGenericExceptionCaught") // A broken record is explicitly retained, never treated as an orphan.
    private fun inspect(key: String): CliReplayEntry {
        val file = File(directory, "$key.json")
        return try {
            val bytes = AntigravityReplayFiles.bytes(file)
            val root = AntigravityReplayFiles.decode(bytes)
            CliReplayEntry(
                key,
                CliReplayMaintenance.hash(bytes),
                root["owner"]?.jsonPrimitive?.content,
                bytes.size.toLong(),
            )
        } catch (_: Exception) {
            val size =
                if (Files.isRegularFile(
                        file.toPath(),
                        LinkOption.NOFOLLOW_LINKS,
                    )
                ) {
                    file.length().coerceAtLeast(0)
                } else {
                    0L
                }
            CliReplayEntry(key, null, null, size)
        }
    }

    private companion object {
        const val MAX_SCAN_ENTRIES = 100_000
        val RECORD_NAME = Regex("[0-9a-f]{64}\\.json")
    }
}

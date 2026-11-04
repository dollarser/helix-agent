package com.helix.app.diagnostics

import com.helix.core.workspace.AtomicFileWriter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

/** Owns only this dedicated directory. Never accepts user paths or reads execution/result bodies. */
@Suppress("TooManyFunctions") // One synchronized owner of configuration, publication, pins and collection.
internal class DiagnosticArchive(
    private val root: Path,
    private val protectedOwners: () -> Set<String>?,
    private val now: () -> Long = System::currentTimeMillis,
    private val recordLimit: Int = 10_000,
) {
    private val configFile = root.resolve("config.json")
    private val records = root.resolve("records")
    private var config: DiagnosticArchiveConfig

    init {
        require(recordLimit in 1..10_000)
        require(!Files.isSymbolicLink(root))
        Files.createDirectories(root)
        require(!Files.isSymbolicLink(records))
        Files.createDirectories(records)
        config = if (Files.exists(configFile, NOFOLLOW_LINKS)) readConfig() else DiagnosticArchiveConfig()
    }

    @Synchronized
    fun configure(policy: DiagnosticRetention) {
        save(config.copy(policy = policy))
        clean()
    }

    @Synchronized
    fun clean() {
        // At most 10k rows; release coroutine/worker control between later scheduled sweeps if interrupted.
        repeat(recordLimit / SWEEP_BATCH + 2) {
            if (maintain(force = true) == 0) return
        }
    }

    @Synchronized
    fun pin(
        id: String,
        pinned: Boolean,
    ) {
        val record = load(path(id))
        val key = record.owner ?: record.id
        save(config.copy(pinned = if (pinned) config.pinned + key else config.pinned - key))
    }

    @Synchronized
    fun append(
        kind: DiagnosticKind,
        event: String,
        body: String,
        owner: String? = null,
    ): Boolean {
        require(event.matches(Regex("[a-zA-Z0-9_.-]{1,80}")))
        require(owner == null || owner.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(body.toByteArray().size <= MAX_BODY_BYTES)
        val time = now()
        val bodyBytes = body.toByteArray()
        val record = DiagnosticRecord(UUID.randomUUID().toString(), owner, kind, event, time, hash(bodyBytes))
        val encoded = DiagnosticArchiveCodec.record(record).toByteArray()
        val incoming = encoded.size.toLong() + bodyBytes.size
        maintain(force = false, incoming = incoming, incomingLog = if (kind == DiagnosticKind.LOG) incoming else 0)
        var entries = scan()
        if (entries.size >= recordLimit) {
            clean()
            entries = scan()
        }
        val used = diskBytes()
        val logBytes = entries.filter { it.record.kind == DiagnosticKind.LOG }.sumOf { it.bytes }
        val full = entries.size >= recordLimit || used + incoming > config.policy.budgetBytes
        val logFull = kind == DiagnosticKind.LOG && logBytes + incoming > LOG_BUDGET
        if (full || logFull) {
            save(config.copy(dropped = config.dropped + 1))
            return false
        }
        // Publish bytes before their index. An interrupted publication leaves an unreferenced owned blob.
        AtomicFileWriter.writeAtomic(bodyPath(record.id), bodyBytes)
        AtomicFileWriter.writeAtomic(path(record.id), encoded)
        return true
    }

    @Synchronized
    fun status(): DiagnosticArchiveStatus {
        val entries = scan()
        val owners = protectedOwners()
        return DiagnosticArchiveStatus(
            config.policy,
            diskBytes(),
            entries.groupBy { it.record.kind }.mapValues { (_, rows) -> rows.sumOf { it.bytes } },
            entries.filter { protected(it.record, owners) }.sumOf { it.bytes },
            config.dropped,
            unknownFiles(entries),
            entries.sortedByDescending { it.record.createdAt }.take(100).map {
                val record = it.record
                DiagnosticArchiveRow(
                    record.id,
                    record.owner,
                    record.kind,
                    record.event,
                    record.createdAt,
                    it.bytes,
                    (record.owner ?: record.id) in config.pinned,
                    record.body == null,
                )
            },
        )
    }

    /** Bounded data for an explicit inspector, never a replacement for the execution ledger. */
    @Synchronized
    fun detail(id: String): String? {
        val record = load(path(id))
        val expected = record.body ?: return null
        val file = bodyPath(id)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= MAX_BODY_BYTES)
        val bytes = Files.readAllBytes(file)
        check(hash(bytes) == expected) { "DIAGNOSTIC_BODY_CORRUPT" }
        return bytes.toString(Charsets.UTF_8)
    }

    @Synchronized
    fun inspect(id: String): String? {
        val record = load(path(id))
        val body = detail(id) ?: return null
        return buildJsonObject {
            put("diagnosticId", record.id)
            put("owner", record.owner)
            put("event", record.event)
            put("createdAt", record.createdAt)
            put("kind", record.kind.name)
            put("body", body)
        }.toString()
    }

    @Synchronized
    fun maintain(
        force: Boolean,
        incoming: Long = 0,
        incomingLog: Long = 0,
    ): Int {
        val time = now()
        val entries = scan()
        val used = diskBytes()
        val pressure = used + incoming >= config.policy.budgetBytes * 8 / 10 || entries.size >= recordLimit - 1
        val logPressure =
            entries.filter { it.record.kind == DiagnosticKind.LOG }.sumOf { it.bytes } + incomingLog >= LOG_BUDGET
        val due = force || time - config.lastSweep >= DIAGNOSTIC_DAY
        if (!due && !pressure && !logPressure) return 0
        val owners = protectedOwners() // Failure to read durable protection aborts collection.
        val candidates = entries.filterNot { protected(it.record, owners) }
        val state =
            SweepState(
                used,
                entries.filter { it.record.kind == DiagnosticKind.LOG }.sumOf { it.bytes },
                entries.size,
                pressure,
                logPressure,
                incoming,
                incomingLog,
                entries.filter { it.record.kind == DiagnosticKind.FAILURE }.mapNotNull { it.record.owner }.toSet(),
            )
        val ordered =
            candidates.sortedWith(
                compareBy<Entry> { priority(it.record.kind) }.thenBy { it.record.createdAt },
            )
        for (entry in ordered.filter { shouldCollect(it.record, state, time) }.take(SWEEP_BATCH)) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Diagnostic cleanup interrupted")
            if (shouldCollect(entry.record, state, time)) collect(entry, state, time)
        }
        cleanAbandonedTemps(time)
        save(config.copy(lastSweep = time))
        return state.changed
    }

    private fun shouldCollect(
        record: DiagnosticRecord,
        state: SweepState,
        time: Long,
    ): Boolean {
        if (record.body == null) {
            return time - requireNotNull(record.clearedAt) >= 90 * DIAGNOSTIC_DAY || state.count >= recordLimit - 1
        }
        val kind =
            if (record.owner in state.failureOwners && record.kind == DiagnosticKind.NORMAL) {
                DiagnosticKind.FAILURE
            } else {
                record.kind
            }
        val expired = (time - record.createdAt).coerceAtLeast(0) >= config.policy.ttl(kind)
        val trim = state.pressure && state.remaining + state.incoming > config.policy.budgetBytes * 7 / 10
        val trimCount = state.count - state.changed >= recordLimit - 1
        val trimLogs =
            record.kind == DiagnosticKind.LOG && state.logPressure &&
                state.logs + state.incomingLog > LOG_BUDGET * 7 / 10
        return expired || trim || trimLogs || trimCount
    }

    private fun collect(
        entry: Entry,
        state: SweepState,
        time: Long,
    ) {
        val record = entry.record
        var remainingBytes = 0
        if (record.body == null) {
            Files.delete(entry.path)
            state.count--
        } else {
            val cleared = DiagnosticArchiveCodec.record(record.copy(body = null, clearedAt = time)).toByteArray()
            AtomicFileWriter.writeAtomic(entry.path, cleared)
            deleteBody(record.id)
            remainingBytes = cleared.size
        }
        state.remaining -= entry.bytes - remainingBytes
        if (record.kind == DiagnosticKind.LOG) state.logs -= entry.bytes - remainingBytes
        state.changed++
    }

    private data class SweepState(
        var remaining: Long,
        var logs: Long,
        var count: Int,
        val pressure: Boolean,
        val logPressure: Boolean,
        val incoming: Long,
        val incomingLog: Long,
        val failureOwners: Set<String>,
        var changed: Int = 0,
    )

    private fun protected(
        record: DiagnosticRecord,
        owners: Set<String>?,
    ): Boolean =
        (record.owner ?: record.id) in config.pinned ||
            (record.owner != null && (owners == null || record.owner in owners))

    private fun priority(kind: DiagnosticKind): Int =
        when (kind) {
            DiagnosticKind.TEMPORARY -> 0
            DiagnosticKind.LOG, DiagnosticKind.NORMAL -> 1
            DiagnosticKind.ATTACHMENT -> 2
            DiagnosticKind.FAILURE_ATTACHMENT -> 3
            DiagnosticKind.FAILURE -> 4
        }

    private fun scan(): List<Entry> {
        require(!Files.isSymbolicLink(root) && !Files.isSymbolicLink(records))
        return Files.list(records).use { paths ->
            paths
                .iterator()
                .asSequence()
                .filter { isRecord(it) }
                .take(recordLimit + 1)
                .mapNotNull {
                    // Retain corrupt evidence and count its bytes; it is not a cleanup candidate.
                    try {
                        val record = load(it)
                        val body = bodyPath(record.id)
                        val bytes = if (Files.isRegularFile(body, NOFOLLOW_LINKS)) Files.size(body) else 0L
                        Entry(it, record, Files.size(it) + bytes)
                    } catch (_: kotlinx.serialization.SerializationException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }.toList()
        }
    }

    private fun isRecord(path: Path): Boolean =
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
            RECORD_NAME.matches(path.fileName.toString())

    private fun unknownFiles(entries: List<Entry>): Int =
        Files.list(records).use { paths ->
            val known = entries.flatMap { listOf(it.path, bodyPath(it.record.id)) }.toSet()
            paths
                .filter { it !in known }
                .count()
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }

    private fun diskBytes(): Long =
        Files.list(records).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
        } +
            Files.list(root).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
            }

    private fun load(path: Path): DiagnosticRecord {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= 4096)
        val record = DiagnosticArchiveCodec.readRecord(Files.readString(path))
        require(path == this.path(record.id) && record.createdAt >= 0)
        require(record.body != null || record.clearedAt != null)
        require(record.body == null || record.body.matches(Regex("[a-f0-9]{64}")))
        return record
    }

    private fun path(id: String): Path {
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && Files.isDirectory(records, NOFOLLOW_LINKS))
        require(UUID.fromString(id).toString() == id)
        return records.resolve("$id.json")
    }

    private fun bodyPath(id: String): Path = path(id).resolveSibling("$id.body")

    private fun hash(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun deleteBody(id: String) {
        val body = bodyPath(id)
        if (Files.isRegularFile(body, NOFOLLOW_LINKS)) Files.delete(body)
    }

    private fun readConfig(): DiagnosticArchiveConfig {
        require(Files.isRegularFile(configFile, NOFOLLOW_LINKS) && Files.size(configFile) <= DIAGNOSTIC_MIB)
        return DiagnosticArchiveCodec.readConfig(Files.readString(configFile))
    }

    private fun save(value: DiagnosticArchiveConfig) {
        AtomicFileWriter.writeAtomic(configFile, DiagnosticArchiveCodec.config(value).toByteArray())
        config = value
    }

    private fun cleanAbandonedTemps(time: Long) {
        // AtomicFileWriter owns these temporary siblings; no user files or active publishers share this lock/root.
        for (directory in listOf(root, records)) {
            Files.list(directory).use { paths ->
                paths
                    .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .filter { it.fileName.toString().startsWith(".helix-tmp-") }
                    .filter { time - Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toMillis() >= DIAGNOSTIC_DAY }
                    .limit(SWEEP_BATCH.toLong())
                    .forEach { Files.delete(it) }
            }
        }
        val cleared = scan().filter { it.record.body == null }.map { bodyPath(it.record.id) }.toSet()
        Files.list(records).use { paths ->
            paths
                .filter { BODY_NAME.matches(it.fileName.toString()) }
                .filter {
                    val header = it.resolveSibling(it.fileName.toString().removeSuffix(".body") + ".json")
                    it in cleared || !Files.exists(header, NOFOLLOW_LINKS)
                }.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                .limit(SWEEP_BATCH.toLong())
                .forEach {
                    if (time - Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toMillis() >=
                        DIAGNOSTIC_DAY
                    ) {
                        Files.delete(it)
                    }
                }
        }
    }

    private data class Entry(
        val path: Path,
        val record: DiagnosticRecord,
        val bytes: Long,
    )

    companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
        private const val SWEEP_BATCH = 256
        private const val LOG_BUDGET = 32 * DIAGNOSTIC_MIB
        private const val UUID_TEXT = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        private val RECORD_NAME = Regex("$UUID_TEXT\\.json")
        private val BODY_NAME = Regex("$UUID_TEXT\\.body")
    }
}

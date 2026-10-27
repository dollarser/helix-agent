package com.helix.runtime.proot.app

import com.helix.runtime.proot.core.RuntimeTreeDeletion
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import com.helix.runtime.proot.ipc.ProotJobState
import java.io.File
import java.io.FileOutputStream

/**
 * The companion's job journal (HXA-084; architecture doc section 6.7). The
 * companion is the SOLE writer; physical completion and evidence retention
 * are separate decisions:
 *
 * - one atomic `record.json` per job under `<runtimeRoot>/jobs/<jobId>/`
 *   (tmp + fsync + rename, the HXA-082 activation style),
 * - the pending execution/result budget (128 entries / 1 MiB of records) is enforced
 *   at submit time AFTER the eviction sweep,
 * - eviction: reconciled tombstones older than 7 days are dropped; terminal
 *   evidence that was NEVER reconciled is kept at most 30 days, then only an
 *   evidence-expired identity marker survives for another 7 days; the output
 *   payload is deleted only after the retention period or explicit collection.
 *
 * Reconciliation (main app verified the proof) deletes the payload directory
 * IMMEDIATELY and stamps `reconciledAtEpochMs`.
 *
 * The process-wide runner and detached service share this instance. Journal changes
 * are synchronized; independent process lifetimes and user-file results are not locked.
 *
 * The store is the journal's single writer surface: load/save/quota/eviction
 * all live with the file they mutate.
 */
@Suppress("TooManyFunctions")
class ProotJobStore(
    private val root: File,
    private val physicallyRunning: (String) -> Boolean = { false },
    private val cleanupFailure: (String, Exception) -> Unit = { _, error -> throw error },
    private val remove: (File) -> Boolean = RuntimeTreeDeletion::remove,
) {
    companion object {
        const val MAX_ENTRIES = 128
        const val MAX_TOTAL_RECORD_BYTES: Long = 1L * 1024L * 1024L
        const val RECONCILED_TOMBSTONE_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val UNRECONCILED_EVIDENCE_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
        private const val RECORD_FILE = "record.json"
        private const val OUTPUT_FILE = "output.zip"
    }

    val jobsDir: File
        get() = File(root, "jobs")

    fun jobDir(jobId: String): File = File(jobsDir, jobId)

    fun recordFile(jobId: String): File = File(jobDir(jobId), RECORD_FILE)

    fun outputFile(jobId: String): File = File(jobDir(jobId), OUTPUT_FILE)

    /** Only an absent record means unknown. Corruption cannot authorize reusing an execution identity. */
    @Synchronized
    fun load(jobId: String): ProotJobRecord? {
        val file = recordFile(jobId)
        if (!file.exists()) return null
        check(file.isFile && file.length() <= MAX_TOTAL_RECORD_BYTES) { "Job journal is unreadable" }
        return checkNotNull(parseQuietly(file)) { "Job journal is corrupt; execution identity must not be reused" }
    }

    /** All journal entries; corruption fails closed rather than freeing an execution identity. */
    @Synchronized
    fun entries(): Map<String, ProotJobRecord> {
        val dir = jobsDir
        if (!dir.isDirectory) return emptyMap()
        val result = LinkedHashMap<String, ProotJobRecord>()
        dir
            .listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { job ->
                val record = load(job.name)
                if (record != null) result[job.name] = record
            }
        return result
    }

    /**
     * Atomic record write (tmp + fsync + rename). The caller decides the state;
     * the store is the durability boundary, not a policy engine.
     */
    @Synchronized
    fun put(record: ProotJobRecord) {
        val file = recordFile(record.jobId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${RECORD_FILE}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(ProotJobRecordCodec.encode(record).encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            tmp.delete()
        }
    }

    /** Exact terminal identity; no receipt exists until all payload removal succeeds. */
    @Synchronized
    fun acknowledge(
        jobId: String,
        terminalCommit: String,
        now: Long,
    ): ProotJobRecord? {
        val current = load(jobId) ?: return null
        val settled = current.state.isTerminal && current.state != ProotJobState.ORPHANED && !physicallyRunning(jobId)
        return if (settled && !current.evidenceExpired && current.terminalCommit == terminalCommit) {
            reconcile(jobId, now)
            load(jobId)
        } else {
            null
        }
    }

    /** Reconciliation: payload deletion must finish before the receipt is committed. */
    @Synchronized
    fun reconcile(
        jobId: String,
        reconciledAtEpochMs: Long,
    ) {
        val current = load(jobId) ?: return
        if (current.reconciledAtEpochMs == null && !current.evidenceExpired) {
            require(current.state.isTerminal && current.state != ProotJobState.ORPHANED)
            check(!physicallyRunning(jobId)) { "Job is still running" }
            val files = requireNotNull(jobDir(jobId).listFiles())
            files.filter { it.name != RECORD_FILE && it.name != DetachedJobStore.FILE_NAME }.forEach {
                check(
                    remove(it),
                ) { "payload cleanup failed" }
            }
            put(current.copy(reconciledAtEpochMs = reconciledAtEpochMs))
        }
    }

    /** Deletes a payload (input-invalid / superseded); the record stays as the terminal proof. */
    @Synchronized
    fun deletePayload(jobId: String) {
        val directory = jobDir(jobId)
        if (!directory.exists()) return
        checkNotNull(directory.listFiles()) { "payload directory unreadable" }
            .filter { it.name != RECORD_FILE && it.name != DetachedJobStore.FILE_NAME }
            .forEach { check(remove(it)) { "payload cleanup failed" } }
    }

    /** Release launch scratch only after proven physical completion; result archives remain readable. */
    @Synchronized
    fun cleanupScratch(jobId: String) {
        val record = load(jobId) ?: return
        if (!record.state.isTerminal || record.state == ProotJobState.ORPHANED || physicallyRunning(jobId)) return
        listOf("input.zip", "tmp", "proc.txt", "record.json.tmp").forEach { name ->
            check(remove(File(jobDir(jobId), name))) { "job scratch cleanup failed" }
        }
    }

    /**
     * The eviction sweep + budget check, run before every submit. Returns true
     * when a NEW entry fits after eviction; the eviction itself is applied.
     * Reconciled and expired receipts retain deduplication identity for their TTL,
     * but do not consume the outstanding execution/result budget.
     */
    @Synchronized
    fun pruneAndBudgetAvailable(nowMs: Long): Boolean {
        prune(nowMs)
        val entries =
            entries().filterValues {
                !it.state.isTerminal || it.state == ProotJobState.ORPHANED || physicallyRunning(it.jobId) ||
                    (it.reconciledAtEpochMs == null && !it.evidenceExpired)
            }
        if (entries.size >= MAX_ENTRIES) return false
        val usedBytes = entries.values.sumOf { v -> recordFile(v.jobId).length() }
        // A new PENDING record is ~300 bytes; budget against the full cap.
        return usedBytes + 1024L <= MAX_TOTAL_RECORD_BYTES
    }

    /** Receipts are history, not running capacity. Unknown physical outcomes are never expired. */
    @Synchronized
    fun prune(nowMs: Long) {
        entries().forEach { (jobId, record) ->
            try {
                pruneRecord(jobId, record, nowMs)
            } catch (error: java.io.IOException) {
                cleanupFailure(jobId, error)
            } catch (error: IllegalStateException) {
                cleanupFailure(jobId, error)
            }
        }
    }

    private fun pruneRecord(
        jobId: String,
        record: ProotJobRecord,
        nowMs: Long,
    ) {
        if (!record.state.isTerminal || record.state == ProotJobState.ORPHANED || physicallyRunning(jobId)) {
            return
        }
        cleanupScratch(jobId)
        val reconciledAt = record.reconciledAtEpochMs
        val terminalAt = record.terminalAtEpochMs ?: return
        val expiredAge = UNRECONCILED_EVIDENCE_TTL_MS + RECONCILED_TOMBSTONE_TTL_MS
        when {
            reconciledAt != null && nowMs - reconciledAt > RECONCILED_TOMBSTONE_TTL_MS -> {
                check(remove(jobDir(jobId))) { "receipt cleanup failed" }
            }

            reconciledAt == null && nowMs - terminalAt > expiredAge -> {
                // Drop payload first: a failed cleanup must retain the durable identity for retry.
                deletePayload(jobId)
                check(remove(jobDir(jobId))) { "expired receipt cleanup failed" }
            }

            !record.evidenceExpired && reconciledAt == null &&
                nowMs - terminalAt > UNRECONCILED_EVIDENCE_TTL_MS -> {
                deletePayload(jobId)
                put(record.copy(evidenceExpired = true))
            }
        }
    }

    /** Acquire an output descriptor under the same lock as reconciliation/expiry. Open descriptors survive unlink. */
    @Synchronized
    internal fun <T> readOutput(block: () -> T): T = block()

    /** Orphan sweep input: non-terminal records still claiming a live process. */
    fun activeJobIds(): List<String> = entries().filterValues { !it.state.isTerminal }.keys.toList()

    // Decode failures are reported by load as a closed journal error, never as an absent execution.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseQuietly(file: File): ProotJobRecord? =
        try {
            ProotJobRecordCodec.parse(file.readText())
        } catch (e: Exception) {
            null
        }
}

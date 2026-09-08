package com.helix.runtime.proot.app

import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import com.helix.runtime.proot.ipc.ProotJobState
import java.io.File
import java.io.FileOutputStream

/**
 * The companion's job journal (HXA-084; architecture doc section 6.7). The
 * companion is the SOLE writer and it never silently deletes an active or
 * unreconciled terminal record:
 *
 * - one atomic `record.json` per job under `<runtimeRoot>/jobs/<jobId>/`
 *   (tmp + fsync + rename, the HXA-082 activation style),
 * - the bounded metadata budget (128 entries / 1 MiB of records) is enforced
 *   at submit time AFTER the eviction sweep,
 * - eviction: reconciled tombstones older than 7 days are dropped; terminal
 *   evidence that was NEVER reconciled is kept at most 30 days, then only an
 *   evidence-expired marker survives (the payload is deleted, the record shrinks
 *   to {state, evidenceExpired, terminalCommit}).
 *
 * Reconciliation (main app verified the proof) deletes the payload directory
 * IMMEDIATELY and stamps `reconciledAtEpochMs`.
 *
 * All methods run on the runner's single job thread (the binder hands every job
 * transaction to one [java.util.concurrent.ExecutorService]); there is no
 * in-process contention to lock against.
 *
 * The store is the journal's single writer surface: load/save/quota/eviction
 * all live with the file they mutate.
 */
@Suppress("TooManyFunctions")
class ProotJobStore(
    private val root: File,
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

    /** Reads one record; null when the job is unknown. Malformed -> null (sweep candidate). */
    fun load(jobId: String): ProotJobRecord? {
        val file = recordFile(jobId)
        if (!file.isFile) return null
        return parseQuietly(file)
    }

    /** All journal entries (id + parsed record); malformed files are skipped here and evicted below. */
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
    fun put(record: ProotJobRecord) {
        val file = recordFile(record.jobId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${RECORD_FILE}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(ProotJobRecordCodec.encode(record).encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
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
        return if (current.state.isTerminal && !current.evidenceExpired && current.terminalCommit == terminalCommit) {
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
            require(current.state.isTerminal)
            val files = requireNotNull(jobDir(jobId).listFiles())
            files.filter { it.name != RECORD_FILE }.forEach {
                check(
                    it.deleteRecursively(),
                ) { "payload cleanup failed" }
            }
            put(current.copy(reconciledAtEpochMs = reconciledAtEpochMs))
        }
    }

    /** Deletes a payload (input-invalid / superseded); the record stays as the terminal proof. */
    fun deletePayload(jobId: String) {
        jobDir(jobId)
            .listFiles()
            ?.filter { it.name != RECORD_FILE }
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * The eviction sweep + budget check, run before every submit. Returns true
     * when a NEW entry fits after eviction; the eviction itself is applied.
     * An evidence-expired marker is small (a few hundred bytes), so it always
     * fits within the 128/1MiB caps that the caller also checks.
     */
    fun pruneAndBudgetAvailable(nowMs: Long): Boolean {
        prune(nowMs)
        val entries = entries()
        if (entries.size >= MAX_ENTRIES) return false
        val usedBytes = entries.values.sumOf { v -> recordFile(v.jobId).length() }
        // A new PENDING record is ~300 bytes; budget against the full cap.
        return usedBytes + 1024L <= MAX_TOTAL_RECORD_BYTES
    }

    /** Applies the two TTL rules; never touches non-terminal records. */
    fun prune(nowMs: Long) {
        entries().forEach { (jobId, record) ->
            if (!record.state.isTerminal || record.evidenceExpired) return@forEach
            val reconciledAt = record.reconciledAtEpochMs
            if (reconciledAt != null) {
                if (nowMs - reconciledAt > RECONCILED_TOMBSTONE_TTL_MS) {
                    jobDir(jobId).deleteRecursively()
                }
            } else {
                val terminalAt = record.terminalAtEpochMs
                if (terminalAt != null && nowMs - terminalAt > UNRECONCILED_EVIDENCE_TTL_MS) {
                    // Keep only the evidence-expired marker: payload gone, identity kept.
                    put(record.copy(evidenceExpired = true))
                    deletePayload(jobId)
                }
            }
        }
    }

    /** Orphan sweep input: non-terminal records still claiming a live process. */
    fun activeJobIds(): List<String> = entries().filterValues { !it.state.isTerminal }.keys.toList()

    // A corrupt record is ABSENT at this seam; the runner's reconcile path
    // surfaces it as ORPHANED, never as a crash.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseQuietly(file: File): ProotJobRecord? =
        try {
            ProotJobRecordCodec.parse(file.readText())
        } catch (e: Exception) {
            null
        }
}

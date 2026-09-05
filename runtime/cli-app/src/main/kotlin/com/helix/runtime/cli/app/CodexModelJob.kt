package com.helix.runtime.cli.app

import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobRecordCodec
import com.helix.runtime.cli.client.CliModelJobState
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal typealias CodexModelJobState = CliModelJobState
internal typealias CodexModelJobRecord = CliModelJobRecord

internal class CodexModelJobStore(private val root: File) {
    private val jobs = File(root, "codex-model-jobs")

    fun load(jobId: String): CodexModelJobRecord? {
        val file = recordFile(jobId)
        if (!file.isFile) return null
        return CliModelJobRecordCodec.decode(file.readText())
    }

    fun put(record: CodexModelJobRecord) {
        val file = recordFile(record.jobId)
        require(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        val tmp = File(file.parentFile, "record.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(CliModelJobRecordCodec.encode(record).encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
        require(tmp.renameTo(file) || runCatching { tmp.copyTo(file, overwrite = true); tmp.delete() }.isSuccess) {
            "atomic job record write failed"
        }
    }

    fun canAcceptNew(): Boolean {
        val directories = jobs.listFiles()?.filter { it.isDirectory }.orEmpty()
        val bytes = directories.sumOf { File(it, "record.json").length() }
        return directories.size < MAX_ENTRIES && bytes + MAX_RECORD_BYTES <= MAX_TOTAL_BYTES
    }

    fun recoverInterrupted(now: Long) {
        jobs.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val record = runCatching { load(dir.name) }.getOrNull() ?: return@forEach
            if (!record.state.terminal) {
                put(record.copy(state = CodexModelJobState.INTERRUPTED, terminalAtEpochMillis = now))
            }
        }
    }

    private fun recordFile(jobId: String) = File(File(jobs, jobId), "record.json")

    internal companion object {
        const val MAX_RECORD_BYTES = CliModelJobRecordCodec.MAX_RECORD_BYTES
        const val MAX_ENTRIES = 128
        const val MAX_TOTAL_BYTES = 1024L * 1024L
    }
}

internal sealed interface CodexModelJobSubmit {
    data class Accepted(val record: CodexModelJobRecord) : CodexModelJobSubmit
    data class Duplicate(val record: CodexModelJobRecord) : CodexModelJobSubmit
    data object RequestMismatch : CodexModelJobSubmit
    data object Busy : CodexModelJobSubmit
    data object JournalFull : CodexModelJobSubmit
}

internal class CodexModelJobRunner(
    private val store: CodexModelJobStore,
    private val execute: () -> CodexSmokeResult,
    private val cancelExecution: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
) : AutoCloseable {
    private val lock = Any()
    private var activeJobId: String? = null

    init { store.recoverInterrupted(clock()) }

    fun submit(jobId: String, requestSha256: String): CodexModelJobSubmit = synchronized(lock) {
        store.load(jobId)?.let { existing ->
            return@synchronized if (existing.requestSha256 == requestSha256) {
                CodexModelJobSubmit.Duplicate(existing)
            } else {
                CodexModelJobSubmit.RequestMismatch
            }
        }
        if (activeJobId != null) return@synchronized CodexModelJobSubmit.Busy
        if (!store.canAcceptNew()) return@synchronized CodexModelJobSubmit.JournalFull
        val pending = CodexModelJobRecord(jobId, requestSha256, CodexModelJobState.PENDING, clock())
        store.put(pending)
        activeJobId = jobId
        worker.submit { runJob(pending) }
        CodexModelJobSubmit.Accepted(pending)
    }

    fun query(jobId: String): CodexModelJobRecord? = synchronized(lock) { store.load(jobId) }

    fun cancel(jobId: String): CodexModelJobRecord? = synchronized(lock) {
        val current = store.load(jobId) ?: return@synchronized null
        if (current.state.terminal) return@synchronized current
        if (activeJobId == jobId) {
            cancelExecution()
        }
        terminal(current, CodexModelJobState.CANCELLED)
    }

    fun reconcile(jobId: String): CodexModelJobRecord? = query(jobId)

    override fun close() {
        synchronized(lock) {
            activeJobId?.let(::cancel)
            worker.shutdownNow()
        }
    }

    private fun runJob(pending: CodexModelJobRecord) {
        synchronized(lock) {
            val live = store.load(pending.jobId)
            if (live == null || live.state != CodexModelJobState.PENDING) {
                clearActive(pending.jobId)
                return
            }
            store.put(live.copy(state = CodexModelJobState.RUNNING))
        }
        val result = runCatching(execute)
        synchronized(lock) {
            val live = store.load(pending.jobId)
            if (live != null && !live.state.terminal && result.isSuccess) {
                val smoke = result.getOrThrow()
                store.put(
                    live.copy(
                        state = CodexModelJobState.SUCCEEDED,
                        terminalAtEpochMillis = clock(),
                        model = smoke.model,
                        outputSha256 = sha256(smoke.text),
                    ),
                )
            } else if (live != null && !live.state.terminal) {
                terminal(live, CodexModelJobState.FAILED)
            }
            clearActive(pending.jobId)
        }
    }

    private fun clearActive(jobId: String) {
        if (activeJobId == jobId) activeJobId = null
    }

    private fun terminal(record: CodexModelJobRecord, state: CodexModelJobState): CodexModelJobRecord =
        record.copy(state = state, terminalAtEpochMillis = clock()).also(store::put)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}

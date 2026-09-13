package com.helix.runtime.cli.app

import com.helix.core.model.ModelEvent
import com.helix.runtime.cli.client.CliModelEventCodec
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelRequestCodec
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class CodexPayloadJobStore(
    private val root: File,
) {
    private val records = CodexModelJobStore(File(root, "provider-v1"))
    private val payloads = CodexPayloadFiles(File(File(root, "provider-v1"), "codex-model-jobs"))

    fun load(jobId: String) = records.load(jobId)

    fun put(record: CliModelJobRecord) = records.put(record)

    fun canAcceptNew(incomingBytes: Int) = incomingBytes > 0 && records.canAcceptNew()

    fun recoverInterrupted(now: Long) = records.recoverInterrupted(now)

    fun expireEvidence(now: Long) = records.expireEvidence(now)

    fun putRequest(
        jobId: String,
        bytes: ByteArray,
    ) = payloads.putRequest(jobId, bytes)

    fun loadRequest(jobId: String): ByteArray = payloads.loadRequest(jobId)

    fun putOutput(
        jobId: String,
        bytes: ByteArray,
    ) = payloads.putOutput(jobId, bytes)

    fun loadOutput(jobId: String): ByteArray? = payloads.loadOutput(jobId)

    fun finishReconcile(
        record: CliModelJobRecord,
        now: Long,
    ): CliModelJobRecord {
        records.expireEvidence(now)
        val current = requireNotNull(load(record.jobId))
        if (current.state == CliModelJobState.EVIDENCE_EXPIRED) return current
        payloads.delete(record.jobId)
        return current.copy(reconciledAtEpochMillis = now).also(::put)
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 64L * 1024L * 1024L
    }
}

internal sealed interface CodexPayloadSubmit {
    data class Accepted(
        val record: CliModelJobRecord,
    ) : CodexPayloadSubmit

    data class Duplicate(
        val record: CliModelJobRecord,
    ) : CodexPayloadSubmit

    data object RequestMismatch : CodexPayloadSubmit

    data object Busy : CodexPayloadSubmit

    data object JournalFull : CodexPayloadSubmit
}

internal data class CodexReconcile(
    val record: CliModelJobRecord,
    val payload: ByteArray?,
)

internal class CodexPayloadJobRunner(
    private val store: CodexPayloadJobStore,
    private val execute: (ByteArray) -> CodexModelExecution,
    private val cancelExecution: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val executeStreaming: ((ByteArray, (List<ModelEvent>) -> Unit) -> CodexModelExecution)? = null,
) : AutoCloseable {
    private val lock = Any()
    private var activeJobId: String? = null
    private val progress = CodexJobProgress()

    init {
        store.recoverInterrupted(clock())
    }

    fun submit(
        jobId: String,
        requestSha256: String,
        payload: ByteArray,
    ): CodexPayloadSubmit =
        synchronized(lock) {
            store.load(jobId)?.let { existing ->
                return@synchronized if (existing.requestSha256 == requestSha256) {
                    CodexPayloadSubmit.Duplicate(existing)
                } else {
                    CodexPayloadSubmit.RequestMismatch
                }
            }
            require(sha256(payload) == requestSha256) { "request hash mismatch" }
            CliModelRequestCodec.decode(payload)
            if (activeJobId != null) return@synchronized CodexPayloadSubmit.Busy
            if (!store.canAcceptNew(payload.size)) return@synchronized CodexPayloadSubmit.JournalFull
            store.putRequest(jobId, payload)
            val pending = CliModelJobRecord(jobId, requestSha256, CliModelJobState.PENDING, clock())
            store.put(pending)
            activeJobId = jobId
            progress.clear()
            worker.submit { runJob(pending) }
            CodexPayloadSubmit.Accepted(pending)
        }

    fun query(jobId: String): CliModelJobRecord? =
        synchronized(lock) {
            store.expireEvidence(clock())
            store.load(jobId)
        }

    fun readProgress(
        jobId: String,
        offset: Int,
    ): List<ModelEvent> =
        synchronized(lock) {
            require(offset >= 0)
            if (activeJobId != jobId) emptyList() else progress.read(offset)
        }

    fun cancel(jobId: String): CliModelJobRecord? =
        synchronized(lock) {
            val record = store.load(jobId) ?: return@synchronized null
            if (record.state.terminal) return@synchronized record
            if (activeJobId == jobId) cancelExecution()
            record.copy(state = CliModelJobState.CANCELLED, terminalAtEpochMillis = clock()).also(store::put)
        }

    fun prepareReconcile(jobId: String): CodexReconcile? =
        synchronized(lock) {
            store.expireEvidence(clock())
            val record = store.load(jobId) ?: return@synchronized null
            val payload =
                if (record.state == CliModelJobState.SUCCEEDED && record.reconciledAtEpochMillis == null) {
                    store.loadOutput(jobId)?.also { require(sha256(it) == record.outputSha256) }
                } else {
                    null
                }
            CodexReconcile(record, payload)
        }

    fun acknowledgeResult(
        jobId: String,
        identity: String,
    ): CliModelJobRecord? =
        synchronized(lock) {
            val record = store.load(jobId)
            if (record == null || !record.state.terminal ||
                identity != record.requestSha256 + ":" + record.outputSha256.orEmpty()
            ) {
                null
            } else if (record.reconciledAtEpochMillis != null) {
                record
            } else {
                store.finishReconcile(record, clock())
            }
        }

    fun finishReconcile(record: CliModelJobRecord): CliModelJobRecord =
        synchronized(lock) {
            store.finishReconcile(record, clock())
        }

    override fun close() {
        synchronized(lock) {
            activeJobId?.let(::cancel)
            worker.shutdownNow()
        }
    }

    private fun runJob(pending: CliModelJobRecord) {
        synchronized(lock) {
            val live = store.load(pending.jobId)
            if (live == null || live.state != CliModelJobState.PENDING) {
                if (activeJobId == pending.jobId) activeJobId = null
                return
            }
            store.put(live.copy(state = CliModelJobState.RUNNING))
        }
        val result =
            runCatching {
                val bytes = store.loadRequest(pending.jobId)
                executeStreaming?.invoke(bytes) { chunk ->
                    synchronized(lock) {
                        if (activeJobId == pending.jobId && store.load(pending.jobId)?.state?.terminal == false) {
                            progress.append(chunk)
                        }
                    }
                } ?: execute(bytes)
            }.mapCatching { execution ->
                // Malformed output must settle this job, not leave the runner permanently busy.
                execution to CliModelEventCodec.encode(execution.events)
            }
        synchronized(lock) {
            val live = store.load(pending.jobId)
            if (live != null && !live.state.terminal && result.isSuccess) {
                val (execution, output) = result.getOrThrow()
                store.putOutput(live.jobId, output)
                store.put(
                    live.copy(
                        state = CliModelJobState.SUCCEEDED,
                        terminalAtEpochMillis = clock(),
                        model = execution.model,
                        outputSha256 = sha256(output),
                    ),
                )
            } else if (live != null && !live.state.terminal) {
                store.put(live.copy(state = CliModelJobState.FAILED, terminalAtEpochMillis = clock()))
            }
            if (activeJobId == pending.jobId) activeJobId = null
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

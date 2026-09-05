package com.helix.runtime.cli.app

import com.helix.runtime.cli.client.CliModelEventCodec
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliModelRequestCodec
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class CodexPayloadJobStore(private val root: File) {
    private val records = CodexModelJobStore(File(root, "provider-v1"))
    private val jobs = File(File(root, "provider-v1"), "codex-model-jobs")

    fun load(jobId: String) = records.load(jobId)
    fun put(record: CliModelJobRecord) = records.put(record)
    fun canAcceptNew(incomingBytes: Int) =
        incomingBytes in 1..CliModelRequestCodec.MAX_BYTES &&
            records.canAcceptNew() &&
            payloadBytes() + incomingBytes <= MAX_PAYLOAD_BYTES
    fun recoverInterrupted(now: Long) = records.recoverInterrupted(now)

    fun putRequest(jobId: String, bytes: ByteArray) = atomicWrite(file(jobId, REQUEST), bytes, CliModelRequestCodec.MAX_BYTES)
    fun loadRequest(jobId: String): ByteArray = file(jobId, REQUEST).readBytes().also {
        require(it.isNotEmpty() && it.size <= CliModelRequestCodec.MAX_BYTES)
    }
    fun putOutput(jobId: String, bytes: ByteArray) = atomicWrite(file(jobId, OUTPUT), bytes, CliModelEventCodec.MAX_BYTES)
    fun loadOutput(jobId: String): ByteArray? = file(jobId, OUTPUT).takeIf(File::isFile)?.readBytes()?.also {
        require(it.isNotEmpty() && it.size <= CliModelEventCodec.MAX_BYTES)
    }
    fun finishReconcile(record: CliModelJobRecord, now: Long): CliModelJobRecord {
        requireDeleted(file(record.jobId, REQUEST))
        requireDeleted(file(record.jobId, OUTPUT))
        return record.copy(reconciledAtEpochMillis = now).also(::put)
    }

    private fun atomicWrite(target: File, bytes: ByteArray, limit: Int) {
        require(bytes.isNotEmpty() && bytes.size <= limit)
        require(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
        require(tmp.renameTo(target) || runCatching { tmp.copyTo(target, overwrite = true); tmp.delete() }.isSuccess)
    }

    private fun payloadBytes(): Long = jobs.walkTopDown().filter(File::isFile)
        .filter { it.name == REQUEST || it.name == OUTPUT }.sumOf(File::length)
    private fun requireDeleted(target: File) {
        require(!target.exists() || target.delete()) { "failed to delete reconciled payload" }
    }
    private fun file(jobId: String, name: String) = File(File(jobs, jobId), name)

    companion object {
        const val MAX_PAYLOAD_BYTES = 8L * 1024L * 1024L
        private const val REQUEST = "request.json"
        private const val OUTPUT = "events.json"
    }
}

internal sealed interface CodexPayloadSubmit {
    data class Accepted(val record: CliModelJobRecord) : CodexPayloadSubmit
    data class Duplicate(val record: CliModelJobRecord) : CodexPayloadSubmit
    data object RequestMismatch : CodexPayloadSubmit
    data object Busy : CodexPayloadSubmit
    data object JournalFull : CodexPayloadSubmit
}

internal data class CodexReconcile(val record: CliModelJobRecord, val payload: ByteArray?)

internal class CodexPayloadJobRunner(
    private val store: CodexPayloadJobStore,
    private val execute: (ByteArray) -> CodexModelExecution,
    private val cancelExecution: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
) : AutoCloseable {
    private val lock = Any()
    private var activeJobId: String? = null

    init { store.recoverInterrupted(clock()) }

    fun submit(jobId: String, requestSha256: String, payload: ByteArray): CodexPayloadSubmit = synchronized(lock) {
        store.load(jobId)?.let { existing ->
            return@synchronized if (existing.requestSha256 == requestSha256) CodexPayloadSubmit.Duplicate(existing)
            else CodexPayloadSubmit.RequestMismatch
        }
        require(sha256(payload) == requestSha256) { "request hash mismatch" }
        CliModelRequestCodec.decode(payload)
        if (activeJobId != null) return@synchronized CodexPayloadSubmit.Busy
        if (!store.canAcceptNew(payload.size)) return@synchronized CodexPayloadSubmit.JournalFull
        store.putRequest(jobId, payload)
        val pending = CliModelJobRecord(jobId, requestSha256, CliModelJobState.PENDING, clock())
        store.put(pending)
        activeJobId = jobId
        worker.submit { runJob(pending) }
        CodexPayloadSubmit.Accepted(pending)
    }

    fun query(jobId: String): CliModelJobRecord? = synchronized(lock) { store.load(jobId) }

    fun cancel(jobId: String): CliModelJobRecord? = synchronized(lock) {
        val record = store.load(jobId) ?: return@synchronized null
        if (record.state.terminal) return@synchronized record
        if (activeJobId == jobId) cancelExecution()
        record.copy(state = CliModelJobState.CANCELLED, terminalAtEpochMillis = clock()).also(store::put)
    }

    fun prepareReconcile(jobId: String): CodexReconcile? = synchronized(lock) {
        val record = store.load(jobId) ?: return@synchronized null
        val payload = if (record.state == CliModelJobState.SUCCEEDED && record.reconciledAtEpochMillis == null) {
            store.loadOutput(jobId)?.also { require(sha256(it) == record.outputSha256) }
        } else null
        CodexReconcile(record, payload)
    }

    fun finishReconcile(record: CliModelJobRecord): CliModelJobRecord = synchronized(lock) {
        store.finishReconcile(record, clock())
    }

    override fun close() {
        synchronized(lock) { activeJobId?.let(::cancel); worker.shutdownNow() }
    }

    private fun runJob(pending: CliModelJobRecord) {
        synchronized(lock) {
            val live = store.load(pending.jobId)
            if (live == null || live.state != CliModelJobState.PENDING) { clear(pending.jobId); return }
            store.put(live.copy(state = CliModelJobState.RUNNING))
        }
        val result = runCatching { execute(store.loadRequest(pending.jobId)) }
        synchronized(lock) {
            val live = store.load(pending.jobId)
            if (live != null && !live.state.terminal && result.isSuccess) {
                val execution = result.getOrThrow()
                val output = CliModelEventCodec.encode(execution.events)
                store.putOutput(live.jobId, output)
                store.put(live.copy(
                    state = CliModelJobState.SUCCEEDED, terminalAtEpochMillis = clock(),
                    model = execution.model, outputSha256 = sha256(output),
                ))
            } else if (live != null && !live.state.terminal) {
                store.put(live.copy(state = CliModelJobState.FAILED, terminalAtEpochMillis = clock()))
            }
            clear(pending.jobId)
        }
    }

    private fun clear(jobId: String) { if (activeJobId == jobId) activeJobId = null }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

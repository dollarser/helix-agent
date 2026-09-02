package com.helix.runtime.quickjs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Cancellation token for an execution. HXA-053 wires the Dispatcher's own signal. */
fun interface JsCancellation {
    fun isCancelled(): Boolean
}

/**
 * HXA-051 execution parameters for [JsExecutionClient.execute].
 *
 * [source] is evaluated verbatim in the isolated instance (input-injection wrapping is
 * HXA-052's concern; this task transports [inputJsonUtf8] per doc 03 §3.1 and the service
 * size-checks + hashes it). Payloads above [JsProtocol.PARCEL_INLINE_MAX_BYTES] are
 * transparently moved to read-only `ParcelFileDescriptor`s by the client. [outputFile],
 * when present, receives the full result bytes (write PFD); the client re-materializes
 * them into the returned [JsExecutionResult].
 *
 * [debugInjectCrash]/[debugCrashAfterMs] arm the test-only crash seam — they are inert
 * unless both this flag and `BuildConfig.DEBUG` are set, and only instrumented tests set
 * them (HXA-053's production path never does).
 *
 * Concurrency is 1 by contract (doc 03 §4.1); the caller (Dispatcher, HXA-053)
 * serializes executions. This client never retries or replays an execution.
 */
data class JsExecuteParams(
    val executionId: String,
    val source: String,
    val inputJsonUtf8: ByteArray? = null,
    val limits: JsExecutionLimits = JsExecutionLimits.DEFAULTS,
    val outputFile: File? = null,
    val debugInjectCrash: Boolean = false,
    val debugCrashAfterMs: Long = CRASH_SEAM_DEFAULT_DELAY_MS,
) {
    companion object {
        const val CRASH_SEAM_DEFAULT_DELAY_MS: Long = 250L
    }
}

/**
 * HXA-051 main-process client for the QuickJS execution protocol (doc 03 §4).
 *
 * One [execute] call = one execution = one unique `bindIsolatedService` instance
 * ([JsInstanceName] derived from the execution ID). The call blocks until a stable
 * [JsExecutionResult] with exactly one status:
 *
 * - pre-flight failures (limits, sizes, blank ID, pre-start cancel) are rejected
 *   BEFORE any bind, so no isolated process is spawned;
 * - bind failures → [JsExecutionStatus.BIND_FAILED];
 * - in-flight cancel → the client sends the interrupt transaction and reports the
 *   service's [JsExecutionStatus.INTERRUPTED] (never a blind retry);
 * - timeout → the client waits to the monotonic deadline, sends the interrupt
 *   ("timeout first interrupts"), then gives up on the Binder interaction after a 1 s
 *   grace window, unbinds, and returns [JsExecutionStatus.TIMEOUT] (system reclamation
 *   takes over; no `killProcess`/`System.exit` anywhere in this control plane);
 * - Binder death mid-execution → [JsExecutionStatus.CRASHED] (outcome unknown, no
 *   replay; a later execution uses a fresh instance);
 * - anything unclassifiable → [JsExecutionStatus.UNKNOWN]. The catch-all never returns
 *   success.
 *
 * Every wait is bounded; every resource (bind, death link, PFDs, temp files) is released
 * in the `finally` path.
 */
@Suppress("TooManyFunctions") // one method per protocol phase (preflight / transport / bind / wait / finalize)
class JsExecutionClient(
    private val context: Context,
) {
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // one return per phase; catch-all is fail-closed
    fun execute(
        params: JsExecuteParams,
        cancellation: JsCancellation? = null,
    ): JsExecutionResult {
        val inputSha = params.inputJsonUtf8?.let(JsHash::sha256Hex) ?: ""
        val preflight = preflightReject(params, cancellation, inputSha)
        if (preflight != null) return preflight

        val limits = params.limits.validate()
        val sourceBytes = params.source.toByteArray(StandardCharsets.UTF_8)
        val inputBytes = params.inputJsonUtf8
        val crashSeam = BuildConfig.DEBUG && params.debugInjectCrash
        val deadlineNanos = System.nanoTime() + limits.timeoutMs * NANOS_PER_MS

        val tempFiles = mutableListOf<File>()
        val pfdHolders = mutableListOf<ParcelFileDescriptor>()
        var bound: BoundInstance? = null
        try {
            val transport = prepareTransport(params, sourceBytes, inputBytes, tempFiles, pfdHolders)
            val request =
                JsExecutionRequest(
                    executionId = params.executionId,
                    sourceUtf8 = transport.inlineSource,
                    inputJsonUtf8 = transport.inlineInput,
                    limits = limits,
                    deadlineNanos = deadlineNanos,
                )
            val envelope =
                JsExecutionWire.ExecuteEnvelope(
                    sourcePfd = transport.sourcePfd,
                    inputPfd = transport.inputPfd,
                    outputPfd = transport.outputPfd,
                    sourceTotalBytes = sourceBytes.size,
                    inputTotalBytes = (inputBytes?.size ?: 0).toLong(),
                    flags = if (crashSeam) JsProtocol.FLAG_CRASH_INJECTION else 0,
                    crashAfterMs = params.debugCrashAfterMs.toInt(),
                )

            val boundInstance =
                bindInstance(params.executionId, cancellation, inputSha)
                    ?: return JsExecutionResult.clientFailure(
                        params.executionId,
                        JsExecutionStatus.BIND_FAILED,
                        "bindIsolatedService failed for ${JsInstanceName.forExecution(params.executionId)}",
                        inputSha,
                    )
            bound = boundInstance

            val outcome = awaitResult(boundInstance, request, envelope, deadlineNanos, cancellation, inputSha)
            return finalizeResult(outcome, params, inputSha)
        } catch (e: JsClientFailure) {
            return e.result
        } catch (e: Throwable) {
            // Fail closed: an unexpected client-side exception (IO, protocol, OOM) is a
            // stable UNKNOWN, never a success and never an unhandled throw.
            return clientUnknown(params.executionId, inputSha, e)
        } finally {
            bound?.let { release(it) }
            pfdHolders.forEach { it.closeQuietly() }
            tempFiles.forEach { it.delete() }
        }
    }

    /** Carries a pre-built failure result through the `finally` cleanup without a throw. */
    private class JsClientFailure(
        val result: JsExecutionResult,
    ) : RuntimeException("client failure result")

    private data class Transport(
        val sourcePfd: ParcelFileDescriptor?,
        val inputPfd: ParcelFileDescriptor?,
        val outputPfd: ParcelFileDescriptor?,
        val inlineSource: ByteArray,
        val inlineInput: ByteArray,
    )

    /**
     * Transport assembly (doc 03 §3.1): payloads above the inline parcel cap move to
     * read-only PFDs over app-private temp files; [JsExecuteParams.outputFile] becomes
     * the caller-writable output PFD. All created resources are registered in
     * [tempFiles]/[pfdHolders] so the `finally` path releases them unconditionally.
     */
    private fun prepareTransport(
        params: JsExecuteParams,
        sourceBytes: ByteArray,
        inputBytes: ByteArray?,
        tempFiles: MutableList<File>,
        pfdHolders: MutableList<ParcelFileDescriptor>,
    ): Transport {
        val sourcePfd: ParcelFileDescriptor?
        val inlineSource: ByteArray
        if (sourceBytes.size > JsProtocol.PARCEL_INLINE_MAX_BYTES) {
            val tmp = materializeTemp(params.executionId, "source", sourceBytes, tempFiles)
            sourcePfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            pfdHolders += sourcePfd
            inlineSource = ByteArray(0)
        } else {
            sourcePfd = null
            inlineSource = sourceBytes
        }
        val inputPfd: ParcelFileDescriptor?
        val inlineInput: ByteArray
        if (inputBytes != null && inputBytes.size > JsProtocol.PARCEL_INLINE_MAX_BYTES) {
            val tmp = materializeTemp(params.executionId, "input", inputBytes, tempFiles)
            inputPfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            pfdHolders += inputPfd
            inlineInput = ByteArray(0)
        } else {
            inputPfd = null
            inlineInput = inputBytes ?: ByteArray(0)
        }
        var outputPfd: ParcelFileDescriptor? = null
        if (params.outputFile != null) {
            val target = params.outputFile.absoluteFile
            target.parentFile?.mkdirs()
            outputPfd =
                ParcelFileDescriptor.open(
                    target,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                )
            pfdHolders += outputPfd
        }
        return Transport(sourcePfd, inputPfd, outputPfd, inlineSource, inlineInput)
    }

    /**
     * Pre-flight rejections (doc 03 §4.1): everything the client can decide BEFORE
     * binding is rejected here, so a rejected execution never spawns an isolated
     * process. Returns null when the execution may proceed.
     */
    private fun preflightReject(
        params: JsExecuteParams,
        cancellation: JsCancellation?,
        inputSha: String,
    ): JsExecutionResult? {
        val limitsError: String? =
            try {
                params.limits.validate()
                null
            } catch (e: IllegalArgumentException) {
                "invalid limits: ${e.message}"
            }
        val sizeError = preflightSizeReject(params, params.limits)
        return when {
            cancellation?.isCancelled() == true -> {
                rejection(params, JsExecutionStatus.CANCELLED, "cancelled before start", inputSha)
            }

            params.executionId.isBlank() -> {
                rejection(params, JsExecutionStatus.REQUEST_REJECTED, "blank executionId", inputSha)
            }

            limitsError != null -> {
                rejection(params, JsExecutionStatus.REQUEST_REJECTED, limitsError, inputSha)
            }

            sizeError != null -> {
                rejection(params, JsExecutionStatus.REQUEST_REJECTED, sizeError, inputSha)
            }

            params.debugInjectCrash && !BuildConfig.DEBUG -> {
                rejection(
                    params,
                    JsExecutionStatus.REQUEST_REJECTED,
                    "crash-injection seam is disabled outside debug builds",
                    inputSha,
                )
            }

            else -> {
                null
            }
        }
    }

    private fun preflightSizeReject(
        params: JsExecuteParams,
        limits: JsExecutionLimits,
    ): String? {
        val inputBytes = params.inputJsonUtf8
        return when {
            params.source.toByteArray(StandardCharsets.UTF_8).size > limits.maxSourceBytes -> {
                "source exceeds maxSourceBytes ${limits.maxSourceBytes}"
            }

            inputBytes != null && inputBytes.size > limits.maxInputBytes -> {
                "input ${inputBytes.size} exceeds maxInputBytes ${limits.maxInputBytes}"
            }

            else -> {
                null
            }
        }
    }

    private fun rejection(
        params: JsExecuteParams,
        status: JsExecutionStatus,
        detail: String,
        inputSha: String,
    ): JsExecutionResult = JsExecutionResult.clientFailure(params.executionId, status, detail, inputSha)

    private data class BoundInstance(
        val binder: IBinder,
        val connection: ServiceConnection,
        val dead: AtomicBoolean,
        val deathRecipient: IBinder.DeathRecipient,
    )

    @Suppress("SwallowedException") // a raced death is recorded on the dead flag, not an error
    private fun bindInstance(
        executionId: String,
        cancellation: JsCancellation?,
        inputSha: String,
    ): BoundInstance? {
        val instanceName = JsInstanceName.forExecution(executionId)
        val connected = CountDownLatch(1)
        val binderHolder = AtomicReference<IBinder?>(null)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    binderHolder.set(service)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val bindAccepted =
            context.bindIsolatedService(
                Intent(context, JsExecutionService::class.java),
                Context.BIND_AUTO_CREATE,
                instanceName,
                context.mainExecutor,
                connection,
            )
        if (!bindAccepted) return null
        val startedAt = System.nanoTime()
        while (!connected.await(POLL_MS, TimeUnit.MILLISECONDS)) {
            if (cancellation?.isCancelled() == true) {
                bindFailure(executionId, JsExecutionStatus.CANCELLED, "cancelled during bind", inputSha)
            }
            if (System.nanoTime() - startedAt > BIND_TIMEOUT_MS * NANOS_PER_MS) {
                bindFailure(
                    executionId,
                    JsExecutionStatus.BIND_FAILED,
                    "service did not connect within ${BIND_TIMEOUT_MS} ms",
                    inputSha,
                )
            }
        }
        val binder =
            binderHolder.get()
                ?: bindFailure(
                    executionId,
                    JsExecutionStatus.BIND_FAILED,
                    "onServiceConnected delivered no binder",
                    inputSha,
                )
        val dead = AtomicBoolean(false)
        val deathRecipient =
            object : IBinder.DeathRecipient {
                override fun binderDied() {
                    dead.set(true)
                }
            }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: DeadObjectException) {
            dead.set(true)
        }
        return BoundInstance(binder, connection, dead, deathRecipient)
    }

    /** A bind-phase failure carries a pre-built failure result through the finally cleanup. */
    private fun bindFailure(
        executionId: String,
        status: JsExecutionStatus,
        detail: String,
        inputSha: String,
    ): Nothing = throw JsClientFailure(JsExecutionResult.clientFailure(executionId, status, detail, inputSha))

    private fun release(bound: BoundInstance) {
        runCatching { bound.binder.unlinkToDeath(bound.deathRecipient, 0) }
        runCatching { context.unbindService(bound.connection) }
    }

    /**
     * The bounded wait: worker thread blocks on the EXECUTE transact; this thread polls
     * until the monotonic deadline, watching cancellation (→ interrupt transaction) and
     * death. After the deadline a 1 s grace window lets the service's deadline-interrupt
     * reply land; then the client gives up on the Binder interaction entirely.
     */
    private fun awaitResult(
        bound: BoundInstance,
        request: JsExecutionRequest,
        envelope: JsExecutionWire.ExecuteEnvelope,
        deadlineNanos: Long,
        cancellation: JsCancellation?,
        inputSha: String,
    ): JsExecutionResult {
        val workerDone = CountDownLatch(1)
        val workerHolder = AtomicReference<Any?>(UNSTARTED)
        launchExecuteWorker(bound, request, envelope, workerDone, workerHolder)

        var interruptSent = false
        var cancelledAtNanos: Long? = null
        var giveUp = false
        val deadlineGiveUpAt = deadlineNanos + GIVE_UP_GRACE_MS * NANOS_PER_MS
        while (!giveUp && !workerDone.await(POLL_MS, TimeUnit.MILLISECONDS)) {
            val now = System.nanoTime()
            if (cancellation?.isCancelled() == true && cancelledAtNanos == null) {
                // Cancel in flight: deliver the interrupt once, then bound the wait. The
                // engine interrupts at the next poll (HXA-050-verified mechanism), so a
                // short grace suffices; a non-replying live instance degrades to a
                // synthesized INTERRUPTED (interrupt was delivered, never a retry).
                if (sendInterrupt(bound)) {
                    interruptSent = true
                    cancelledAtNanos = now
                } else {
                    giveUp = true
                }
            }
            if (now >= deadlineNanos && !interruptSent && sendInterrupt(bound)) {
                interruptSent = true
            }
            val cancelGiveUpAt = cancelledAtNanos?.plus(CANCEL_GRACE_MS * NANOS_PER_MS) ?: Long.MAX_VALUE
            if (now >= deadlineGiveUpAt || now >= cancelGiveUpAt) giveUp = true
        }
        return classifyOutcome(workerHolder.get(), bound, request.executionId, inputSha, cancelledAtNanos)
    }

    /** Blocks the EXECUTE transact off the calling thread so the wait loop stays responsive. */
    @Suppress("TooGenericExceptionCaught") // worker thread boundary: every failure becomes an observable result
    private fun launchExecuteWorker(
        bound: BoundInstance,
        request: JsExecutionRequest,
        envelope: JsExecutionWire.ExecuteEnvelope,
        workerDone: CountDownLatch,
        workerHolder: AtomicReference<Any?>,
    ) {
        Thread(
            {
                try {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        JsExecutionWire.writeExecute(data, request, envelope)
                        bound.binder.transact(JsProtocol.CODE_EXECUTE, data, reply, 0)
                        workerHolder.set(JsExecutionWire.readResult(reply))
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                } catch (t: DeadObjectException) {
                    bound.dead.set(true)
                    workerHolder.set(t)
                } catch (t: RemoteException) {
                    bound.dead.set(true)
                    workerHolder.set(t)
                } catch (t: Throwable) {
                    workerHolder.set(t)
                } finally {
                    workerDone.countDown()
                }
            },
            "helix-js-client-execute",
        ).apply { isDaemon = true }.start()
    }

    /** Maps the worker's terminal state to the closed status set (the catch-all never succeeds). */
    private fun classifyOutcome(
        holder: Any?,
        bound: BoundInstance,
        executionId: String,
        inputSha: String,
        cancelledAtNanos: Long?,
    ): JsExecutionResult =
        when {
            holder is JsExecutionResult -> {
                holder
            }

            holder is DeadObjectException || holder is RemoteException || bound.dead.get() -> {
                clientCrashed(executionId, inputSha)
            }

            holder is Throwable -> {
                clientUnknown(executionId, inputSha, holder)
            }

            bound.dead.get() -> {
                clientCrashed(executionId, inputSha)
            }

            cancelledAtNanos != null -> {
                JsExecutionResult.clientFailure(
                    executionId,
                    JsExecutionStatus.INTERRUPTED,
                    "interrupt delivered to live instance; reply not received within grace",
                    inputSha,
                )
            }

            else -> {
                JsExecutionResult.clientFailure(
                    executionId,
                    JsExecutionStatus.TIMEOUT,
                    "no reply within deadline + ${GIVE_UP_GRACE_MS} ms grace; instance abandoned to unbind/reclamation",
                    inputSha,
                )
            }
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // failed transact = binder died; dead flag
    private fun sendInterrupt(bound: BoundInstance): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            bound.binder.transact(JsProtocol.CODE_INTERRUPT, data, reply, 0)
            return true
        } catch (t: Throwable) {
            bound.dead.set(true)
            return false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun clientCrashed(
        executionId: String,
        inputSha: String,
    ): JsExecutionResult =
        JsExecutionResult.clientFailure(
            executionId,
            JsExecutionStatus.CRASHED,
            "isolated service process died; outcome unknown — the same execution is never replayed",
            inputSha,
        )

    private fun clientUnknown(
        executionId: String,
        inputSha: String,
        error: Throwable,
    ): JsExecutionResult =
        JsExecutionResult.clientFailure(
            executionId,
            JsExecutionStatus.UNKNOWN,
            "client-side protocol failure: $error",
            inputSha,
        )

    /**
     * Materializes the full output for the caller: when an [JsExecuteParams.outputFile]
     * was provided the service wrote the result through the PFD, so the client reads the
     * file back and verifies size + SHA-256 before reporting SUCCESS (fail-closed to
     * UNKNOWN on any mismatch).
     */
    private fun finalizeResult(
        result: JsExecutionResult,
        params: JsExecuteParams,
        inputSha: String,
    ): JsExecutionResult {
        if (result.status != JsExecutionStatus.SUCCESS) return result
        return try {
            val bytes =
                if (params.outputFile != null) {
                    readBounded(params.outputFile.absoluteFile, result.outputBytes)
                } else {
                    result.outputUtf8
                }
            if (bytes.size.toLong() != result.outputBytes) {
                throw IOException("output size ${bytes.size} != declared ${result.outputBytes}")
            }
            if (JsHash.sha256Hex(bytes) != result.outputSha256Hex) {
                throw IOException("output SHA-256 mismatch")
            }
            JsExecutionResult(
                executionId = result.executionId,
                status = result.status,
                outputUtf8 = bytes,
                outputBytes = result.outputBytes,
                outputSha256Hex = result.outputSha256Hex,
                inputSha256Hex = result.inputSha256Hex,
                detail = result.detail,
                servicePid = result.servicePid,
                serviceUid = result.serviceUid,
            )
        } catch (e: IOException) {
            clientUnknown(result.executionId, inputSha, e)
        }
    }

    private fun materializeTemp(
        executionId: String,
        tag: String,
        bytes: ByteArray,
        tempFiles: MutableList<File>,
    ): File {
        val tmp = File(context.cacheDir, "js-exec-${executionId.take(128)}-$tag.tmp")
        tmp.writeBytes(bytes)
        tempFiles += tmp
        return tmp
    }

    private fun readBounded(
        file: File,
        expectedBytes: Long,
    ): ByteArray {
        val bytes = file.readBytes()
        if (bytes.size.toLong() != expectedBytes) {
            throw IOException("output file has ${bytes.size} bytes, expected $expectedBytes")
        }
        return bytes
    }

    companion object {
        private const val NANOS_PER_MS: Long = 1_000_000L

        /** Bounded bind/connect wait (HXA-050 probe: cold isolated starts finish well inside this). */
        const val BIND_TIMEOUT_MS: Long = 15_000L

        /** Doc 03 §4.5: after the deadline, give up on the Binder interaction after 1 s. */
        const val GIVE_UP_GRACE_MS: Long = 1_000L

        /** After an in-flight cancel interrupt, give up on the reply after 2 s. */
        const val CANCEL_GRACE_MS: Long = 2_000L

        private const val POLL_MS: Long = 50L

        private val UNSTARTED = Object()
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    runCatching { close() }
}

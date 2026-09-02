package com.helix.runtime.quickjs

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import app.cash.zipline.InterruptHandler
import app.cash.zipline.QuickJs
import app.cash.zipline.QuickJsException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * HXA-051 production QuickJS execution service (architecture doc local-code-execution
 * §2.2/§4). Replaces the HXA-050 `SpikeIsolatedService` (same manifest shape, production
 * protocol).
 *
 * Process model (doc 03 §2.2): `isolatedProcess=true`, `exported=false`,
 * `stopWithTask=false`, no permissions, no host bridge. Every execution binds a UNIQUE
 * instance through `Context.bindIsolatedService` with an instance name derived from the
 * execution ID ([JsInstanceName]); each instance serves EXACTLY ONE execution for its
 * lifetime (one-shot slot), so no QuickJS state is ever shared across tasks (doc 03 §4.1).
 *
 * Execution control (doc 03 §4): a brand-new `QuickJs` instance is created ON THE
 * DEDICATED EXECUTION THREAD (native stack 16 MiB) — the thread-baseline constraint
 * pinned by ADR-0015 — with `memoryLimit` and an `InterruptHandler` (monotonic deadline
 * + client interrupt flag) set before `evaluate`, and closed on the same thread.
 *
 * Normal control plane is exclusively: interrupt transaction, deadline-driven interrupt,
 * unbind + system reclamation. This service NEVER calls `killProcess`/`System.exit` as
 * control flow. The only exception is the DEBUG-gated, explicitly-flagged crash-injection
 * seam used by instrumented crash tests (see [startCrashSeam]); release builds compile it
 * out and HXA-053's production tool path never sets the flag.
 */
class JsExecutionService : Service() {
    private val binder = ExecutionBinder()

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * One instance per bound `bindIsolatedService` call; one execution per instance.
     * The binder (and therefore this class) lives in the isolated process.
     */
    @Suppress("TooManyFunctions") // one method per protocol phase
    private class ExecutionBinder : Binder() {
        private val slot = AtomicReference<SlotState>(SlotState.IDLE)
        private val interruptRequested = AtomicBoolean(false)

        @Suppress("TooGenericExceptionCaught") // fail-closed: never let a transaction failure kill the process
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean =
            when (code) {
                JsProtocol.CODE_INFO -> {
                    val r = requireNotNull(reply) { "INFO reply parcel is null" }
                    JsExecutionWire.writeInfo(r, Process.myPid(), Process.myUid())
                    true
                }

                JsProtocol.CODE_EXECUTE -> {
                    val r = requireNotNull(reply) { "EXECUTE reply parcel is null" }
                    try {
                        handleExecute(data, r, interruptRequested)
                    } catch (t: Throwable) {
                        // Defensive backstop: the reply must ALWAYS carry a stable status.
                        // An unexpected failure degrades to UNKNOWN, never to success.
                        JsExecutionWire.writeResult(
                            r,
                            JsExecutionResult.serviceFailure(
                                executionId = "",
                                status = JsExecutionStatus.UNKNOWN,
                                detail = "service internal failure: $t",
                                inputSha256Hex = "",
                                servicePid = Process.myPid(),
                                serviceUid = Process.myUid(),
                            ),
                        )
                    }
                    true
                }

                JsProtocol.CODE_INTERRUPT -> {
                    interruptRequested.set(true)
                    true
                }

                else -> {
                    super.onTransact(code, data, reply, flags)
                }
            }

        @Suppress("ReturnCount", "TooGenericExceptionCaught") // one return per distinct rejection path
        private fun handleExecute(
            data: Parcel,
            reply: Parcel,
            interruptRequested: AtomicBoolean,
        ) {
            val (request, envelope) =
                runCatching { JsExecutionWire.readExecute(data) }
                    .getOrElse { t: Throwable ->
                        replyWith(reply, JsExecutionStatus.REQUEST_REJECTED, "malformed EXECUTE parcel: $t", "", null)
                        return
                    }
            val rejection = validateRequest(request, envelope)
            if (rejection != null) {
                markUsed()
                replyWith(reply, JsExecutionStatus.REQUEST_REJECTED, rejection, "", request)
                return
            }
            val acquired = slot.compareAndSet(SlotState.IDLE, SlotState.RUNNING)
            if (!acquired) {
                val reason =
                    if (slot.get() == SlotState.RUNNING) {
                        "instance busy"
                    } else {
                        "instance already used; bind a new instance name"
                    }
                replyWith(reply, JsExecutionStatus.REQUEST_REJECTED, reason, "", request)
                return
            }
            runPayloadAndExecute(request, envelope, reply, interruptRequested)
        }

        @Suppress("TooGenericExceptionCaught") // payload read must fail closed on any I/O error
        private fun runPayloadAndExecute(
            request: JsExecutionRequest,
            envelope: JsExecutionWire.ExecuteEnvelope,
            reply: Parcel,
            interruptRequested: AtomicBoolean,
        ) {
            var sourcePfd: ParcelFileDescriptor? = null
            var inputPfd: ParcelFileDescriptor? = null
            var outputPfd: ParcelFileDescriptor? = null
            try {
                sourcePfd = envelope.sourcePfd
                inputPfd = envelope.inputPfd
                outputPfd = envelope.outputPfd
                val sourceBytes: ByteArray
                val inputBytes: ByteArray
                try {
                    sourceBytes =
                        if (sourcePfd != null) {
                            readBounded(sourcePfd, envelope.sourceTotalBytes.toLong())
                        } else {
                            request.sourceUtf8
                        }
                    inputBytes =
                        if (inputPfd != null) readBounded(inputPfd, envelope.inputTotalBytes) else request.inputJsonUtf8
                } catch (t: Throwable) {
                    replyWith(
                        reply,
                        JsExecutionStatus.REQUEST_REJECTED,
                        "payload unreadable: $t",
                        "",
                        request,
                    )
                    return
                }
                if ((envelope.flags and JsProtocol.FLAG_CRASH_INJECTION) != 0) {
                    startCrashSeam(envelope.crashAfterMs)
                }
                val executed = runOnExecutionThread(request, sourceBytes, inputBytes, outputPfd, interruptRequested)
                replyWith(reply, executed.status, executed.detail, JsHash.sha256Hex(inputBytes), request, executed)
            } finally {
                markUsed()
                sourcePfd?.closeQuietly()
                inputPfd?.closeQuietly()
                outputPfd?.closeQuietly()
            }
        }

        private fun replyWith(
            reply: Parcel,
            status: JsExecutionStatus,
            detail: String,
            inputSha256Hex: String,
            request: JsExecutionRequest?,
            executed: JsExecutionResult? = null,
        ) {
            val result =
                executed
                    ?: JsExecutionResult.serviceFailure(
                        executionId = request?.executionId ?: "",
                        status = status,
                        detail = detail,
                        inputSha256Hex = inputSha256Hex,
                        servicePid = Process.myPid(),
                        serviceUid = Process.myUid(),
                    )
            JsExecutionWire.writeResult(reply, result)
        }

        private fun markUsed() {
            slot.set(SlotState.DONE)
        }

        /**
         * Validates the request+envelope pair BEFORE any engine work (doc 03 §4.1 limits
         * are enforced here as well as pre-bind on the client). Returns a stable rejection
         * reason, or null when the request is executable.
         */
        @Suppress("ReturnCount") // one return per distinct rejection reason
        private fun validateRequest(
            request: JsExecutionRequest,
            envelope: JsExecutionWire.ExecuteEnvelope,
        ): String? {
            if (request.executionId.isBlank()) return "blank executionId"
            try {
                request.limits.validate()
            } catch (e: IllegalArgumentException) {
                return "invalid limits: ${e.message}"
            }
            if (request.deadlineNanos <= System.nanoTime()) return "deadline already expired"
            if ((envelope.flags and JsProtocol.FLAG_CRASH_INJECTION.inv()) != 0) return "unknown EXECUTE flags"
            return validateSource(request, envelope) ?: validateInput(request, envelope)
        }

        /** PFD/inline consistency and size caps for the source payload. */
        @Suppress("ReturnCount") // one return per distinct rejection reason
        private fun validateSource(
            request: JsExecutionRequest,
            envelope: JsExecutionWire.ExecuteEnvelope,
        ): String? {
            val limits = request.limits
            val inlineSource = request.sourceUtf8
            if (envelope.sourcePfd != null) {
                if (inlineSource.isNotEmpty()) return "source must not be inline when a source PFD is provided"
                if (envelope.sourceTotalBytes < 0) return "negative source length"
                if (envelope.sourceTotalBytes > limits.maxSourceBytes) {
                    return "source ${envelope.sourceTotalBytes} exceeds maxSourceBytes ${limits.maxSourceBytes}"
                }
                return null
            }
            if (envelope.sourceTotalBytes != inlineSource.size) {
                return "source length mismatch (declared ${envelope.sourceTotalBytes}, inline ${inlineSource.size})"
            }
            if (inlineSource.size > limits.maxSourceBytes) {
                return "source ${inlineSource.size} exceeds maxSourceBytes ${limits.maxSourceBytes}"
            }
            if (inlineSource.size > JsProtocol.PARCEL_INLINE_MAX_BYTES) {
                return "inline source above parcel cap; use a source PFD"
            }
            return null
        }

        /** PFD/inline consistency and size caps for the input payload. */
        @Suppress("ReturnCount") // one return per distinct rejection reason
        private fun validateInput(
            request: JsExecutionRequest,
            envelope: JsExecutionWire.ExecuteEnvelope,
        ): String? {
            val limits = request.limits
            val inlineInput = request.inputJsonUtf8
            if (envelope.inputPfd != null) {
                if (inlineInput.isNotEmpty()) return "input must not be inline when an input PFD is provided"
                if (envelope.inputTotalBytes < 0) return "negative input length"
                if (envelope.inputTotalBytes > limits.maxInputBytes) {
                    return "input ${envelope.inputTotalBytes} exceeds maxInputBytes ${limits.maxInputBytes}"
                }
                return null
            }
            if (envelope.inputTotalBytes != inlineInput.size.toLong()) {
                return "input length mismatch (declared ${envelope.inputTotalBytes}, inline ${inlineInput.size})"
            }
            if (inlineInput.size > limits.maxInputBytes) {
                return "input ${inlineInput.size} exceeds maxInputBytes ${limits.maxInputBytes}"
            }
            if (inlineInput.size > JsProtocol.PARCEL_INLINE_MAX_BYTES) {
                return "inline input above parcel cap; use an input PFD"
            }
            return null
        }

        /** Reads exactly [totalBytes] bytes from a read-only PFD; fails on truncation. */
        private fun readBounded(
            pfd: ParcelFileDescriptor,
            totalBytes: Long,
        ): ByteArray {
            val buffer = ByteArray(PFD_READ_CHUNK_BYTES)
            val out = ByteArrayOutputStream(totalBytes.toInt())
            var remaining = totalBytes
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size, remaining.toInt()))
                    if (n < 0) throw IOException("payload truncated: declared $totalBytes bytes, stream ended early")
                    out.write(buffer, 0, n)
                    remaining -= n
                }
            }
            return out.toByteArray()
        }

        /**
         * Runs the whole QuickJS lifecycle on a dedicated execution thread (ADR-0015
         * thread-baseline constraint: create, evaluate and close on the SAME thread).
         *
         * The thread's native stack is 16 MiB (HXA-050 verified value, comfortably above
         * Zipline's 6 MiB minimum) so a JS `stack overflow` terminates as a JS-level
         * [QuickJsException] instead of a process crash. The wall-time deadline is
         * enforced by the interrupt handler (monotonic clock), so the join is bounded:
         * `timeoutMs + EXECUTION_JOIN_GRACE_MS`.
         */
        @Suppress("ReturnCount", "TooGenericExceptionCaught") // one return per terminal state; thread boundary
        private fun runOnExecutionThread(
            request: JsExecutionRequest,
            sourceBytes: ByteArray,
            inputBytes: ByteArray,
            outputPfd: ParcelFileDescriptor?,
            interruptRequested: AtomicBoolean,
        ): JsExecutionResult {
            val resultHolder = AtomicReference<JsExecutionResult?>(null)
            val errorHolder = AtomicReference<Throwable?>(null)
            val thread =
                Thread(
                    null,
                    {
                        try {
                            resultHolder.set(execute(request, sourceBytes, inputBytes, outputPfd, interruptRequested))
                        } catch (t: Throwable) {
                            errorHolder.set(t)
                        }
                    },
                    EXECUTION_THREAD_NAME,
                    EXECUTION_THREAD_STACK_BYTES,
                )
            thread.start()
            try {
                thread.join(request.limits.timeoutMs + EXECUTION_JOIN_GRACE_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val error = errorHolder.get()
            if (error != null) {
                // A Java StackOverflowError here means Zipline's JS→JVM value conversion
                // overflowed on a cyclic/too-deep result — the output-contract rejection
                // of doc 03 §4.6, not a JS-level error (JS stack overflow is a
                // QuickJsException and stays JS_ERROR).
                val status =
                    if (error is StackOverflowError) JsExecutionStatus.OUTPUT_LIMIT else JsExecutionStatus.UNKNOWN
                val detail =
                    if (error is StackOverflowError) {
                        "result is not JSON-encodable (circular or too deep)"
                    } else {
                        "execution thread failure: $error"
                    }
                return JsExecutionResult.serviceFailure(
                    executionId = request.executionId,
                    status = status,
                    detail = detail,
                    inputSha256Hex = JsHash.sha256Hex(inputBytes),
                    servicePid = Process.myPid(),
                    serviceUid = Process.myUid(),
                )
            }
            val result = resultHolder.get()
            if (result != null) return result
            // The deadline-driven interrupt must have fired long before this join bound;
            // if the thread is somehow still running the instance is abandoned to
            // unbind/reclamation (never killed from here).
            return JsExecutionResult.serviceFailure(
                executionId = request.executionId,
                status = JsExecutionStatus.TIMEOUT,
                detail = "execution thread did not return after interrupt grace",
                inputSha256Hex = JsHash.sha256Hex(inputBytes),
                servicePid = Process.myPid(),
                serviceUid = Process.myUid(),
            )
        }

        /** The QuickJS lifecycle itself; MUST run on the dedicated execution thread. */
        private fun execute(
            request: JsExecutionRequest,
            sourceBytes: ByteArray,
            inputBytes: ByteArray,
            outputPfd: ParcelFileDescriptor?,
            interruptRequested: AtomicBoolean,
        ): JsExecutionResult {
            val js = QuickJs.create()
            try {
                js.memoryLimit = request.limits.memoryBytes
                js.interruptHandler =
                    InterruptHandler {
                        interruptRequested.get() || System.nanoTime() >= request.deadlineNanos
                    }
                val value = js.evaluate(String(sourceBytes, StandardCharsets.UTF_8), JS_FILE_NAME)
                return deliverResult(request, inputBytes, outputPfd, value)
            } catch (e: QuickJsException) {
                return classifyEngineError(request, inputBytes, interruptRequested, e)
            } finally {
                js.close()
            }
        }

        /** Encodes the evaluated value and delivers it (inline or through the output PFD). */
        @Suppress("ReturnCount") // one return per distinct delivery outcome
        private fun deliverResult(
            request: JsExecutionRequest,
            inputBytes: ByteArray,
            outputPfd: ParcelFileDescriptor?,
            value: Any?,
        ): JsExecutionResult {
            val encoded: ByteArray =
                try {
                    JsResultJson.encode(value)
                } catch (e: JsResultJson.EncodingFailure) {
                    return outputLimit(request, inputBytes, "result is not JSON-encodable: ${e.message}")
                }
            if (encoded.size > request.limits.maxOutputBytes) {
                return outputLimit(
                    request,
                    inputBytes,
                    "output ${encoded.size} exceeds maxOutputBytes ${request.limits.maxOutputBytes}",
                )
            }
            if (outputPfd == null && encoded.size > JsProtocol.PARCEL_INLINE_MAX_BYTES) {
                // No output PFD and the result cannot ride the inline parcel cap:
                // fail closed — never silently truncate (doc 03 §4.6).
                return outputLimit(
                    request,
                    inputBytes,
                    "output ${encoded.size} exceeds the inline parcel cap " +
                        "${JsProtocol.PARCEL_INLINE_MAX_BYTES} and no output PFD was provided",
                )
            }
            if (outputPfd != null) {
                ParcelFileDescriptor.AutoCloseOutputStream(outputPfd).use { it.write(encoded) }
            }
            val inline =
                if (encoded.size <= JsProtocol.PARCEL_INLINE_MAX_BYTES) encoded else ByteArray(0)
            return JsExecutionResult(
                executionId = request.executionId,
                status = JsExecutionStatus.SUCCESS,
                outputUtf8 = inline,
                outputBytes = encoded.size.toLong(),
                outputSha256Hex = JsHash.sha256Hex(encoded),
                inputSha256Hex = JsHash.sha256Hex(inputBytes),
                detail = "",
                servicePid = Process.myPid(),
                serviceUid = Process.myUid(),
            )
        }

        private fun outputLimit(
            request: JsExecutionRequest,
            inputBytes: ByteArray,
            detail: String,
        ): JsExecutionResult =
            JsExecutionResult.serviceFailure(
                executionId = request.executionId,
                status = JsExecutionStatus.OUTPUT_LIMIT,
                detail = detail,
                inputSha256Hex = JsHash.sha256Hex(inputBytes),
                servicePid = Process.myPid(),
                serviceUid = Process.myUid(),
            )

        /**
         * Classifies a [QuickJsException] with the deadline-first rule: the watchdog's
         * timeout-interrupt and the service-side deadline land on the same monotonic
         * instant, so a deadline breach classifies as TIMEOUT even if the interrupt flag
         * is also set. Explicit cancels carry a distant deadline and therefore classify
         * as INTERRUPTED.
         */
        private fun classifyEngineError(
            request: JsExecutionRequest,
            inputBytes: ByteArray,
            interruptRequested: AtomicBoolean,
            error: QuickJsException,
        ): JsExecutionResult {
            val deadlinePassed = System.nanoTime() >= request.deadlineNanos
            val status =
                when {
                    deadlinePassed -> JsExecutionStatus.TIMEOUT
                    interruptRequested.get() -> JsExecutionStatus.INTERRUPTED
                    isOutOfMemory(error) -> JsExecutionStatus.OOM
                    else -> JsExecutionStatus.JS_ERROR
                }
            return JsExecutionResult.serviceFailure(
                executionId = request.executionId,
                status = status,
                detail = error.message.orEmpty().ifBlank { "<empty message>" },
                inputSha256Hex = JsHash.sha256Hex(inputBytes),
                servicePid = Process.myPid(),
                serviceUid = Process.myUid(),
            )
        }

        /**
         * OOM is normally the message `out of memory` (both APIs). HXA-050 pinned that on
         * API 29 a 64 MiB heap exhaustion can fail to allocate the JS Error object itself
         * and surface the SAME JS-level OOM with an EMPTY message.
         *
         * The two forms are classified by message shape: the engine's `memoryUsage`
         * counter is NOT a usable discriminator — it reports current usage, which falls
         * back to baseline (~94 KiB observed on API 29) after the failed allocation.
         * Known raw-mode ambiguity: a user `throw new Error()` with an empty message is
         * indistinguishable at the Zipline API level and labels as OOM; HXA-052's
         * wrapper guarantees non-blank error text, eliminating the ambiguity in
         * production mode.
         */
        private fun isOutOfMemory(error: QuickJsException): Boolean {
            val message = error.message.orEmpty()
            return message.contains("out of memory", ignoreCase = true) || message.isBlank()
        }

        /**
         * TEST-ONLY crash-injection seam.
         *
         * Simulates an external crash of the isolated process (native abort, OOM-killer,
         * ANR killer) so instrumented tests can exercise the Binder-death path. It is
         * doubly gated: (1) compiled out unless [BuildConfig.DEBUG] and (2) armed only by
         * the explicit `FLAG_CRASH_INJECTION` request flag, which only instrumented tests
         * set. HXA-053's production tool path never sets the flag, and this is the ONLY
         * place in the module where `Process.killProcess` is called — it is crash
         * SIMULATION, not control flow.
         */
        private fun startCrashSeam(delayMs: Int) {
            if (!BuildConfig.DEBUG) return
            val killer =
                Thread(
                    {
                        try {
                            Thread.sleep(delayMs.coerceAtLeast(0).toLong())
                        } catch (e: InterruptedException) {
                            return@Thread
                        }
                        Process.killProcess(Process.myPid())
                    },
                    CRASH_SEAM_THREAD_NAME,
                )
            killer.isDaemon = true
            killer.start()
        }
    }

    private enum class SlotState {
        IDLE,
        RUNNING,
        DONE,
    }

    companion object {
        /** Dedicated execution thread; ADR-0015: create/evaluate/close QuickJs on it. */
        private const val EXECUTION_THREAD_NAME: String = "helix-js-execution"

        /** 16 MiB native stack (HXA-050 verified; > Zipline's 6 MiB minimum). */
        private const val EXECUTION_THREAD_STACK_BYTES: Long = 16L * 1024 * 1024

        private const val EXECUTION_JOIN_GRACE_MS: Long = 5_000L

        private const val PFD_READ_CHUNK_BYTES: Int = 8 * 1024

        private const val JS_FILE_NAME: String = "helix.js"

        private const val CRASH_SEAM_THREAD_NAME: String = "helix-js-crash-seam"
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    runCatching { close() }
}

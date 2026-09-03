package com.helix.runtime.quickjs

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared harness for the HXA-051 instrumented suite.
 *
 * Isolation is asserted through PID/UID and the isolated UID only — never through the
 * process-name string (doc 03 §2.2: the process name is an OS detail, not a protocol ID
 * or security boundary). All waits are bounded and asserted; no test sleeps unboundedly.
 */
internal object JsExecutionTestSupport {
    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    val client: JsExecutionClient
        get() = JsExecutionClient(context)

    fun newExecutionId(tag: String): String = "$tag-${UUID.randomUUID()}"

    fun params(
        executionId: String,
        source: String,
        inputJsonUtf8: ByteArray? = null,
        limits: JsExecutionLimits = JsExecutionLimits.DEFAULTS,
        outputFile: File? = null,
        debugInjectCrash: Boolean = false,
        debugCrashAfterMs: Long = JsExecuteParams.CRASH_SEAM_DEFAULT_DELAY_MS,
    ): JsExecuteParams =
        JsExecuteParams(
            executionId = executionId,
            source = source,
            inputJsonUtf8 = inputJsonUtf8,
            limits = limits,
            outputFile = outputFile,
            debugInjectCrash = debugInjectCrash,
            debugCrashAfterMs = debugCrashAfterMs,
        )

    /** A unique instance name for direct (non-client) binds in lifecycle tests. */
    fun newInstanceName(tag: String): String = JsInstanceName.forExecution(newExecutionId(tag))

    @Suppress("DEPRECATION") // bounded reclamation observation; the primary signal is the death recipient.
    fun runningPids(): Set<Int> =
        runningAppProcesses()
            .mapNotNull { it.pid }
            .toSet()

    // This app's own processes (the platform restricts the list to the caller's app).
    @Suppress("DEPRECATION") // the call only ever returns the caller app's own processes.
    private fun runningAppProcesses(): List<ActivityManager.RunningAppProcessInfo> =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .runningAppProcesses
            .orEmpty()

    // Evidence-only observation (HXA-054 device evidence): the PIDs of THIS app's
    // `:helix_js` isolated processes, for crash/timeout reclamation evidence in tests.
    // This is a test observation channel — the process name is never a protocol ID or
    // security boundary (doc 03 §2.2), and protocol assertions keep using PID/UID only.
    fun isolatedPids(): Set<Int> =
        runningAppProcesses()
            .filter { it.processName.orEmpty().endsWith(ISOLATED_PROCESS_SUFFIX) }
            .map { it.pid }
            .toSet()

    /** Bounded wait for PID reclamation; returns true when the PID is gone. */
    fun awaitProcessGone(
        pid: Int,
        timeoutMs: Long = 30_000L,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (pid !in runningPids()) return true
            Thread.sleep(100)
        }
        return pid !in runningPids()
    }

    /** A directly bound isolated instance (bypassing the client) plus its INFO identity. */
    class DirectBound(
        val binder: IBinder,
        val connection: ServiceConnection,
        val pid: Int,
        val uid: Int,
    ) {
        fun release() {
            runCatching { context.unbindService(connection) }
        }
    }

    fun bindDirect(instanceName: String): DirectBound {
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
        val accepted =
            context.bindIsolatedService(
                Intent(context, JsExecutionService::class.java),
                Context.BIND_AUTO_CREATE,
                instanceName,
                context.mainExecutor,
                connection,
            )
        check(accepted) { "bindIsolatedService rejected $instanceName" }
        check(connected.await(15, TimeUnit.SECONDS)) { "direct bind did not connect for $instanceName" }
        val binder = checkNotNull(binderHolder.get()) { "no binder for $instanceName" }
        val (pid, uid) = info(binder)
        return DirectBound(binder, connection, pid, uid)
    }

    fun info(binder: IBinder): Pair<Int, Int> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            binder.transact(JsProtocol.CODE_INFO, data, reply, 0)
            return JsExecutionWire.readInfo(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * A minimal inline EXECUTE transaction straight on a binder (used to probe instance
     * slot semantics and the service-side defense-in-depth checks without the client;
     * the client always moves payloads above the inline parcel cap to PFDs, so the
     * service-side inline-cap rejections are only reachable this way).
     */
    fun executeDirect(
        binder: IBinder,
        executionId: String,
        source: String,
        inputJsonUtf8: ByteArray = ByteArray(0),
        limits: JsExecutionLimits = JsExecutionLimits.DEFAULTS,
    ): JsExecutionResult {
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        val request =
            JsExecutionRequest(
                executionId = executionId,
                sourceUtf8 = sourceBytes,
                inputJsonUtf8 = inputJsonUtf8,
                limits = limits,
                deadlineNanos = System.nanoTime() + limits.timeoutMs * 1_000_000L,
            )
        val envelope =
            JsExecutionWire.ExecuteEnvelope(
                sourcePfd = null,
                inputPfd = null,
                outputPfd = null,
                sourceTotalBytes = sourceBytes.size,
                inputTotalBytes = inputJsonUtf8.size.toLong(),
                flags = 0,
                crashAfterMs = 0,
            )
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            JsExecutionWire.writeExecute(data, request, envelope)
            binder.transact(JsProtocol.CODE_EXECUTE, data, reply, 0)
            return JsExecutionWire.readResult(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private const val ISOLATED_PROCESS_SUFFIX: String = ":helix_js"
}

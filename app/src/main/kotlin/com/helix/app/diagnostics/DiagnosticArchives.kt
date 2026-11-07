package com.helix.app.diagnostics

import android.annotation.SuppressLint
import android.app.Application
import com.helix.core.policy.DiagnosticRedaction
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Main-process diagnostic owner. Failures here never change an execution's durable outcome. */
@Suppress("TooManyFunctions") // Single main-process diagnostic facade for producers, settings and lifecycle.
internal object DiagnosticArchives {
    @Volatile private var owners: (() -> Set<String>)? = null

    @Volatile private var archive: DiagnosticArchive? = null

    @Volatile var failure: String? = null
        private set
    private val dropped = AtomicLong()
    private var installed = false

    @Volatile private var maintenance: (suspend () -> Unit)? = null
    private val writer =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(256)) {
            Thread(it, "diagnostic-archive").apply { isDaemon = true }
        }

    @Synchronized
    // Check commit success before marking a crash as durably archived; KTX edit returns Unit.
    @SuppressLint("UseKtx")
    fun install(application: Application) {
        if (installed) return
        installed = true
        enqueue {
            archive =
                DiagnosticArchive(
                    application.noBackupFilesDir.toPath().resolve("diagnostic-archive"),
                    { owners?.invoke() },
                )
            requireArchive().clean()
            val evidence = ProcessEvidenceStore(application).read()
            if (evidence.crashAtMillis != null) {
                val seen = application.getSharedPreferences("diagnostic-archive", 0)
                if (seen.getLong("crashAt", 0) != evidence.crashAtMillis) {
                    val body = DiagnosticBundlePreview.collect(ProcessEvidenceStore(application)).encode()
                    if (requireArchive().append(DiagnosticKind.FAILURE, "process.crash", body)) {
                        check(seen.edit().putLong("crashAt", evidence.crashAtMillis).commit())
                    }
                }
            }
            requireArchive().append(DiagnosticKind.LOG, "process.start", "{}")
        }
        Executors
            .newSingleThreadScheduledExecutor {
                Thread(it, "diagnostic-maintenance").apply { isDaemon = true }
            }.scheduleWithFixedDelay({ enqueue { clean() } }, 1, 1, TimeUnit.DAYS)
    }

    fun bindMaintenance(action: suspend () -> Unit) {
        maintenance = action
        enqueue { kotlinx.coroutines.runBlocking { action() } }
    }

    fun bindProtectedOwners(read: () -> Set<String>) {
        owners = read
        // Startup may have held all task-owned entries until Room was ready; revisit with durable facts.
        enqueue { requireArchive().clean() }
    }

    fun record(
        kind: DiagnosticKind,
        event: String,
        body: String,
        owner: String? = null,
    ) {
        require(body.toByteArray().size <= DiagnosticArchive.MAX_BODY_BYTES)
        val redacted = DiagnosticRedaction.redact(body)
        enqueue { requireArchive().append(kind, event, redacted, owner) }
    }

    fun operation(
        stage: String,
        type: String,
    ) = record(
        DiagnosticKind.FAILURE,
        "operation.failure",
        buildJsonObject {
            put("stage", stage)
            put("exceptionType", type.take(160))
        }.toString(),
    )

    fun status(): DiagnosticArchiveStatus =
        requireArchive().status().let {
            it.copy(dropped = it.dropped + dropped.get())
        }

    fun configure(policy: DiagnosticRetention) = requireArchive().configure(policy)

    fun pin(
        id: String,
        pinned: Boolean,
    ) = requireArchive().pin(id, pinned)

    fun detail(id: String): String? = requireArchive().inspect(id)

    fun clean() {
        requireArchive().clean()
        maintenance?.let { action -> kotlinx.coroutines.runBlocking { action() } }
    }

    private fun requireArchive(): DiagnosticArchive = checkNotNull(archive) { "DIAGNOSTICS_NOT_READY" }

    // Diagnostic failure is visible in status and never changes execution success.
    @Suppress("TooGenericExceptionCaught")
    private fun enqueue(action: () -> Unit) {
        try {
            writer.execute {
                try {
                    action()
                    failure = null
                } catch (_: Exception) {
                    failure = "DIAGNOSTIC_STORAGE_FAILED"
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            dropped.incrementAndGet()
            failure = "DIAGNOSTIC_QUEUE_FULL"
        }
    }
}

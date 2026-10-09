package com.helix.tools.framework

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only trusted host wiring can create this concrete read-only observer; metadata cannot opt a tool into it. */
internal class JobObservationExecutor(
    private val service: JobObservationService,
    private val waiting: Boolean,
) : ToolExecutor {
    override fun execute(call: ExecutableToolCall): ToolExecutorResult =
        ToolExecutorResult.Failed(
            "JOB_OBSERVATION_REQUIRES_COMPLETION: use the governed completion dispatch entry.",
            sideEffectFree = true,
        )

    fun acceptsEvidence(
        sessionId: String,
        evidence: kotlinx.serialization.json.JsonObject,
    ): Boolean = service.acceptsEvidence(sessionId, evidence)

    fun start(
        call: ExecutableToolCall,
        mayRead: () -> Boolean,
    ): JobObservationSubmission = service.start(call, waiting, mayRead)
}

/** The logical observer quota is held through final output validation/audit, not just through query completion. */
internal class JobObservationSubmission(
    val result: CompletableFuture<ToolExecutorResult>,
    val beforePublish: (ToolExecutorResult) -> ToolExecutorResult = { it },
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }

    companion object {
        fun immediate(result: ToolExecutorResult): JobObservationSubmission =
            JobObservationSubmission(CompletableFuture.completedFuture(result)) {}
    }
}

/** No disk/network work on timer or Binder callbacks. At most the bounded observer set can queue publication. */
internal object JobObservationPublication {
    private val pool =
        ThreadPoolExecutor(
            2,
            2,
            60,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(32),
            { action -> Thread(action, "job-observation-publication").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

    /** Preserve the observer quota through the enclosing launch's output validation and audit. */
    fun map(
        submission: JobObservationSubmission,
        transform: (ToolExecutorResult) -> ToolExecutorResult,
    ): JobObservationSubmission {
        val observed =
            java.util.concurrent.atomic
                .AtomicReference<ToolExecutorResult>()
        return JobObservationSubmission(
            submission.result.thenApplyAsync({ result ->
                val checked = submission.beforePublish(result)
                observed.set(checked)
                transform(checked)
            }, pool),
            beforePublish = { result ->
                val original = requireNotNull(observed.get())
                if (submission.beforePublish(original) == original) {
                    result
                } else {
                    ToolExecutorResult.CancelledWithEffectTruth(
                        "JOB_LAUNCH_BINDING_CHANGED: inspect the original execution; nothing was replayed.",
                        sideEffectFree = false,
                        requiresReview = true,
                    )
                }
            },
            release = submission::close,
        )
    }

    fun <T> settle(
        submission: JobObservationSubmission,
        finish: (ToolExecutorResult?, Throwable?) -> T,
    ): CompletableFuture<T> =
        submission.result
            .handleAsync({ result, failure ->
                val checked =
                    if (failure == null) runCatching { submission.beforePublish(requireNotNull(result)) } else null
                // Revalidation failures enter the same audit settlement, never a parallel result path.
                finish(checked?.getOrNull(), failure ?: checked?.exceptionOrNull())
            }, pool)
            .whenComplete { _, _ -> submission.close() }
}

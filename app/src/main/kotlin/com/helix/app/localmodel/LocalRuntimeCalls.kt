package com.helix.app.localmodel

import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.local.LocalRuntimeException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No queue or thread growth when a Binder/read ignores cancellation. Completion is not inferred from timeout. */
internal class LocalRuntimeCalls(
    name: String,
    capacity: Int = 2,
) : AutoCloseable {
    private val workers =
        ThreadPoolExecutor(
            0,
            capacity,
            30,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { task -> Thread(task, name).apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

    @Suppress("TooGenericExceptionCaught") // Worker failures propagate; no synthetic success.
    suspend fun <T> call(onCancel: () -> Unit = {}, action: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { onCancel() }
            try {
                workers.execute {
                    if (continuation.isActive) {
                        try {
                            continuation.resume(action())
                        } catch (failure: Throwable) {
                            continuation.resumeWithException(failure)
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                continuation.resumeWithException(LocalRuntimeException(ModelErrorCode.LOCAL_CANCEL_TIMEOUT))
            }
        }

    override fun close() {
        workers.shutdownNow()
    }
}

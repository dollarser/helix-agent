package com.helix.core.agent

import com.helix.core.agent.ModelStreamState

/** Retry only an empty inference response, never a partial stream or a runtime-backed agent. */
object ModelRecoveryPolicy {
    const val DIAGNOSTIC_TYPE = "model.automatic_retry"

    fun retryDelayMillis(
        directInference: Boolean,
        retries: Int,
        stream: ModelStreamState,
        elapsedMillis: Long = 0,
        jitter: Double = kotlin.random.Random.nextDouble(),
    ): Long? =
        if (directInference && retryableEmptyResponse(stream)) {
            com.helix.core.model.TechnicalRecovery
                .delayMillis(retries, elapsedMillis, jitter = jitter)
        } else {
            null
        }

    private fun retryableEmptyResponse(stream: ModelStreamState): Boolean =
        stream.retryableError && !stream.completed && !stream.receiving && !stream.hasToolCallFragments
}

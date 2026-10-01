package com.helix.core.agent

import com.helix.core.agent.ModelStreamState

/** Retry only an empty inference response, never a partial stream or a runtime-backed agent. */
object ModelRecoveryPolicy {
    fun retryDelayMillis(
        directInference: Boolean,
        retries: Int,
        stream: ModelStreamState,
    ): Long? =
        if (directInference && retries in 0..1 && retryableEmptyResponse(stream)) {
            1_000L shl retries
        } else {
            null
        }

    private fun retryableEmptyResponse(stream: ModelStreamState): Boolean =
        stream.retryableError && !stream.completed && stream.outputSizeBytes == 0L && !stream.hasToolCallFragments
}

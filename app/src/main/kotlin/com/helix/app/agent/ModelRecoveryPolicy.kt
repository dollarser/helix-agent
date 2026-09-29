package com.helix.app.agent

/** Retry only an empty inference response, never a partial stream or a runtime-backed agent. */
internal object ModelRecoveryPolicy {
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

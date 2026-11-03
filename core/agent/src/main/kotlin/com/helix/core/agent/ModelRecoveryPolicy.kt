package com.helix.core.agent

/** Retry the current empty inference request, never replay a tool or a partial model stream. */
object ModelRecoveryPolicy {
    const val DIAGNOSTIC_TYPE = "model.automatic_retry"
    const val MAX_RETRIES = 5

    fun retryDelayMillis(
        directInference: Boolean,
        retries: Int,
        stream: ModelStreamState,
        jitter: Double = kotlin.random.Random.nextDouble(),
    ): Long? {
        require(jitter in 0.0..1.0)
        if (!directInference || !retryableEmptyResponse(stream) || retries !in 0 until MAX_RETRIES) return null
        val backoff = ((1_000L shl retries) * (0.8 + jitter * 0.4)).toLong()
        return maxOf(backoff, stream.failureDiagnostic?.retryAfterMillis ?: 0)
    }

    private fun retryableEmptyResponse(stream: ModelStreamState): Boolean =
        stream.retryableError && !stream.completed && !stream.receiving && !stream.hasToolCallFragments
}

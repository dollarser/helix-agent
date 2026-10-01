package com.helix.tools.framework

/** Original execution identity, resolved by the host. Model arguments carry only [handle]. */
data class JobObservationBinding(
    val sessionId: String,
    val turnId: String,
    val handle: String,
    val providerRef: String,
    val executionId: String,
    val generation: String,
    val bindingHash: String,
) {
    init {
        require(
            listOf(sessionId, turnId, handle, providerRef, executionId, generation).all {
                it.length in 1..128 && it.none { character -> character.code < 32 }
            },
        )
        require(bindingHash.matches(Regex("[a-f0-9]{64}")))
    }
}

/** An observation is not an execution receipt or permission to collect its output. */
data class JobObservation(
    val binding: JobObservationBinding,
    val state: String,
    val terminal: Boolean,
    val requiresReview: Boolean,
    val settlementPending: Boolean,
    val revision: String,
    val observedAtMillis: Long,
    val exitCode: Int? = null,
    val elapsedDurationMillis: Long? = null,
) {
    init {
        require(state.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
        require(revision.length in 1..128 && revision.none { it.code < 32 })
        require(observedAtMillis >= 0)
        require(elapsedDurationMillis == null || elapsedDurationMillis >= 0)
    }
}

/** Trusted, read-only host adapter. No command submission, cancellation, lease renewal or output import. */
interface JobObservationPort {
    fun resolve(
        sessionId: String,
        handle: String,
    ): JobObservationBinding

    fun isCurrent(binding: JobObservationBinding): Boolean

    fun query(binding: JobObservationBinding): JobObservation?
}

/** Host/test parameters stay within fixed resource bounds; tool arguments cannot change them. */
data class JobObservationLimits(
    val maxObservers: Int = 16,
    val maxPerSession: Int = 4,
    val maxHandles: Int = 8,
    val maxQueries: Int = 2,
    val maxWaitMillis: Long = 15_000,
    val pollMillis: Long = 250,
    val queryTimeoutMillis: Long = 2_000,
    val finalizationReserveMillis: Long = 250,
    val tickMillis: Long = 25,
) {
    init {
        require(maxObservers in 1..16 && maxPerSession in 1..minOf(4, maxObservers))
        require(maxHandles in 1..8 && maxQueries in 1..2)
        require(maxWaitMillis in 1..15_000 && pollMillis in 1..1_000)
        require(queryTimeoutMillis in 1..2_000 && finalizationReserveMillis in 0..1_000)
        require(tickMillis in 1..100)
    }
}

internal enum class JobWaitCondition { ANY, ALL }

internal enum class JobObservationReason {
    SNAPSHOT,
    CONDITION_MET,
    WAIT_EXPIRED,
    SOURCE_UNAVAILABLE,
    REVIEW_REQUIRED,
    OBSERVATION_BUSY,
}

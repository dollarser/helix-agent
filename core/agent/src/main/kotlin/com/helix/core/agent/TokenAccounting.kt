package com.helix.core.agent

import com.helix.core.model.ModelCallId

/**
 * Token accounting for one current model call. Provider usage replaces the conservative byte
 * estimate for that dimension; missing usage is never treated as zero.
 */
data class CallTokenAccount(
    val callId: ModelCallId,
    val requestBytes: Long,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val responseBytes: Long = 0,
) {
    init {
        require(
            listOf(requestBytes, responseBytes, inputTokens, outputTokens, totalTokens)
                .none { it != null && it < 0 },
        ) {
            "token accounting values must be >= 0"
        }
    }

    val effectiveInput: Long
        get() = inputTokens ?: TokenEstimator.estimateTokens(requestBytes)

    val effectiveOutput: Long
        get() = outputTokens ?: TokenEstimator.estimateTokens(responseBytes)

    val effectiveTotal: Long
        get() = totalTokens ?: effectiveInput + effectiveOutput
}

/**
 * Conservative byte-based token estimation for calls whose usage the provider did not report.
 *
 * A typical BPE tokenizer produces roughly one token per 4 bytes of text; estimating
 * `ceil(bytes / 4)` keeps the budget accounting deterministic and dependency-free. The divisor
 * is deliberately a named constant: if a tighter or looser bound is required later it must be
 * changed here (and in tests), not scattered across call sites.
 */
object TokenEstimator {
    const val CONSERVATIVE_BYTES_PER_TOKEN = 4L

    /** Exact JVM UTF-8 byte length without allocating a second copy of the text. */
    fun utf8Bytes(text: String): Long {
        var count = 0L
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            count +=
                when {
                    char.code < 0x80 -> {
                        1
                    }

                    char.code < 0x800 -> {
                        2
                    }

                    char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                        index++
                        4
                    }

                    char.isSurrogate() -> {
                        1
                    }

                    // JVM UTF-8 replaces an unpaired surrogate with '?'.
                    else -> {
                        3
                    }
                }
        }
        return count
    }

    fun estimateTokens(byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount must be >= 0" }
        return (byteCount + CONSERVATIVE_BYTES_PER_TOKEN - 1) / CONSERVATIVE_BYTES_PER_TOKEN
    }
}

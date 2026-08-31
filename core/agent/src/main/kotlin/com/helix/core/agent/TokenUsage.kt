package com.helix.core.agent

/**
 * Token usage reported by a provider for one model call. Any field may be missing (null);
 * missing values are never treated as zero by the [TurnReducer] - a conservative estimate is
 * used instead (architecture doc 5.3: "无法准确 tokenizer 时使用保守字节估算，不得将未知 usage
 * 当作 0").
 */
data class TokenUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
) {
    init {
        requireNonNegative("inputTokens", inputTokens)
        requireNonNegative("outputTokens", outputTokens)
        requireNonNegative("totalTokens", totalTokens)
    }

    private fun requireNonNegative(
        name: String,
        value: Long?,
    ) {
        if (value != null) require(value >= 0) { "$name must be >= 0" }
    }

    companion object {
        val MISSING = TokenUsage(null, null, null)
    }
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

    fun estimateTokens(byteCount: Long): Long {
        require(byteCount >= 0) { "byteCount must be >= 0" }
        return (byteCount + CONSERVATIVE_BYTES_PER_TOKEN - 1) / CONSERVATIVE_BYTES_PER_TOKEN
    }
}

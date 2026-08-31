package com.helix.provider.api

import com.helix.core.model.ModelErrorCode

/**
 * Outcome of a model-list query (provider doc section 2.4 phase 2, HXA-025).
 */
public sealed interface ModelCatalogResult {
    /** The service listed its models. */
    public data class Listed(
        val models: List<String>,
    ) : ModelCatalogResult {
        init {
            require(models.isNotEmpty()) { "a listed catalog must not be empty" }
            require(models.size <= MAX_MODELS) { "at most $MAX_MODELS models per list" }
            require(models.toSet().size == models.size) { "model ids must be unique" }
            models.forEach { id ->
                require(id.isNotBlank() && id.length <= MAX_MODEL_ID_LENGTH) {
                    "model id must be 1..$MAX_MODEL_ID_LENGTH non-blank chars"
                }
                require(id.none { it in '\u0000'..'\u001F' || it == '\u007F' }) {
                    "model id contains a control character"
                }
            }
        }

        internal companion object {
            const val MAX_MODELS = 1_024
            const val MAX_MODEL_ID_LENGTH = 256
        }
    }

    /** The service has no model-list endpoint (e.g. the official Anthropic API). */
    public data object Unsupported : ModelCatalogResult

    /** The query failed; [detail] is a bounded diagnostic (never a secret or a payload). */
    public data class Failed(
        val code: ModelErrorCode,
        val detail: String,
        val retryable: Boolean,
    ) : ModelCatalogResult {
        init {
            require(detail.isNotBlank() && detail.length <= MAX_DETAIL_LENGTH) {
                "detail must be 1..$MAX_DETAIL_LENGTH non-blank chars"
            }
        }

        internal companion object {
            const val MAX_DETAIL_LENGTH = 512
        }
    }
}

/**
 * Outcome of the configuration validation (provider doc section 2.4 phase 1, HXA-025).
 */
public sealed interface ProviderCheckResult {
    /** Transport, TLS/HTTP and authentication all succeeded. */
    public data object Ok : ProviderCheckResult

    /**
     * The check failed; [code] is the closed failure class (transport/auth/throttling/
     * server/protocol), [detail] a bounded diagnostic, [retryable] whether the same
     * check may be re-run (connection-level failures) or must be fixed first.
     */
    public data class Failed(
        val code: ModelErrorCode,
        val detail: String,
        val retryable: Boolean,
    ) : ProviderCheckResult {
        init {
            require(detail.isNotBlank() && detail.length <= MAX_DETAIL_LENGTH) {
                "detail must be 1..$MAX_DETAIL_LENGTH non-blank chars"
            }
        }

        internal companion object {
            const val MAX_DETAIL_LENGTH = 512
        }
    }
}

/** Bounds a raw diagnostic (exception class/message, status line) to the detail field. */
internal fun boundedDetail(raw: String): String {
    val cleaned = raw.replace('\n', ' ').replace('\r', ' ').trim()
    return if (cleaned.length > 512) cleaned.take(512) else cleaned
}

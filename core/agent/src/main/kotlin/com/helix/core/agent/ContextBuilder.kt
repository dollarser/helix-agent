package com.helix.core.agent

import com.helix.core.model.ArtifactRef
import com.helix.core.model.SessionId
import com.helix.core.model.Sha256
import com.helix.core.model.TurnId
import java.security.MessageDigest

/**
 * Where a context item's content comes from (architecture doc 5.4).
 *
 * The six content sources listed in the security doc (Web/File/MCP/Skill/Notification/
 * Accessibility) are the untrusted ones: their content is model-visible but must be marked
 * `UNTRUSTED` and can never grant permission by itself.
 */
enum class ContextSourceType {
    SYSTEM,
    MODE_POLICY,
    USER,
    ASSISTANT,
    TOOL_CALL,
    TOOL_RESULT,
    APPROVAL,
    WEB,
    FILE,
    MCP,
    SKILL,
    NOTIFICATION,
    ACCESSIBILITY,
    ;

    val isUntrustedSource: Boolean
        get() = this in UNTRUSTED_SOURCES

    /** App-owned contract: always TRUSTED and never trimmed by the builder. */
    val isContract: Boolean
        get() = this in CONTRACT_SOURCES

    companion object {
        /** Content sources whose text must always be marked [ContextTrust.UNTRUSTED] (doc 07). */
        val UNTRUSTED_SOURCES: Set<ContextSourceType> =
            setOf(WEB, FILE, MCP, SKILL, NOTIFICATION, ACCESSIBILITY)

        /** App-owned contracts: always [ContextTrust.TRUSTED] and never trimmed by the builder. */
        val CONTRACT_SOURCES: Set<ContextSourceType> = setOf(SYSTEM, MODE_POLICY)
    }
}

/** Trust marking carried by every context item (audit + downstream policy use). */
enum class ContextTrust {
    TRUSTED,
    UNTRUSTED,
}

/**
 * One persisted source the [ContextBuilder] may include (a message, a tool call with its full
 * canonical arguments, an approval context, a tool result, or untrusted content).
 *
 * Bounded-content rule (no character-level truncation, ever, doc 02 section 5.4): either the
 * full [content] is small enough to travel inline, or the source is a bounded summary of larger
 * stored content — then [artifactRef] points at the stored body and [contentHash] is the SHA-256
 * of the full body (the binding that makes the summary verifiable later). Chunked retrieval of
 * the full body (`read(offset, maxBytes)`) belongs to HXA-041.
 *
 * [retained] marks the items the current turn state requires verbatim: the current user
 * instruction, unfinished ToolCall arguments, the approval context and the corresponding
 * ToolResults (doc 02 section 5.4). The caller derives it from the turn state; the builder
 * additionally protects [ContextSourceType.CONTRACT_SOURCES] itself.
 */
data class ContextSource(
    val sourceType: ContextSourceType,
    val sourceId: String,
    val trust: ContextTrust,
    val content: String,
    val contentHash: Sha256? = null,
    val artifactRef: ArtifactRef? = null,
    val retained: Boolean = false,
) {
    init {
        require(sourceId.isNotBlank() && sourceId.length <= SOURCE_ID_MAX_LENGTH) {
            "sourceId must be 1..$SOURCE_ID_MAX_LENGTH non-blank characters"
        }
        require(content.isNotBlank()) { "source content must not be blank" }
        require(artifactRef == null || contentHash != null) {
            "a summary replacement must carry the SHA-256 of the full content"
        }
        require(!sourceType.isUntrustedSource || trust == ContextTrust.UNTRUSTED) {
            "${sourceType.name} content must be marked UNTRUSTED (security doc section 7)"
        }
        require(sourceType !in ContextSourceType.CONTRACT_SOURCES || trust == ContextTrust.TRUSTED) {
            "${sourceType.name} is an app contract and must be TRUSTED"
        }
    }

    private companion object {
        const val SOURCE_ID_MAX_LENGTH = 128
    }
}

/**
 * The Provider capability snapshot the build must respect (doc 02 section 5.4 input). The full
 * provider catalog is M2; the context builder only needs the context-window bound.
 */
data class ProviderCapability(
    val providerId: String,
    val modelId: String,
    val maxContextTokens: Long,
) {
    init {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(maxContextTokens >= 1) { "maxContextTokens must be >= 1" }
    }
}

/**
 * One auditable context build (doc 02 section 5.4): the persisted session/Turn snapshot plus
 * the Provider capability and the input token budget for this build.
 *
 * [inputTokenBudget] must already be the stricter of the user config (TurnBudgets) and the
 * Provider capability (doc 02 section 5.3) — the builder validates, it does not re-derive.
 * The snapshot must be in chronological order (oldest first); recency is the deterministic
 * relevance proxy of the first-version trim.
 */
data class ContextBuildRequest(
    val sessionId: SessionId,
    val turnId: TurnId,
    val capability: ProviderCapability,
    val inputTokenBudget: Long,
    val snapshot: List<ContextSource>,
) {
    init {
        require(inputTokenBudget >= 1) { "inputTokenBudget must be >= 1" }
        require(inputTokenBudget <= capability.maxContextTokens) {
            "inputTokenBudget must be the stricter of user config and provider capability"
        }
        val ids = snapshot.map { it.sourceId }
        require(ids.distinct().size == ids.size) { "sourceId must be unique within one context build" }
    }
}

/** One item of the built context — the auditable unit (doc 02 section 5.4). */
data class ContextItem(
    val sourceType: ContextSourceType,
    val sourceId: String,
    val trust: ContextTrust,
    val content: String,
    val contentRef: ArtifactRef?,
    val contentHash: Sha256,
    val estimatedTokens: Long,
)

/**
 * Auditable outcome of a build: exactly which sources entered the request, with their trust,
 * content binding and conservative token estimate — and which were trimmed (ids only, their
 * content never enters the request). [totalEstimatedTokens] is the sum of the item estimates.
 */
data class ContextBuildResult(
    val items: List<ContextItem>,
    val totalEstimatedTokens: Long,
    val droppedSourceIds: List<String>,
) {
    init {
        val sum = items.sumOf { it.estimatedTokens }
        require(sum == totalEstimatedTokens) { "totalEstimatedTokens must equal the sum of item estimates" }
        require(items.all { it.estimatedTokens >= 1 }) { "an included item never estimates zero tokens" }
    }

    val trimmed: Boolean
        get() = droppedSourceIds.isNotEmpty()
}

/**
 * Auditable context assembly (HXA-016, architecture doc section 5.4, security doc section 7).
 * Pure function of [ContextBuildRequest]; no I/O, no tokenizer dependency (conservative byte
 * estimate via [TokenEstimator], unknown usage is never treated as zero).
 *
 * Deterministic trim (tested):
 * 1. contracts (SYSTEM/MODE_POLICY) and [ContextSource.retained] items are never dropped and
 *    never truncated — if they alone exceed the budget the build fails closed;
 * 2. the remaining budget is filled with the non-retained snapshot items from newest to oldest
 *    (recency = first-version relevance); whole items are dropped, never fragments;
 * 3. final order: contracts first (snapshot order), then the included items in snapshot order.
 *
 * Secrets never enter a context: there is no secret source type and no API that accepts one —
 * provider credentials are referenced by alias outside the context (doc 07, section 7).
 */
object ContextBuilder {
    /** Full content may travel inline up to this size; larger sources must be summary + ref. */
    const val MAX_INLINE_CONTENT_BYTES = 32_768

    /** A summary replacing larger stored content is bounded to this size. */
    const val MAX_SUMMARY_BYTES = 2_048

    fun build(request: ContextBuildRequest): ContextBuildResult {
        request.snapshot.forEach { source -> validateBoundedContent(source) }
        val items = request.snapshot.map { source -> toItem(source) }
        val retainedFlags = request.snapshot.map { source -> retained(source) }
        val retainedTokens = items.filterIndexed { i, _ -> retainedFlags[i] }.sumOf { it.estimatedTokens }
        if (retainedTokens > request.inputTokenBudget) {
            throw IllegalStateException(
                "retained context alone exceeds the input budget: " +
                    "$retainedTokens > ${request.inputTokenBudget} tokens",
            )
        }

        // Fill the remaining budget from newest to oldest (deterministic, whole items only).
        var remaining = request.inputTokenBudget - retainedTokens
        val included = BooleanArray(items.size)
        for (index in items.indices.reversed()) {
            if (retainedFlags[index]) {
                included[index] = true
                continue
            }
            val tokens = items[index].estimatedTokens
            if (tokens <= remaining) {
                included[index] = true
                remaining -= tokens
            }
        }

        val contracts = items.filter { it.sourceType.isContract }
        val rest =
            items.filterIndexed { index, item ->
                included[index] && !item.sourceType.isContract
            }
        val dropped =
            items
                .filterIndexed { index, _ -> !included[index] }
                .map { it.sourceId }
        val resultItems = contracts + rest
        return ContextBuildResult(resultItems, resultItems.sumOf { it.estimatedTokens }, dropped)
    }

    /** Contracts are protected by the builder itself; the rest by the turn state flag. */
    private fun retained(source: ContextSource): Boolean = source.retained || source.sourceType.isContract

    private fun validateBoundedContent(source: ContextSource) {
        val bytes = source.content.toByteArray(Charsets.UTF_8).size
        if (source.artifactRef != null) {
            require(bytes <= MAX_SUMMARY_BYTES) {
                "summary for ${source.sourceId} is $bytes bytes; the bound is $MAX_SUMMARY_BYTES " +
                    "(no character-level truncation — produce a shorter summary)"
            }
        } else {
            require(bytes <= MAX_INLINE_CONTENT_BYTES) {
                "content for ${source.sourceId} is $bytes bytes; the inline bound is " +
                    "$MAX_INLINE_CONTENT_BYTES (no character-level truncation — store the full " +
                    "content and pass a bounded summary with its ArtifactRef and SHA-256)"
            }
        }
    }

    private fun toItem(source: ContextSource): ContextItem {
        val hash =
            if (source.artifactRef != null) {
                source.contentHash!!
            } else {
                val computed = sha256Of(source.content)
                source.contentHash?.let { provided ->
                    require(provided == computed) {
                        "contentHash for ${source.sourceId} does not match the content"
                    }
                }
                computed
            }
        val bytes =
            source.content
                .toByteArray(Charsets.UTF_8)
                .size
                .toLong()
        return ContextItem(
            sourceType = source.sourceType,
            sourceId = source.sourceId,
            trust = source.trust,
            content = source.content,
            contentRef = source.artifactRef,
            contentHash = hash,
            estimatedTokens = TokenEstimator.estimateTokens(bytes),
        )
    }

    private fun sha256Of(content: String): Sha256 =
        Sha256(MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).toHex())

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

package com.helix.core.agent

import com.helix.core.model.ModelMessage

/** Bounded immutable source data. The host performs storage/permission/format validation first. */
data class ContextSourceRow(
    val id: String,
    val turnId: String?,
    val sequence: Long,
    val role: String,
    val kind: String,
    val content: String?,
    val attachmentCount: Int,
    val toolCallBatch: Boolean,
    val messages: List<ModelMessage>,
    val mappingFailure: String? = null,
) {
    init {
        require(mappingFailure == null || messages.isEmpty()) { "CONTEXT_AMBIGUOUS_MAPPING" }
    }

    /** Excluded recovery rows need not decode; a selected corrupt row must still fail closed. */
    fun mappedMessages(): List<ModelMessage> {
        require(mappingFailure == null) { requireNotNull(mappingFailure) }
        return messages
    }
}

data class ContextSourceSnapshot(
    val rows: List<ContextSourceRow>,
    val checkpoint: ContextCheckpoint?,
    val currentTurnId: String,
    val predecessorTurnId: String?,
    val inputFloor: Long,
) {
    init {
        require(rows.size <= MAX_ROWS) { "CONTEXT_MATERIALIZATION_LIMIT" }
        require(rows.map { it.id }.distinct().size == rows.size) { "CONTEXT_DUPLICATE_SOURCE" }
        require(rows.zipWithNext().all { (a, b) -> a.sequence < b.sequence }) { "CONTEXT_SOURCE_ORDER" }
        var bytes = 0L
        rows.forEach { row ->
            require(row.id.isNotBlank() && row.sequence >= 0 && row.attachmentCount >= 0)
            val size = TokenEstimator.utf8Bytes(row.content.orEmpty())
            require(size <= MAX_BODY_BYTES - bytes) { "CONTEXT_MATERIALIZATION_LIMIT" }
            bytes += size
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_ROWS = 8192
    }
}

/** Host I/O stage. Implementations feed immutable snapshots into the same pure ContextCompiler. */
interface ContextCompactionSource {
    suspend fun inputFloor(model: String): Long

    suspend fun plan(
        request: TurnContextRequest,
        control: RunControlConfig,
        settings: com.helix.provider.api.ProviderContextSettings,
        force: Boolean,
        inputScale: Double,
    ): ContextCompactionPlan?
}

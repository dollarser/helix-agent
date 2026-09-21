package com.helix.app.agent

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.entity.MessageEntity

/** A capacity failure is recoverable and never means the archived conversation was deleted. */
internal class ContextCapacityException(
    val code: String,
) : IllegalArgumentException(code)

/** Page metadata, then check retained body sizes before reading any body. */
internal object ContextHistory {
    const val MAX_BODY_BYTES = 8 * 1024 * 1024
    private const val MAX_RETAINED_ROWS = 8192
    private const val PAGE_SIZE = 256

    data class Snapshot(
        val rows: List<MessageEntity>,
        val checkpoint: ContextCompaction.Checkpoint?,
    )

    fun checkpoint(
        storage: HelixStorage,
        sessionId: String,
    ): ContextCompaction.Checkpoint? =
        ContextCompaction.checkpoint(
            storage,
            listOfNotNull(storage.messages.latestOfKind(sessionId, ContextCompaction.KIND)),
        )

    fun load(
        storage: HelixStorage,
        sessionId: String,
    ): Snapshot {
        val checkpoint = checkpoint(storage, sessionId)
        val retained = mutableListOf<MessageEntity>()
        var after = -1L
        var bytes = 0L
        while (true) {
            val page = storage.messages.pageAfter(sessionId, after, PAGE_SIZE)
            if (page.isEmpty()) break
            for (row in ContextCompaction.retained(page, checkpoint)) {
                val size = row.contentRef?.let { ContentRef.parse(it).size } ?: 0
                if (size > MAX_BODY_BYTES - bytes || retained.size >= MAX_RETAINED_ROWS) {
                    throw ContextCapacityException("CONTEXT_MATERIALIZATION_LIMIT")
                }
                bytes += size
                retained.add(row)
            }
            after = page.last().sequence
        }
        return Snapshot(retained, checkpoint)
    }

    fun read(
        storage: HelixStorage,
        row: MessageEntity,
    ): String? = storage.messages.readContentBounded(row, MAX_BODY_BYTES)
}

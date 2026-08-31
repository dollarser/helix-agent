package com.helix.app.chat

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole

/**
 * Builds the model-visible message list for a chat turn from PERSISTED message
 * rows (HXA-028: the UI/services work on persisted state, doc 02 section 12).
 *
 * Rules:
 * - roles map by their [ModelRole] names (the chat service persists
 *   `USER` / `ASSISTANT` rows); any other role (M3+ tool/approval rows) is
 *   skipped — the M2 chat request must not invent tool context;
 * - rows with missing/blank content are skipped (an assistant row is persisted
 *   only when the stream produced content — a stopped/failed turn with no
 *   output leaves no assistant row);
 * - the result keeps persisted order; the CALLER appends the new user text
 *   last, which satisfies the [ModelRequest] invariant that the final message
 *   is USER (or TOOL).
 */
object ChatHistoryBuilder {
    data class PersistedRow(
        val turnId: String?,
        val role: String,
        val content: String?,
    )

    fun toModelMessages(rows: List<PersistedRow>): List<ModelMessage> =
        rows.mapNotNull { row ->
            val text = row.content?.takeIf { it.isNotBlank() }
            val role =
                when (row.role) {
                    ModelRole.USER.name -> ModelRole.USER
                    ModelRole.ASSISTANT.name -> ModelRole.ASSISTANT
                    else -> null
                }
            if (text == null || role == null) null else ModelMessage(role = role, text = text)
        }

    /**
     * The persisted rows that form ONE turn's model-visible history:
     *
     * - fresh send ([retryTurnId] == null): every row (the just-persisted
     *   user message is the newest, so the list ends with USER);
     * - retry: every row EXCEPT the retried turn's own rows, with that turn's
     *   USER row re-appended LAST. A newer turn may have followed the failed
     *   one, so the naive "exclude the retried turn's rows" filter need not
     *   end with USER — re-appending restores the invariant;
     * - a retried turn without a user row (corruption) degrades to the
     *   filtered history; the caller's end-with-USER guard then fails closed
     *   and the turn surfaces as FAILED with a safe label.
     */
    fun rowsForTurn(
        rows: List<PersistedRow>,
        retryTurnId: String?,
    ): List<PersistedRow> {
        if (retryTurnId == null) return rows
        val rest = rows.filter { it.turnId != retryTurnId }
        val retried = rows.firstOrNull { it.turnId == retryTurnId && it.role == ModelRole.USER.name }
        return if (retried == null) rest else rest + retried
    }
}

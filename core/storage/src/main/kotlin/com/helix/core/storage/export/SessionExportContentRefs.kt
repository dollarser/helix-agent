package com.helix.core.storage.export

import com.helix.core.storage.HelixDatabase
import com.helix.core.storage.content.ContentRef

/** Deduplication is SQLite-backed; only one previous hash is retained in application memory. */
internal object SessionExportContentRefs {
    fun visit(
        database: HelixDatabase,
        sessionId: String,
        consume: (ContentRef) -> Unit,
    ) {
        val query =
            "SELECT CASE WHEN length(CAST(contentRef AS BLOB)) <= 4096 THEN contentRef ELSE NULL END FROM (" +
                "SELECT contentRef FROM messages WHERE sessionId = ? AND contentRef IS NOT NULL UNION " +
                "SELECT r.contentRef FROM tool_results r JOIN tool_calls c ON c.id=r.toolCallId " +
                "JOIN turns t ON t.id=c.turnId WHERE t.sessionId = ? AND r.contentRef IS NOT NULL) " +
                "ORDER BY substr(contentRef,-66,64),contentRef"
        var previous: ContentRef? = null
        database.query(query, arrayOf(sessionId, sessionId)).use { cursor ->
            while (cursor.moveToNext()) {
                check(!cursor.isNull(0)) { "Content reference exceeds export limit" }
                val encoded = cursor.getString(0)
                val ref = ContentRef.parse(encoded)
                // All production writers use this canonical form; do not mis-sort malformed legacy rows.
                require(encoded == ref.toStorageString()) { "Noncanonical stored content reference" }
                if (previous?.sha256 == ref.sha256) {
                    check(previous == ref) { "Conflicting content identities" }
                } else {
                    consume(ref)
                    previous = ref
                }
            }
        }
    }
}

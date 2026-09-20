package com.helix.core.storage.export

import android.database.Cursor
import com.helix.core.storage.HelixDatabase

/** Keyset pages keep SQLite's sorting buffers free of arbitrarily large argument/summary bodies. */
internal class SessionExportRows(
    private val database: HelixDatabase,
) {
    fun visit(
        query: SessionExportQuery,
        sessionId: String,
        checkCancelled: () -> Unit,
        consume: (Cursor) -> Unit,
    ): Long {
        var after: List<Any>? = null
        val rowSql = query.rowSql()
        var total = 0L
        var count: Int
        do {
            checkCancelled()
            val arguments = listOf(sessionId) + after.orEmpty()
            count = 0
            database.query(query.keysSql(after != null), arguments.toTypedArray()).use { keys ->
                while (keys.moveToNext()) {
                    checkCancelled()
                    val id = keys.getString(0)
                    require(id.toByteArray(Charsets.UTF_8).size <= SessionExportQuery.CELL_BYTES) {
                        "Stable source identity exceeds export limits"
                    }
                    read(rowSql, id, consume)
                    after = query.sortColumns.map { column -> key(keys, column) }
                    count++
                    total++
                }
            }
        } while (count == SessionExportQuery.PAGE_ROWS)
        return total
    }

    private fun read(
        sql: String,
        id: String,
        consume: (Cursor) -> Unit,
    ) {
        database.query(sql, arrayOf(id)).use { row ->
            check(row.moveToFirst()) { "Snapshot source row disappeared" }
            consume(row)
            check(!row.moveToNext()) { "Snapshot identity is not unique" }
        }
    }

    private fun key(
        cursor: Cursor,
        name: String,
    ): Any {
        val index = cursor.getColumnIndexOrThrow(name)
        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
            else -> error("Export ordering requires a persisted non-null scalar")
        }
    }
}

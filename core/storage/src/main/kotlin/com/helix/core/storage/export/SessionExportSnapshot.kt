package com.helix.core.storage.export

import android.database.Cursor
import com.helix.core.storage.HelixDatabase
import com.helix.core.storage.content.ContentRef
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Private relation snapshot, never a deliverable. Content verification happens after its transaction. */
internal class SessionExportSnapshot(
    val file: File,
    val sessionId: String,
    val snapshotId: String,
    val capturedAt: Long,
) : AutoCloseable {
    override fun close() {
        if (file.exists() && !file.delete()) throw IOException("Could not remove export temporary file")
    }
}

internal class SessionExportSnapshotter(
    private val database: HelixDatabase,
) {
    fun capture(
        sessionId: String,
        directory: File,
        checkCancelled: () -> Unit,
    ): SessionExportSnapshot {
        require(sessionId.isNotBlank())
        check(directory.isDirectory || directory.mkdirs())
        val snapshot =
            SessionExportSnapshot(
                File.createTempFile("session-export-", ".snapshot", directory),
                sessionId,
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
            )
        var successful = false
        var observedAt = snapshot.capturedAt
        try {
            FileOutputStream(snapshot.file).use { output ->
                val sink = SnapshotSink(output, checkCancelled)
                // Only local bounded staging occurs here. Never SAF, content reads or model/tool work.
                database.runInTransaction {
                    SessionExportQuery.ALL.forEach { query ->
                        val count =
                            SessionExportRows(database).visit(query, sessionId, checkCancelled) { cursor ->
                                if (query.type == SessionExportType.SESSION) observedAt = System.currentTimeMillis()
                                sink.write(query, cursor)
                            }
                        if (query.type == SessionExportType.SESSION) check(count == 1L) { "Session unavailable" }
                    }
                    SessionExportContentRefs.visit(database, sessionId, sink::writeReference)
                }
                output.fd.sync()
            }
            successful = true
            return SessionExportSnapshot(snapshot.file, sessionId, snapshot.snapshotId, observedAt)
        } finally {
            if (!successful) snapshot.close()
        }
    }
}

private class SnapshotSink(
    private val output: FileOutputStream,
    private val checkCancelled: () -> Unit,
) {
    private var total = 0L

    fun write(
        query: SessionExportQuery,
        cursor: Cursor,
    ) {
        check(!cursor.isNull(0)) { "Stable source identity exceeds export limits" }
        val data = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        val omitted = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        query.fields.forEachIndexed { index, name ->
            checkCancelled()
            val column = index * 2
            val size = if (cursor.isNull(column + 1)) null else cursor.getLong(column + 1)
            if (size != null && size > SessionExportQuery.CELL_BYTES) omitted[name] = JsonPrimitive(size)
            data[name] =
                when (cursor.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> JsonNull
                    Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(column))
                    Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(column))
                    else -> error("Unsupported stored export field type")
                }
        }
        emit(query.type, "${query.identityPrefix}:${cursor.getString(0)}", JsonObject(data), JsonObject(omitted))
    }

    fun writeReference(ref: ContentRef) {
        checkCancelled()
        emit(
            SessionExportType.CONTENT,
            "content:${ref.sha256}",
            buildJsonObject { put("contentRef", ref.toStorageString()) },
            buildJsonObject {},
        )
    }

    private fun emit(
        type: SessionExportType,
        recordId: String,
        data: JsonObject,
        omitted: JsonObject,
    ) {
        val line =
            buildJsonObject {
                put("type", type.wireName)
                put("recordId", recordId)
                put("data", data)
                put("omittedFields", omitted)
            }.toString().toByteArray(Charsets.UTF_8)
        require(line.size < SessionExportFormat.LINE_BYTES) { "Snapshot row exceeds export limit" }
        require(line.size + 1L <= SessionExportFormat.FILE_BYTES - total) { "Snapshot exceeds export limit" }
        output.write(line)
        output.write('\n'.code)
        total += line.size + 1L
    }
}

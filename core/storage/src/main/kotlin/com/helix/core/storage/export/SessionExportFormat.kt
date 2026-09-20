package com.helix.core.storage.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.OutputStream

object SessionExportFormat {
    const val NAME = "helix.session-export"
    const val VERSION = 1
    const val INLINE_BYTES = 32 * 1024
    const val LINE_BYTES = 256 * 1024
    const val FILE_BYTES = 128L * 1024 * 1024
    const val COPY_BUFFER_BYTES = 32 * 1024
}

enum class SessionExportType(
    val wireName: String,
) {
    HEADER("header"),
    SESSION("session"),
    TURN("turn"),
    MESSAGE("message"),
    MODEL_CALL("model_call"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    EXECUTION("execution"),
    APPROVAL("approval"),
    ATTACHMENT("attachment"),
    ARTIFACT("artifact"),
    COMPACTION("compaction"),
    USAGE("usage"),
    GOAL_RUN("goal_run"),
    GOAL_BINDING("goal_binding"),
    CONTENT("content"),
    COMPLETE("complete"),
}

/** A bounded format writer, not an execution journal. The owner must close the destination successfully. */
internal class SessionExportWriter(
    private val output: OutputStream,
    private val exportId: String,
    private val sessionId: String,
    private val maxBytes: Long = SessionExportFormat.FILE_BYTES,
) {
    private var sequence = 0L
    private var bytes = 0L
    private var ended = false
    private val counts = SessionExportType.entries.associateWith { 0L }.toMutableMap()

    init {
        require(exportId.isNotBlank() && sessionId.isNotBlank() && maxBytes > 0)
    }

    fun append(
        type: SessionExportType,
        recordId: String,
        data: JsonObject,
    ) {
        check(!ended)
        require(type != SessionExportType.COMPLETE) { "Use finish for the completion record" }
        require((sequence == 0L) == (type == SessionExportType.HEADER))
        require(recordId.startsWith("${type.wireName}:"))
        write(type, recordId, data)
    }

    fun finish(contentStatistics: JsonObject) {
        check(!ended && sequence >= 2 && counts.getValue(SessionExportType.SESSION) == 1L)
        // A failure during the tail write must never permit a second completion attempt.
        ended = true
        val data =
            buildJsonObject {
                put("complete", true)
                put("recordCount", sequence + 1)
                put(
                    "counts",
                    buildJsonObject {
                        counts.forEach { (type, count) ->
                            put(type.wireName, if (type == SessionExportType.COMPLETE) 1 else count)
                        }
                    },
                )
                put("contentStatistics", contentStatistics)
            }
        write(SessionExportType.COMPLETE, "complete:$sessionId", data)
    }

    private fun write(
        type: SessionExportType,
        recordId: String,
        data: JsonObject,
    ) {
        // Validation, allocation or I/O failure poisons this export instead of allowing skipped rows.
        ended = true
        val line =
            buildJsonObject {
                put("format", SessionExportFormat.NAME)
                put("formatVersion", SessionExportFormat.VERSION)
                put("exportId", exportId)
                put("sequence", sequence)
                put("type", type.wireName)
                put("recordId", recordId)
                put("sessionId", sessionId)
                put("data", data)
            }.toString().toByteArray(Charsets.UTF_8)
        require(line.size < SessionExportFormat.LINE_BYTES) { "Export line exceeds byte limit" }
        require(line.size + 1L <= maxBytes - bytes) { "Export exceeds byte limit" }
        output.write(line)
        output.write('\n'.code)
        bytes += line.size + 1L
        sequence++
        counts[type] = counts.getValue(type) + 1
        ended = type == SessionExportType.COMPLETE
    }
}

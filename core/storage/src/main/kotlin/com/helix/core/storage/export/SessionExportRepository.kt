package com.helix.core.storage.export

import com.helix.core.storage.HelixDatabase
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** User-requested read-only export. No model, tool, recovery or remote I/O can be initiated here. */
class SessionExportRepository internal constructor(
    private val database: HelixDatabase,
    private val contentStore: ContentStore,
) {
    fun prepare(
        sessionId: String,
        directory: File,
        appVersion: String,
        sanitizer: SessionExportSanitizer,
        checkCancelled: () -> Unit,
    ): PreparedSessionExport =
        SessionExportSnapshotter(database).capture(sessionId, directory, checkCancelled).use { snapshot ->
            val target = File.createTempFile("session-export-", ".jsonl", directory)
            var successful = false
            try {
                val digest = stage(snapshot, target, appVersion, sanitizer, checkCancelled)
                checkCancelled()
                val prepared = PreparedSessionExport(target, snapshot.snapshotId, target.length(), digest)
                snapshot.close()
                successful = true
                prepared
            } finally {
                if (!successful) check(!target.exists() || target.delete()) { "Could not remove incomplete export" }
            }
        }

    private fun stage(
        snapshot: SessionExportSnapshot,
        target: File,
        appVersion: String,
        sanitizer: SessionExportSanitizer,
        checkCancelled: () -> Unit,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        SessionExportIndex.build(snapshot, checkCancelled).use { index ->
            FileOutputStream(target).use { output ->
                val writer =
                    SessionExportWriter(DigestOutputStream(output, digest), snapshot.snapshotId, snapshot.sessionId)
                materialize(
                    snapshot,
                    writer,
                    appVersion,
                    sanitizer,
                    checkCancelled,
                    SessionExportReferences(index::contains),
                )
                output.fd.sync()
            }
        }
        return digest.digest()
    }

    private fun materialize(
        snapshot: SessionExportSnapshot,
        writer: SessionExportWriter,
        appVersion: String,
        sanitizer: SessionExportSanitizer,
        checkCancelled: () -> Unit,
        references: SessionExportReferences,
    ) {
        val projection = SessionExportProjection(sanitizer)
        val content = SessionExportContent(contentStore, { sanitizer.sanitize(it, false) }, checkCancelled)
        val statistics = ContentStatistics()
        writer.append(SessionExportType.HEADER, "header:${snapshot.sessionId}", header(snapshot, appVersion))
        // Bounded sequential passes preserve type groups without retaining messages or content IDs in memory.
        SessionExportJson.rows(snapshot.file, checkCancelled) { row ->
            val type = type(row)
            if (type != SessionExportType.CONTENT) {
                val data = references.describe(type, projection.project(type, row))
                statistics.observe(data)
                writer.append(type, row.getValue("recordId").jsonPrimitive.content, data)
            }
        }
        val compaction = SessionExportCompaction(contentStore, projection)
        SessionExportJson.rows(snapshot.file, checkCancelled) { row ->
            val data = row.getValue("data").jsonObject
            if (type(row) == SessionExportType.MESSAGE &&
                data["kind"]?.jsonPrimitive?.content == "CONTEXT_CHECKPOINT_V1"
            ) {
                val boundary = references.describe(SessionExportType.COMPACTION, compaction.describe(data))
                statistics.observe(boundary)
                writer.append(
                    SessionExportType.COMPACTION,
                    "compaction:${data.getValue("id").jsonPrimitive.content}",
                    boundary,
                )
            }
        }
        SessionExportJson.rows(snapshot.file, checkCancelled) { row ->
            if (type(row) == SessionExportType.CONTENT) {
                val encoded =
                    row
                        .getValue("data")
                        .jsonObject
                        .getValue("contentRef")
                        .jsonPrimitive.content
                val data = content.describe(ContentRef.parse(encoded))
                statistics.observe(data)
                writer.append(SessionExportType.CONTENT, row.getValue("recordId").jsonPrimitive.content, data)
            }
        }
        checkCancelled()
        writer.finish(statistics.json())
    }

    private fun type(row: JsonObject): SessionExportType =
        SessionExportType.entries.single { it.wireName == row.getValue("type").jsonPrimitive.content }

    private fun header(
        snapshot: SessionExportSnapshot,
        appVersion: String,
    ): JsonObject =
        buildJsonObject {
            put("snapshotId", snapshot.snapshotId)
            put("capturedAt", snapshot.capturedAt)
            put("appVersion", appVersion)
            put("snapshotBoundary", "single_room_transaction_persisted_relations_content_verified_afterwards")
            put(
                "ordering",
                "v1_fixed_groups_forward_references_allowed_not_execution_order",
            )
            put(
                "groupOrder",
                "header,session,turn,message,model_call,tool_call,tool_result,execution,approval," +
                    "artifact,attachment,goal_run,goal_binding,usage:goal,usage:audit,compaction,content,complete",
            )
            put("referencePolicy", "included_or_explicitly_not_in_selected_snapshot_not_recorded_omitted_limit")
            put("recordTypes", buildJsonArray { SessionExportType.entries.forEach { add(it.wireName) } })
            put("contentPolicy", "small_text_inline_large_content_reference_only")
            put("selfContained", false)
            put("anonymized", false)
            put(
                "credentialPolicy",
                "configuration_excluded_existing_application_credential_rules_unknown_free_text_not_guaranteed",
            )
            put("inlineBytes", SessionExportFormat.INLINE_BYTES)
            put("lineBytes", SessionExportFormat.LINE_BYTES)
            put("fileBytes", SessionExportFormat.FILE_BYTES)
            put("usagePolicy", "unknown_is_not_zero_overlapping_levels_do_not_sum")
        }
}

private class ContentStatistics {
    private val counts =
        mutableMapOf(
            "inline" to 0L,
            "reference_only" to 0L,
            "missing" to 0L,
            "changed" to 0L,
            "redacted" to 0L,
            "omitted_limit" to 0L,
        )

    fun observe(value: JsonElement) {
        if (value is JsonObject) {
            val omitted = value["omittedFields"] as? JsonObject
            if (omitted != null) counts["omitted_limit"] = counts.getValue("omitted_limit") + omitted.size
            val availability = (value["availability"] as? JsonPrimitive)?.contentOrNull
            if (availability in counts) counts[availability!!] = counts.getValue(availability) + 1
            value.values.forEach(::observe)
        }
    }

    fun json(): JsonObject = buildJsonObject { counts.forEach { (name, count) -> put(name, count) } }
}

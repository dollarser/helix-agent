package com.helix.core.storage.backup

import android.database.Cursor
import androidx.room.RoomDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Explicit rule/history records only; WorkManager work specs and OS alarm/notification identities are not portable. */
class AuxiliaryBackupRepository internal constructor(
    private val database: RoomDatabase,
    private val kind: Kind,
) {
    enum class Kind { SCHEDULES, REMINDERS }

    private val columns get() =
        when (kind) {
            Kind.SCHEDULES -> {
                linkedMapOf(
                    "scheduled_rules" to
                        (
                            "id,title,prompt,scopeJson,scopeHash,sessionId,localTime,zone,repeat," +
                                "revision,enabled,nextAt,status,creator,updatedAt"
                        ).split(','),
                    "scheduled_occurrences" to
                        (
                            "ruleId,revision,dueAt,ruleJson,outcome,recordedAt," +
                                "lastAttemptId,skippedThrough"
                        ).split(','),
                    "scheduled_attempts" to
                        (
                            "id,ruleId,revision,dueAt,sessionId,state,createdAt," +
                                "turnId,completedAt,detail"
                        ).split(','),
                )
            }

            Kind.REMINDERS -> {
                linkedMapOf(
                    "reminders" to
                        (
                            "id,title,body,localTime,zone,repeat,exact,nextAt,revision,state," +
                                "scheduling,lastOutcome,updatedAt"
                        ).split(','),
                    "reminder_events" to "reminderId,revision,dueAt,outcome,recordedAt".split(','),
                )
            }
        }

    fun capture(checkActive: () -> Unit): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        var count = 0
        database.runInTransaction {
            columns.forEach { (table, fields) ->
                val rows = mutableListOf<JsonElement>()
                val query = "SELECT ${fields.joinToString(",")} FROM $table"
                database.openHelper.readableDatabase.query(query).use { cursor ->
                    while (cursor.moveToNext()) {
                        checkActive()
                        require(++count <= 50_000)
                        rows += JsonObject(fields.mapIndexed { index, name -> name to cell(cursor, index) }.toMap())
                    }
                }
                result[table] = JsonArray(rows)
            }
        }
        return JsonObject(result)
    }

    private fun cell(
        cursor: Cursor,
        index: Int,
    ): JsonElement =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> {
                JsonNull
            }

            Cursor.FIELD_TYPE_INTEGER -> {
                JsonPrimitive(cursor.getLong(index))
            }

            Cursor.FIELD_TYPE_STRING -> {
                val text = cursor.getString(index)
                require(text.length <= 1_048_576)
                JsonPrimitive(text)
            }

            else -> {
                error("BACKUP_FIELD_INVALID")
            }
        }

    fun restore(
        snapshot: JsonObject,
        sessionIds: Set<String>,
        turnIds: Set<String>,
        checkActive: () -> Unit,
    ) {
        require(snapshot.keys == columns.keys)
        require(snapshot.values.sumOf { (it as JsonArray).size } <= 50_000) { "BACKUP_RECORD_LIMIT" }
        val db = database.openHelper.writableDatabase
        database.runInTransaction {
            columns.forEach { (table, fields) ->
                db.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst() && it.getLong(0) == 0L) }
                val rows = snapshot.getValue(table) as JsonArray
                require(rows.size <= 50_000)
                rows.forEach { element ->
                    checkActive()
                    val source = element as JsonObject
                    require(source.keys == fields.toSet())
                    val row = source.toMutableMap()
                    normalize(table, source, row, sessionIds, turnIds)
                    val names = fields.joinToString(",")
                    val slots = fields.joinToString(",") { "?" }
                    db.execSQL(
                        "INSERT INTO $table ($names) VALUES ($slots)",
                        fields.map { key -> scalar(row.getValue(key)) }.toTypedArray(),
                    )
                }
            }
            if (kind == Kind.SCHEDULES) {
                db
                    .query(
                        "SELECT 1 FROM scheduled_attempts a LEFT JOIN scheduled_rules r " +
                            "ON a.ruleId=r.id WHERE r.id IS NULL LIMIT 1",
                    ).use {
                        require(!it.moveToFirst()) { "BACKUP_SCHEDULE_RELATION_INVALID" }
                    }
            } else {
                db
                    .query(
                        "SELECT 1 FROM reminder_events e LEFT JOIN reminders r " +
                            "ON e.reminderId=r.id WHERE r.id IS NULL LIMIT 1",
                    ).use {
                        require(!it.moveToFirst()) { "BACKUP_REMINDER_RELATION_INVALID" }
                    }
            }
        }
        checkpoint()
    }

    private fun normalize(
        table: String,
        source: JsonObject,
        row: MutableMap<String, JsonElement>,
        sessionIds: Set<String>,
        turnIds: Set<String>,
    ) {
        val missingSession =
            table in setOf("scheduled_rules", "scheduled_attempts") &&
                source.getValue("sessionId").jsonPrimitive.content !in sessionIds
        if (table == "scheduled_attempts") normalizeAttempt(source, row, missingSession, turnIds)
        if (table == "scheduled_rules") {
            row["enabled"] = JsonPrimitive(0)
            row["nextAt"] = JsonNull
            row["status"] = JsonPrimitive(if (missingSession) "SCOPE_CHANGED" else "PAUSED")
            row["scopeHash"] = JsonPrimitive("")
            val scopeText = source.getValue("scopeJson").jsonPrimitive.content
            row["scopeJson"] = JsonPrimitive(scopeDefinition(scopeText))
        }
        if (table == "scheduled_occurrences") normalizeOccurrence(source, row)
        if (table == "reminders") {
            if (source.getValue("state").jsonPrimitive.content == "ACTIVE") {
                row["state"] = JsonPrimitive("PAUSED")
            }
            row["scheduling"] = JsonPrimitive("NONE")
            row["nextAt"] = JsonNull
        }
    }

    private fun normalizeAttempt(
        source: JsonObject,
        row: MutableMap<String, JsonElement>,
        missingSession: Boolean,
        turnIds: Set<String>,
    ) {
        if (source.getValue("state").jsonPrimitive.content !in
            setOf("ADMITTING", "RUNNING", "STOP_REQUESTED", "WAITING")
        ) {
            return
        }
        // Deleted sources remain historical identities; never retarget them or revive a call.
        val turn = source.getValue("turnId").jsonPrimitive.content
        val unavailable = missingSession || (turn.isNotEmpty() && turn !in turnIds)
        row["state"] = JsonPrimitive("INTERRUPTED")
        row["detail"] = JsonPrimitive(if (unavailable) "RESTORE_SOURCE_UNAVAILABLE" else "RESTORE_RECONFIRM_REQUIRED")
    }

    private fun normalizeOccurrence(
        source: JsonObject,
        row: MutableMap<String, JsonElement>,
    ) {
        val ruleText = source.getValue("ruleJson").jsonPrimitive.content
        val definition = Json.parseToJsonElement(ruleText) as JsonObject
        val scopeText = definition.getValue("scope").jsonPrimitive.content
        row["ruleJson"] =
            JsonPrimitive(
                JsonObject(
                    definition +
                        mapOf(
                            "scopeHash" to JsonPrimitive(""),
                            "scope" to JsonPrimitive(scopeDefinition(scopeText)),
                        ),
                ).toString(),
            )
        val unfinished = setOf("ADMITTING", "RUNNING", "PENDING")
        if (source.getValue("outcome").jsonPrimitive.content in unfinished) {
            row["outcome"] = JsonPrimitive("INTERRUPTED")
        }
    }

    private fun scopeDefinition(text: String): String {
        val source = Json.parseToJsonElement(text) as JsonObject
        val budget = source.getValue("maxRuntimeMillis")
        require(!budget.jsonPrimitive.isString && budget.jsonPrimitive.long > 0)
        return JsonObject(mapOf("maxRuntimeMillis" to budget)).toString()
    }

    private fun scalar(value: JsonElement): Any? =
        when (value) {
            JsonNull -> null
            is JsonPrimitive -> if (value.isString) value.content else value.long
            else -> error("BACKUP_FIELD_INVALID")
        }

    fun checkpoint() {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            check(it.moveToFirst() && it.getInt(0) == 0) { "BACKUP_CHECKPOINT_BUSY" }
        }
    }
}

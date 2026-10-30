package com.helix.core.storage.backup

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ToolEffectReviewDecision
import com.helix.core.storage.HelixDatabase
import com.helix.core.storage.SearchText
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.MessageSearchEntity
import com.helix.core.storage.repository.ProviderConfigSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** Current domain records only; SQL identifiers always come from the compiled whitelist. */
@Suppress("TooManyFunctions") // One transactional store port; validation and projections share its authority.
class PortableStateRepository internal constructor(
    private val database: HelixDatabase,
    private val content: ContentStore,
) {
    fun capture(checkActive: () -> Unit): JsonObject {
        val tables = linkedMapOf<String, JsonElement>()
        var count = 0
        var bytes = 0L
        database.runInTransaction {
            PortableTables.columns.forEach { (table, columns) ->
                val rows = mutableListOf<JsonElement>()
                val query = "SELECT ${columns.joinToString(",")} FROM $table"
                database.openHelper.readableDatabase.query(query).use { cursor ->
                    while (cursor.moveToNext()) {
                        checkActive()
                        // Restoring a workspace retains its historical identity and adds a current binding.
                        count += if (table == "workspaces") 2 else 1
                        require(count <= 500_000) { "BACKUP_RECORD_LIMIT" }
                        val row = JsonObject(columns.mapIndexed { i, name -> name to cell(cursor, i) }.toMap())
                        bytes += row.toString().toByteArray().size
                        require(bytes <= 64L * 1024 * 1024) { "BACKUP_METADATA_LIMIT" }
                        rows += row
                    }
                }
                tables[table] = JsonArray(rows)
            }
        }
        return JsonObject(tables).also(::validateShape)
    }

    fun references(snapshot: JsonObject): Set<ContentRef> {
        validateShape(snapshot)
        return snapshot.values
            .flatMap { (it as JsonArray).toList() }
            .flatMap { element ->
                (element as JsonObject)
                    .filterKeys { it in CONTENT_FIELDS }
                    .values
                    .filter { it != JsonNull && it.jsonPrimitive.content.startsWith('{') }
                    .map { ContentRef.parse(it.jsonPrimitive.content) }
            }.toSet()
    }

    /** Destination must be a newly created private database, never the active profile. */
    fun restore(
        snapshot: JsonObject,
        checkActive: () -> Unit,
    ) {
        validateShape(snapshot)
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            db.execSQL("PRAGMA defer_foreign_keys=ON")
            requireEmpty(db)
            val (reviewTurns, reviewGoals) = reviewScope(snapshot)
            val now = System.currentTimeMillis()
            PortableTables.columns.forEach { (table, columns) ->
                rows(snapshot, table).forEach { original ->
                    checkActive()
                    val row = PortableRestoreLifecycle.apply(table, original, reviewTurns, reviewGoals, now)
                    val names = columns.joinToString(",")
                    val slots = columns.joinToString(",") { "?" }
                    db.execSQL(
                        "INSERT INTO $table ($names) VALUES ($slots)",
                        columns.map { scalar(row.getValue(it)) }.toTypedArray(),
                    )
                }
            }
            db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "BACKUP_RELATION_INVALID" } }
            appendInterruptedResults(db)
            val files = requireNotNull(content as? FileContentStore)
            references(snapshot).forEach { files.verifiedBinary(it, 512L * 1024 * 1024, checkActive) }
            rebuildSearch(db, snapshot, checkActive)
        }
    }

    private fun reviewScope(snapshot: JsonObject): Pair<Set<String>, Set<String>> {
        val reviewed =
            rows(
                snapshot,
                "tool_call_reviews",
            ).map { it.getValue("toolCallId").jsonPrimitive.content }.toSet()
        val turns =
            rows(snapshot, "tool_calls")
                .filter {
                    it.getValue("id").jsonPrimitive.content !in reviewed &&
                        it.getValue("state").jsonPrimitive.content in setOf("RUNNING", "NEEDS_REVIEW", "INTERRUPTED")
                }.map { it.getValue("turnId").jsonPrimitive.content }
                .toSet()
        val runs =
            rows(snapshot, "goal_turn_bindings")
                .filter { it.getValue("turnId").jsonPrimitive.content in turns }
                .map { it.getValue("runId").jsonPrimitive.content }
                .toSet()
        val goals =
            rows(snapshot, "goal_runs")
                .filter { it.getValue("id").jsonPrimitive.content in runs }
                .map { it.getValue("goalId").jsonPrimitive.content }
                .toSet()
        return turns to goals
    }

    private fun appendInterruptedResults(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO tool_results (id,toolCallId,status,summary,contentRef,verified) " +
                "SELECT lower(hex(randomblob(16))),c.id," +
                "CASE WHEN c.state='CANCELLED' THEN 'CANCELLED' ELSE 'UNKNOWN' END," +
                "CASE WHEN c.state='CANCELLED' THEN 'Restore: call never started; not replayed' " +
                "ELSE 'Restore: external result requires review; not replayed' END,NULL,0 " +
                "FROM tool_calls c LEFT JOIN tool_results r ON r.toolCallId=c.id WHERE r.id IS NULL " +
                "AND c.state IN ('CANCELLED','NEEDS_REVIEW','INTERRUPTED')",
        )
    }

    private fun rebuildSearch(
        db: SupportSQLiteDatabase,
        snapshot: JsonObject,
        checkActive: () -> Unit,
    ) {
        val query =
            "SELECT id,sessionId,contentRef FROM messages " +
                "WHERE role IN ('USER','ASSISTANT') AND contentRef IS NOT NULL"
        db.query(query).use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                val ref = cursor.getString(2)
                val body = content.readBounded(ContentRef.parse(ref), 64 * 1024 * 1024)
                database.sessionSearchDao().insert(
                    MessageSearchEntity(cursor.getString(0), cursor.getString(1), ref, body, SearchText.fold(body)),
                )
            }
        }
        rows(snapshot, "sessions").forEach { row ->
            db.execSQL(
                "UPDATE sessions SET searchTitle=? WHERE id=?",
                arrayOf(
                    SearchText.fold(row.getValue("title").jsonPrimitive.content),
                    row.getValue("id").jsonPrimitive.content,
                ),
            )
        }
    }

    fun sealPortableCredentials(
        directory: File,
        values: Map<String, String>,
    ) {
        require(!directory.exists() || directory.listFiles().orEmpty().isEmpty())
        val target =
            com.helix.core.storage
                .AndroidKeystoreSecretStore(directory)
        values.forEach { (alias, value) ->
            require(alias.startsWith("restored-"))
            val reference =
                com.helix.core.model
                    .SecretAlias(alias)
            target.put(reference, value)
            check(target.get(reference) == value) { "BACKUP_CREDENTIAL_STORE_UNVERIFIED" }
        }
    }

    fun checkpoint() {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
            check(it.moveToFirst() && it.getInt(0) == 0) { "BACKUP_CHECKPOINT_BUSY" }
        }
    }

    fun configureManagedClone(
        id: String,
        directory: File,
    ) {
        require(id.matches(Regex("ws-[a-f0-9-]{36}")) && directory.name == id)
        val witness =
            com.helix.core.storage.WorkspaceNativeIdentity
                .witness(directory.toPath())
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workspaces SET ownership='MANAGED',backend='PATH',locator=?,identityKey=?," +
                "availability='READY',witness=? WHERE id=?",
            arrayOf(id, "managed:$id", witness, id),
        )
    }

    private fun requireEmpty(db: SupportSQLiteDatabase) {
        check(
            PortableTables.columns.keys.all { table ->
                db.query("SELECT COUNT(*) FROM $table").use {
                    it.moveToFirst()
                    it.getLong(0) == 0L
                }
            },
        ) { "BACKUP_TARGET_NOT_EMPTY" }
    }

    private fun validateShape(snapshot: JsonObject) {
        require(snapshot.keys == PortableTables.columns.keys) { "BACKUP_TABLES_INVALID" }
        var count = 0
        snapshot.forEach { (table, values) ->
            require(values is JsonArray)
            count += values.size
            require(count <= 500_000)
            values.forEach { validateRow(table, it) }
        }
        val calls = rows(snapshot, "tool_calls").associateBy { it.getValue("id").jsonPrimitive.content }
        rows(snapshot, "tool_call_reviews").forEach { review ->
            val call = calls.getValue(review.getValue("toolCallId").jsonPrimitive.content)
            require(call.getValue("state").jsonPrimitive.content in setOf("NEEDS_REVIEW", "INTERRUPTED"))
        }
    }

    private fun validateRow(
        table: String,
        value: JsonElement,
    ) {
        require(value is JsonObject && value.keys == PortableTables.columns.getValue(table).toSet())
        value.forEach { (column, cell) ->
            scalar(cell)
            if (cell != JsonNull) {
                require(cell is JsonPrimitive && cell.isString == (column !in PortableTables.integers.getValue(table)))
                require(!cell.isString || cell.content.length <= 1_048_576)
            }
        }
        val state = value["state"]?.jsonPrimitive?.content
        when (table) {
            "turns" -> {
                com.helix.core.model.TurnState
                    .valueOf(requireNotNull(state))
            }

            "tool_calls" -> {
                com.helix.core.model.ToolCallState
                    .valueOf(requireNotNull(state))
            }

            "goals" -> {
                com.helix.core.model.GoalState
                    .valueOf(requireNotNull(state))
            }

            "plans" -> {
                com.helix.core.storage.repository.PlanLifecycleState
                    .valueOf(requireNotNull(state))
            }

            "session_inputs" -> {
                com.helix.core.storage.repository.SessionInputState
                    .valueOf(requireNotNull(state))
                require(value.getValue("schemaVersion").jsonPrimitive.long == 1L)
            }

            "session_run_controls" -> {
                require(value.getValue("configVersion").jsonPrimitive.long == 1L)
            }

            "tool_call_reviews" -> {
                ToolEffectReviewDecision.valueOf(value.getValue("decision").jsonPrimitive.content)
            }

            "provider_configs" -> {
                validateProvider(value)
            }
        }
    }

    private fun validateProvider(row: JsonObject) {
        fun text(key: String) =
            row
                .getValue(key)
                .takeUnless { it == JsonNull }
                ?.jsonPrimitive
                ?.content
        ProviderConfigSpec(
            requireNotNull(text("id")),
            requireNotNull(text("displayName")),
            text("protocol")?.let(ProviderProtocol::parse),
            text("endpoint"),
            requireNotNull(text("model")),
            requireNotNull(text("headersJson")),
            text("secretAlias"),
            requireNotNull(text("capabilitySnapshot")),
            requireNotNull(text("provisioningKind")),
            requireNotNull(text("transportKind")),
            requireNotNull(text("authKind")),
        ).toEntity()
    }

    private fun scalar(value: JsonElement): Any? =
        when (value) {
            JsonNull -> null
            is JsonPrimitive -> if (value.isString) value.content else value.long
            else -> error("BACKUP_FIELD_INVALID")
        }

    private fun cell(
        cursor: Cursor,
        index: Int,
    ): JsonElement =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> JsonNull
            Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
            Cursor.FIELD_TYPE_STRING -> JsonPrimitive(cursor.getString(index).also { require(it.length <= 1_048_576) })
            else -> error("BACKUP_FIELD_UNSUPPORTED")
        }

    private fun rows(
        snapshot: JsonObject,
        table: String,
    ) = (snapshot.getValue(table) as JsonArray).map { it as JsonObject }

    private companion object {
        val CONTENT_FIELDS = setOf("contentRef", "textRef", "referenceContentRef", "evidenceRef")
    }
}

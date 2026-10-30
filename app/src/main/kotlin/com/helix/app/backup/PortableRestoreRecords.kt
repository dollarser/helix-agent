package com.helix.app.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Identity-preserving history, with fresh current-directory and credential bindings. */
internal class PortableRestoreRecords(
    source: JsonObject,
    private val attachmentIds: Set<String>,
    credentialValues: Map<String, String>,
    val restoreId: String = UUID.randomUUID().toString(),
) {
    val workspaceClones =
        AppBackupCapture.rows(source, "workspaces").associate {
            AppBackupCapture.text(it, "id") to "ws-${UUID.randomUUID()}"
        }
    private val aliases =
        AppBackupCapture.allowedCredentialAliases(source).associateWith {
            "restored-${UUID.randomUUID()}"
        }
    val credentials: Map<String, String>
    val records: JsonObject

    init {
        require(restoreId.matches(Regex("[a-f0-9-]{36}")))
        require(credentialValues.keys.all { it in aliases }) { "BACKUP_CREDENTIAL_SCOPE" }
        credentials = credentialValues.mapKeys { aliases.getValue(it.key) }
        val result = linkedMapOf<String, JsonElement>()
        source.forEach { (table, values) ->
            val rows = (values as JsonArray).map { transform(table, it as JsonObject) }.toMutableList()
            if (table == "workspaces") rows += AppBackupCapture.rows(source, table).map(::clone)
            result[table] = JsonArray(rows)
        }
        records = JsonObject(result)
    }

    private fun transform(
        table: String,
        original: JsonObject,
    ): JsonObject {
        val row = original.toMutableMap()

        fun text(key: String) = AppBackupCapture.text(original, key)

        fun set(
            key: String,
            value: String?,
        ) {
            row[key] = value?.let(::JsonPrimitive) ?: JsonNull
        }
        when (table) {
            "workspaces" -> {
                set("ownerSessionId", null)
            }

            "projects", "session_workspaces" -> {
                set("workspaceId", workspaceClones[text("workspaceId")])
            }

            "sessions" -> {
                set("directoryRef", null)
            }

            "artifacts" -> {
                if (text("id") in attachmentIds) {
                    set("relativePath", "scope:app:restored-$restoreId/attachments/${text("id")}")
                }
            }

            "provider_configs" -> {
                val alias = row["secretAlias"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                set("secretAlias", aliases[alias])
            }

            "mcp_servers", "a2a_agents" -> {
                val alias = row["authAlias"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                set("authAlias", aliases[alias])
            }

            "connector_installations" -> {
                val manifest = Json.parseToJsonElement(text("manifest")) as JsonObject
                val endpoints =
                    (manifest["endpoints"] as? JsonArray)?.map {
                        JsonObject((it as JsonObject) - "authBindingHash")
                    }
                if (endpoints != null) {
                    val restored = JsonObject(manifest + ("endpoints" to JsonArray(endpoints)))
                    set("manifest", restored.toString())
                }
            }
        }
        return JsonObject(row)
    }

    private fun clone(row: JsonObject): JsonObject {
        val id = workspaceClones.getValue(AppBackupCapture.text(row, "id"))
        return JsonObject(
            row +
                mapOf(
                    "id" to JsonPrimitive(id),
                    "backend" to JsonPrimitive("PATH"),
                    "ownership" to JsonPrimitive("MANAGED"),
                    "locator" to JsonPrimitive(id),
                    "identityKey" to JsonPrimitive("managed:$id"),
                    "availability" to JsonPrimitive("UNAVAILABLE"),
                    "witness" to JsonNull,
                ),
        )
    }
}

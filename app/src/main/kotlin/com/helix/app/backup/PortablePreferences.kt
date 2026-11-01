package com.helix.app.backup

import android.content.Context
import android.util.Xml
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Only named product preferences; account status, device grants and evidence caches are never copied. */
internal object PortablePreferences {
    private val authorizationStores =
        setOf(
            "helix-mobile-use",
            "helix-lan-scopes",
            "speech-consent",
            "knowledge-access",
            "directory-sync-authorization",
        )
    private val fixed =
        mapOf(
            "memory-settings" to setOf("enabled", "auto-global", "auto-project"),
            "com.helix.app.language" to setOf("choice"),
            "speech-services" to setOf("settings"),
            "file-library" to setOf("v1"),
            "knowledge-semantic" to setOf("model", "query-prefix", "document-prefix", "model-revision"),
        )

    private fun allowed(
        store: String,
        key: String,
    ): Boolean =
        if (store == "helix-ui") {
            key in setOf("run_control_v1", "goal_defaults_v1") ||
                listOf("provider-model-selection-v1-", "context-v1-", "debug-custom-api-").any(key::startsWith)
        } else {
            key in fixed[store].orEmpty()
        }

    fun capture(context: Context): JsonObject =
        JsonObject(
            (fixed.keys + "helix-ui").associateWith { name ->
                val entries = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                JsonObject(entries.filterKeys { allowed(name, it) }.mapValues { (_, value) -> encode(value) })
            },
        )

    private fun encode(value: Any?): JsonObject {
        val type: String
        val primitive: JsonPrimitive
        when (value) {
            is String -> {
                type = "string"
                primitive = JsonPrimitive(value)
            }

            is Boolean -> {
                type = "boolean"
                primitive = JsonPrimitive(value)
            }

            else -> {
                error("BACKUP_PREFERENCE_TYPE")
            }
        }
        return JsonObject(mapOf("type" to JsonPrimitive(type), "value" to primitive))
    }

    private fun writeEntry(
        xml: org.xmlpull.v1.XmlSerializer,
        name: String,
        key: String,
        entry: JsonElement,
    ) {
        require(allowed(name, key)) { "BACKUP_PREFERENCE_SCOPE" }
        val item = entry as JsonObject
        require(item.keys == setOf("type", "value"))
        when (item.getValue("type").jsonPrimitive.content) {
            "string" -> {
                val text = item.getValue("value").jsonPrimitive
                require(text.isString && text.content.length <= 1_048_576)
                xml.startTag(null, "string")
                xml.attribute(null, "name", key)
                xml.text(text.content)
                xml.endTag(null, "string")
            }

            "boolean" -> {
                val flag = item.getValue("value").jsonPrimitive
                require(!flag.isString)
                xml.startTag(null, "boolean")
                xml.attribute(null, "name", key)
                xml.attribute(null, "value", flag.boolean.toString())
                xml.endTag(null, "boolean")
            }

            else -> {
                error("BACKUP_PREFERENCE_TYPE")
            }
        }
    }

    fun stage(
        snapshot: JsonObject,
        directory: File,
    ): Map<String, File?> {
        require(snapshot.keys == fixed.keys + "helix-ui")
        require(directory.isDirectory || directory.mkdirs())
        val roots = linkedMapOf<String, File?>()
        snapshot.forEach { (name, value) ->
            val entries = value as JsonObject
            require(entries.size <= 4096)
            val file = File(directory, "$name.xml")
            file.outputStream().use { output ->
                val xml = Xml.newSerializer()
                xml.setOutput(output, "UTF-8")
                xml.startDocument("UTF-8", true)
                xml.startTag(null, "map")
                entries.forEach { (key, entry) -> writeEntry(xml, name, key, entry) }
                xml.endTag(null, "map")
                xml.endDocument()
                xml.flush()
            }
            roots["shared_prefs/$name.xml"] = file
            roots["shared_prefs/$name.xml.bak"] = null
        }
        authorizationStores.forEach { name ->
            val file =
                File(directory, "$name.xml").apply {
                    writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?><map />")
                }
            roots["shared_prefs/$name.xml"] = file
            roots["shared_prefs/$name.xml.bak"] = null
        }
        return roots
    }

    fun permittedRoot(path: String): Boolean =
        (fixed.keys + "helix-ui" + authorizationStores).any {
            path == "shared_prefs/$it.xml" || path == "shared_prefs/$it.xml.bak"
        }

    @android.annotation.SuppressLint("UseKtx") // Restore must verify the synchronous persistence result.
    fun refreshLoaded(
        context: Context,
        snapshot: JsonObject,
        resetAuthorization: Boolean = true,
    ) {
        if (resetAuthorization) {
            authorizationStores.forEach {
                check(
                    context
                        .getSharedPreferences(it, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit(),
                )
            }
        }
        snapshot.forEach { (name, value) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            (value as JsonObject).forEach { (key, entry) ->
                require(allowed(name, key))
                val item = entry as JsonObject
                when (item.getValue("type").jsonPrimitive.content) {
                    "string" -> editor.putString(key, item.getValue("value").jsonPrimitive.content)
                    "boolean" -> editor.putBoolean(key, item.getValue("value").jsonPrimitive.boolean)
                    else -> error("BACKUP_PREFERENCE_TYPE")
                }
            }
            check(editor.commit()) { "RESTORE_RESTART_REQUIRED" }
        }
    }
}

package com.helix.runtime.cli.client

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Public local credential readiness, not remote authentication or remaining quota. */
data class CliAccountState(
    val state: String,
    val revision: String? = null,
) {
    init {
        require(state in setOf("LOGGED_IN", "LOGGED_OUT", "CREDENTIAL_ERROR"))
        require((state == "LOGGED_IN") == (revision != null))
        require(revision == null || REVISION.matches(revision))
    }

    companion object {
        private val REVISION = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
        private val PROVIDERS = setOf("codex", "claude", "grok", "copilot")

        fun encode(accounts: Map<String, CliAccountState>): JsonObject {
            require(accounts.keys.all { it in PROVIDERS })
            return JsonObject(
                accounts.mapValues { (_, account) ->
                    buildJsonObject {
                        put("state", account.state)
                        put("revision", account.revision?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
            )
        }

        fun decode(value: JsonElement): Map<String, CliAccountState> {
            val accounts = value.jsonObject
            require(accounts.keys.all { it in PROVIDERS })
            return accounts.mapValues { (_, element) ->
                val record = element.jsonObject
                require(record.keys == setOf("state", "revision"))
                val state =
                    record
                        .getValue("state")
                        .jsonPrimitive
                        .also { require(it.isString) }
                        .content
                val revision =
                    record
                        .getValue("revision")
                        .takeUnless { it == JsonNull }
                        ?.jsonPrimitive
                        ?.also { require(it.isString) }
                        ?.content
                CliAccountState(state, revision)
            }
        }
    }
}

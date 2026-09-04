package com.helix.extensions.a2a

import com.helix.core.model.A2aAgentId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.Sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

@Suppress("TooManyFunctions")
internal object A2aAgentCardParser {
    fun parse(
        agentId: A2aAgentId,
        expectedOrigin: String,
        raw: String,
        extended: Boolean,
    ): A2aAgentCardSnapshot {
        require(raw.toByteArray().size <= MAX_CARD_BYTES) { "A2A Agent Card exceeds $MAX_CARD_BYTES bytes" }
        val card = Json.parseToJsonElement(raw) as? JsonObject ?: error("A2A Agent Card must be a JSON object")
        rejectUnknownRequiredExtensions(card)
        val canonical = card.canonicalJson()
        val defaultsIn = card.requiredStringArray("defaultInputModes", MAX_MODES, MAX_MODE_LENGTH)
        val defaultsOut = card.requiredStringArray("defaultOutputModes", MAX_MODES, MAX_MODE_LENGTH)
        val selected = selectInterface(card, expectedOrigin)
        val skills = card.requiredArray("skills", MAX_SKILLS).map { parseSkill(it, defaultsIn, defaultsOut) }
        require(skills.map { it.id }.toSet().size == skills.size) { "A2A Agent Card has duplicate Skill IDs" }
        return A2aAgentCardSnapshot(
            agentId = agentId,
            name = card.requiredString("name", MAX_NAME_LENGTH),
            description = card.requiredString("description", MAX_DESCRIPTION_LENGTH),
            agentVersion = card.requiredString("version", MAX_VERSION_LENGTH),
            provider = card.optionalProvider(),
            capabilities = card.requiredCapabilities(),
            selectedInterface = selected,
            defaultInputModes = defaultsIn,
            defaultOutputModes = defaultsOut,
            skills = skills,
            cardHash = Sha256(hash(canonical)),
            canonicalCardJson = canonical,
            extended = extended,
        )
    }

    private fun selectInterface(
        card: JsonObject,
        expectedOrigin: String,
    ): A2aInterfaceSnapshot {
        val candidates = card.requiredArray("supportedInterfaces", MAX_INTERFACES)
        candidates.forEach { value ->
            val item = value as? JsonObject ?: error("A2A interface must be an object")
            val bindingName = item.requiredString("protocolBinding", MAX_BINDING_LENGTH)
            val binding = A2aBinding.entries.firstOrNull { it.wireName == bindingName } ?: return@forEach
            val version = item.requiredString("protocolVersion", MAX_VERSION_LENGTH)
            if (version != SUPPORTED_PROTOCOL_VERSION) return@forEach
            val endpoint = NormalizedEndpoint.parse(item.requiredString("url", MAX_URL_LENGTH))
            require(endpoint.scheme == "https" || endpoint.isLiteralLoopback()) {
                "A2A interface must use HTTPS"
            }
            require(
                endpoint.origin == expectedOrigin,
            ) { "A2A interface origin differs from configured Agent Card origin" }
            return A2aInterfaceSnapshot(
                endpoint = endpoint,
                binding = binding,
                protocolVersion = version,
                tenant = item.optionalString("tenant", MAX_TENANT_LENGTH),
            )
        }
        error("A2A Agent Card has no supported v1.0 JSONRPC or HTTP+JSON interface")
    }

    private fun parseSkill(
        value: JsonElement,
        defaultInputModes: List<String>,
        defaultOutputModes: List<String>,
    ): A2aSkillSnapshot {
        val skill = value as? JsonObject ?: error("A2A Skill must be an object")
        val canonical = skill.canonicalJson()
        return A2aSkillSnapshot(
            id = skill.requiredString("id", MAX_SKILL_ID_LENGTH),
            name = skill.requiredString("name", MAX_NAME_LENGTH),
            description = skill.requiredString("description", MAX_DESCRIPTION_LENGTH),
            tags = skill.requiredStringArray("tags", MAX_TAGS, MAX_TAG_LENGTH),
            inputModes = skill.optionalStringArray("inputModes", MAX_MODES, MAX_MODE_LENGTH) ?: defaultInputModes,
            outputModes = skill.optionalStringArray("outputModes", MAX_MODES, MAX_MODE_LENGTH) ?: defaultOutputModes,
            contentHash = Sha256(hash(canonical)),
        )
    }

    private fun rejectUnknownRequiredExtensions(card: JsonObject) {
        val capabilities = card["capabilities"] as? JsonObject ?: error("A2A capabilities must be an object")
        val extensions = capabilities["extensions"] as? JsonArray ?: return
        require(extensions.size <= MAX_EXTENSIONS) { "A2A Agent Card has too many extensions" }
        extensions.forEach { value ->
            val extension = value as? JsonObject ?: error("A2A extension must be an object")
            extension.optionalString("uri", MAX_URL_LENGTH)
            require(extension.optionalBoolean("required") != true) { "Required A2A extensions are not supported" }
        }
    }

    private fun JsonObject.requiredCapabilities(): A2aCapabilitySnapshot {
        val value = this["capabilities"] as? JsonObject ?: error("A2A capabilities must be an object")
        return A2aCapabilitySnapshot(
            streaming = value.optionalBoolean("streaming") ?: false,
            pushNotifications = value.optionalBoolean("pushNotifications") ?: false,
            extendedAgentCard = value.optionalBoolean("extendedAgentCard") ?: false,
        )
    }

    private fun JsonObject.optionalProvider(): A2aProviderSnapshot? {
        val element = this["provider"]
        return when (element) {
            null, JsonNull -> {
                null
            }

            else -> {
                val provider = element as? JsonObject ?: error("A2A provider must be an object")
                A2aProviderSnapshot(
                    organization = provider.requiredString("organization", MAX_NAME_LENGTH),
                    url = provider.requiredString("url", MAX_URL_LENGTH),
                )
            }
        }
    }

    private fun JsonObject.requiredArray(
        key: String,
        maxEntries: Int,
    ): JsonArray {
        val array = this[key] as? JsonArray ?: error("A2A $key must be an array")
        require(array.size <= maxEntries) { "A2A $key exceeds $maxEntries entries" }
        return array
    }

    private fun JsonObject.requiredString(
        key: String,
        maxLength: Int,
    ): String = optionalString(key, maxLength) ?: error("A2A $key must be a string")

    private fun JsonObject.optionalString(
        key: String,
        maxLength: Int,
    ): String? {
        val element = this[key]
        return when (element) {
            null, JsonNull -> {
                null
            }

            else -> {
                val value =
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?: error("A2A $key must be a string")
                require(value.isNotBlank() && value.length <= maxLength && value.none { it == '\u0000' }) {
                    "A2A $key must be 1..$maxLength non-blank characters without NUL"
                }
                value
            }
        }
    }

    private fun JsonObject.optionalBoolean(key: String): Boolean? {
        val element = this[key]
        return when (element) {
            null, JsonNull -> null
            else -> (element as? JsonPrimitive)?.booleanOrNull ?: error("A2A $key must be a boolean")
        }
    }

    private fun JsonObject.requiredStringArray(
        key: String,
        maxEntries: Int,
        maxLength: Int,
    ): List<String> = optionalStringArray(key, maxEntries, maxLength) ?: error("A2A $key must be an array")

    private fun JsonObject.optionalStringArray(
        key: String,
        maxEntries: Int,
        maxLength: Int,
    ): List<String>? {
        val element = this[key]
        return when (element) {
            null, JsonNull -> {
                null
            }

            else -> {
                val array = element as? JsonArray ?: error("A2A $key must be an array")
                require(array.size <= maxEntries) { "A2A $key exceeds $maxEntries entries" }
                val values =
                    array.mapIndexed { index, value ->
                        val text =
                            (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                                ?: error("A2A $key[$index] must be a string")
                        require(text.isNotBlank() && text.length <= maxLength && text.none { it == '\u0000' }) {
                            "A2A $key[$index] is invalid"
                        }
                        text
                    }
                require(values.toSet().size == values.size) { "A2A $key contains duplicates" }
                values
            }
        }
    }

    private fun JsonElement.canonicalJson(): String =
        when (this) {
            is JsonObject -> {
                keys.sorted().joinToString(",", "{", "}") { key ->
                    "${JsonPrimitive(key)}:${getValue(key).canonicalJson()}"
                }
            }

            is JsonArray -> {
                joinToString(",", "[", "]") { it.canonicalJson() }
            }

            is JsonPrimitive -> {
                toString()
            }
        }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private const val SUPPORTED_PROTOCOL_VERSION = "1.0"
    private const val MAX_CARD_BYTES = 512 * 1024
    private const val MAX_INTERFACES = 16
    private const val MAX_SKILLS = 256
    private const val MAX_EXTENSIONS = 32
    private const val MAX_MODES = 32
    private const val MAX_TAGS = 32
    private const val MAX_NAME_LENGTH = 256
    private const val MAX_DESCRIPTION_LENGTH = 16 * 1024
    private const val MAX_VERSION_LENGTH = 64
    private const val MAX_BINDING_LENGTH = 128
    private const val MAX_URL_LENGTH = 2_048
    private const val MAX_TENANT_LENGTH = 256
    private const val MAX_SKILL_ID_LENGTH = 256
    private const val MAX_MODE_LENGTH = 128
    private const val MAX_TAG_LENGTH = 128
}

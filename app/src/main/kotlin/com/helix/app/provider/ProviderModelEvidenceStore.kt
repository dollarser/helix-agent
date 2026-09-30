package com.helix.app.provider

import com.helix.app.internal.LineStore
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.ProviderCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Exact-model test result, not a source-wide assertion and not a selection preference. */
data class ProviderModelVerification(
    val atMillis: Long,
    val capabilities: ProviderCapabilities? = null,
    val failure: ModelErrorCode? = null,
) {
    init {
        require(atMillis >= 0)
        require(capabilities == null || failure == null)
    }
}

data class ProviderModelEvidence(
    val catalog: List<String>? = null,
    val verifications: Map<String, ProviderModelVerification> = emptyMap(),
    val generations: Map<String, ProviderModelVerification> = emptyMap(),
)

/** Last good directory and exact-model tests share a bounded, identity-scoped public-evidence document. */
class ProviderModelEvidenceStore(
    private val store: LineStore,
) {
    fun read(
        id: String,
        identity: String,
    ): ProviderModelEvidence =
        synchronized(LOCK) {
            val text = store.lines(key(id)).singleOrNull() ?: return@synchronized ProviderModelEvidence()
            runCatching {
                require(text.length <= MAX_CHARS)
                val root = Json.parseToJsonElement(text).jsonObject
                require(
                    root.keys == setOf("identity", "catalog", "tests") ||
                        root.keys == setOf("identity", "catalog", "tests", "generations"),
                )
                require(root.getValue("identity").jsonPrimitive.isString)
                if (root.getValue("identity").jsonPrimitive.content != identity) {
                    return@synchronized ProviderModelEvidence()
                }
                val catalog =
                    root.getValue("catalog").takeUnless { it == JsonNull }?.let { value ->
                        (value as JsonArray)
                            .map {
                                require(it.jsonPrimitive.isString)
                                it.jsonPrimitive.content
                            }.also(ProviderSelectedModels::validate)
                    }
                val tests = root.getValue("tests").jsonObject
                require(tests.size <= 1024)
                ProviderSelectedModels.validate(tests.keys.toList())
                val generations = root["generations"]?.jsonObject.orEmpty()
                require(generations.size <= 1024)
                ProviderSelectedModels.validate(generations.keys.toList())
                ProviderModelEvidence(
                    catalog,
                    tests.mapValues { (_, value) -> decodeTest(value.jsonObject) },
                    generations.mapValues { (_, value) -> decodeTest(value.jsonObject) },
                )
            }.getOrDefault(ProviderModelEvidence())
        }

    private fun decodeTest(test: JsonObject): ProviderModelVerification {
        require(test.keys == setOf("at", "caps", "error"))
        require(!test.getValue("at").jsonPrimitive.isString)
        val caps = test.getValue("caps").takeUnless { it == JsonNull }
        val error = test.getValue("error").takeUnless { it == JsonNull }
        require(error == null || error.jsonPrimitive.isString)
        return ProviderModelVerification(
            test.getValue("at").jsonPrimitive.long,
            caps?.let { ProviderCapabilities.parse(it.toString()) },
            error?.let { ModelErrorCode.valueOf(it.jsonPrimitive.content) },
        )
    }

    fun catalog(
        id: String,
        identity: String,
        models: List<String>,
    ) = synchronized(LOCK) {
        ProviderSelectedModels.validate(models)
        write(id, identity, read(id, identity).copy(catalog = models.distinct()))
    }

    fun verify(
        id: String,
        identity: String,
        model: String,
        result: ProviderModelVerification,
    ) = synchronized(LOCK) {
        ProviderSelectedModels.validate(listOf(model))
        val old = read(id, identity)
        write(id, identity, old.copy(verifications = old.verifications + (model to result)))
    }

    fun generation(
        id: String,
        identity: String,
        model: String,
        result: ProviderModelVerification,
    ) {
        synchronized(LOCK) {
            ProviderSelectedModels.validate(listOf(model))
            val old = read(id, identity)
            write(id, identity, old.copy(generations = old.generations + (model to result)))
        }
    }

    fun clear(id: String) = synchronized(LOCK) { store.setLines(key(id), emptyList()) }

    private fun write(
        id: String,
        identity: String,
        value: ProviderModelEvidence,
    ) {
        require(value.verifications.size <= 1024)
        require(value.generations.size <= 1024)
        val document =
            buildJsonObject {
                put("identity", identity)
                put("catalog", value.catalog?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
                put("tests", JsonObject(value.verifications.mapValues { (_, test) -> encodeTest(test) }))
                put("generations", JsonObject(value.generations.mapValues { (_, test) -> encodeTest(test) }))
            }.toString()
        require(document.length <= MAX_CHARS)
        store.setLines(key(id), listOf(document))
    }

    private fun encodeTest(test: ProviderModelVerification): JsonObject {
        val caps = test.capabilities?.let(ProviderCapabilities::toJsonString)
        return buildJsonObject {
            put("at", test.atMillis)
            put("caps", caps?.let(Json::parseToJsonElement) ?: JsonNull)
            put("error", test.failure?.name?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private fun key(id: String) = "provider-model-evidence-v1-$id"

    private companion object {
        val LOCK = Any()
        const val MAX_CHARS = 1024 * 1024
    }
}

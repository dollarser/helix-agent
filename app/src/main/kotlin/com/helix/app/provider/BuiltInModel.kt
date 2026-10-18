package com.helix.app.provider

import com.helix.app.R
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderAuth
import com.helix.core.model.ProviderConnection
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderTransport
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Public deployment identity. No backend credential is embedded, looked up or synthesized. */
object BuiltInModel {
    const val PROVIDER_ID = "builtin-helix-qwen"
    const val MODEL_ID = "Qwen3.8-27B"
    const val CLIENT_ID = "helix-android-preview-v1"
    const val API_ROOT = "https://api.sunlingzhang.com/v1"
    const val GENERATION_URL = "$API_ROOT/chat/completions"
    const val CATALOG_URL = "$API_ROOT/models"

    val capabilities =
        ProviderCapabilities(
            streaming = true,
            toolCalls = true,
            parallelToolCalls = false,
            vision = true,
            reasoning = true,
            jsonSchemaOutput = false,
            maxContextTokens = null,
            source = CapabilitySource.BUNDLED_CONTRACT,
        )

    /** Only the named bundled adapter contract is known; directory membership adds no capabilities. */
    fun capabilitiesFor(model: String): ProviderCapabilities =
        if (model ==
            MODEL_ID
        ) {
            capabilities
        } else {
            capabilities.copy(streaming = false, toolCalls = false, vision = false, reasoning = false)
        }

    /**
     * Client adaptation for upstream Qwen/Qwen3.8-27B template
     * 1d4bf0f2ff6012fd82039f2fa52739d0dd7c60c0 (low/medium/xhigh).
     * The deployment template is unconfirmed; this is not detected gateway or directory evidence.
     */
    fun reasoningOptionsFor(model: String): List<com.helix.core.model.ReasoningEffort> =
        if (model == MODEL_ID) com.helix.core.model.ReasoningEffort.FALLBACK else emptyList()

    val connection =
        ProviderConnection(
            ProviderProvisioningKind.BUNDLED_SERVICE,
            ProviderTransport.Network(ProviderProtocol.OPENAI_CHAT_COMPLETIONS, NormalizedEndpoint.parse(API_ROOT)),
            ProviderAuth.None,
        )

    fun owns(providerId: String): Boolean = providerId == PROVIDER_ID

    fun validate(config: ProviderConfig) {
        require(
            owns(config.id) && config.connection == connection && config.headers.isEmpty(),
        ) {
            "Bundled service configuration changed"
        }
    }

    /** No raw server body, inferred quota cause, personal remaining quota or invented reset time. */
    fun errorLabel(code: String?): Int? =
        when (code) {
            "AUTH" -> R.string.builtin_model_auth_error
            "RATE_LIMITED" -> R.string.builtin_model_rate_limit
            "TRANSPORT", "SERVER_ERROR", "HTTP_ERROR" -> R.string.builtin_model_service_error
            "TIMEOUT" -> R.string.builtin_model_timeout
            else -> null
        }

    /** Use the original model call, never the conversation's later selection. */
    fun errorLabelForSnapshot(
        code: String?,
        snapshot: String?,
    ): Int? {
        val provider =
            snapshot?.let {
                runCatching {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["providerId"]
                        ?.jsonPrimitive
                        ?.content
                }.getOrNull()
            }
        return errorLabel(code).takeIf { provider == PROVIDER_ID }
    }

    internal fun errorLabelForTurn(
        storage: com.helix.core.storage.HelixStorage,
        turnId: String,
        code: String,
    ): Int? =
        errorLabelForSnapshot(
            code,
            storage.modelCalls
                .listByTurn(turnId)
                .lastOrNull()
                ?.providerSnapshot,
        )
}

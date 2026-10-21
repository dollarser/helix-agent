package com.helix.app.provider

import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.core.storage.entity.transportIdentity
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities

/** No tested capability is claimed by a new or invalidated source; status remains independently untested. */
internal val UNTESTED_PROVIDER_SNAPSHOT: String =
    ProviderCapabilities.toJsonString(
        ProviderCapabilities(
            streaming = false,
            toolCalls = false,
            parallelToolCalls = false,
            vision = false,
            reasoning = false,
            jsonSchemaOutput = false,
            maxContextTokens = null,
            source = CapabilitySource.MANUAL,
        ),
    )

/** Called only inside ProviderService's authenticated mutation gate. */
internal fun storeVisionDeclaration(
    storage: HelixStorage,
    row: ProviderConfigEntity,
    enabled: Boolean,
    testStatus: ProviderTestStatusStore,
    atMillis: Long,
) {
    val current =
        runCatching { ProviderCapabilities.parse(row.capabilitySnapshot) }.getOrNull()
            ?: ProviderCapabilities.parse(UNTESTED_PROVIDER_SNAPSHOT)
    val declared = current.copy(vision = enabled).withManualSource()
    storage.providerConfigs.overwrite(
        ProviderConfigSpec(
            id = row.id,
            displayName = row.displayName,
            protocol = row.protocol?.let(ProviderProtocol::parse),
            endpoint = row.endpoint,
            model = row.model,
            headersJson = row.headersJson,
            secretAlias = row.secretAlias,
            provisioningKind = row.provisioningKind,
            transportKind = row.transportKind,
            authKind = row.authKind,
            capabilitySnapshot = ProviderCapabilities.toJsonString(declared),
        ),
    )
    testStatus.modelEvidence.verify(
        row.id,
        row.transportIdentity,
        row.model,
        ProviderModelVerification(atMillis, declared),
    )
}

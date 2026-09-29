package com.helix.app.ui

import com.helix.app.R
import com.helix.app.provider.ComposeOutcome
import com.helix.app.provider.ProviderComposer
import com.helix.app.provider.ProviderService
import com.helix.core.model.ProviderProvisioningKind
import com.helix.provider.api.ModelCatalogResult
import kotlinx.coroutines.CancellationException

internal fun providerGroupLabel(group: ProviderProvisioningKind): Int =
    when (group) {
        ProviderProvisioningKind.ON_DEVICE_ASSET -> R.string.local_model_title
        ProviderProvisioningKind.USER_CONFIGURED -> R.string.provider_group_api
        ProviderProvisioningKind.MANAGED_ACCOUNT -> R.string.provider_group_account
    }

internal data class ProviderFormDiscovery(
    val models: List<String> = emptyList(),
    val message: Int? = null,
    val running: Boolean = false,
)

internal fun ProviderForm.catalogIdentity() =
    listOf(fields.endpoint, fields.apiKey, fields.headerName, fields.headerValue)

@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal suspend fun discoverProviderForm(
    form: ProviderForm,
    service: ProviderService,
): ProviderFormDiscovery {
    return try {
        val headers =
            form.preservedHeaders +
                if (form.fields.headerName.isBlank()) {
                    emptyMap()
                } else {
                    mapOf(form.fields.headerName.trim() to form.fields.headerValue.trim())
                }
        val outcome =
            ProviderComposer.compose(
                form.template.copy(credentialRequired = false, defaultHeaders = emptyMap()),
                form.fields.name.trim(),
                form.fields.endpoint.trim(),
                "catalog-discovery",
                headers,
            )
        if (outcome is ComposeOutcome.Rejected) {
            return ProviderFormDiscovery(
                emptyList(),
                outcome.reasonRes.takeIf { outcome.reasonArgs.isEmpty() } ?: R.string.provider_discovery_failed,
            )
        }
        val result =
            service.discoverModels(
                (outcome as ComposeOutcome.Ok).draft,
                form.fields.apiKey.trim().takeIf {
                    it.isNotEmpty()
                },
                form.providerId,
                form.cleartextConfirmed,
            )
        when (result) {
            is ModelCatalogResult.Listed -> ProviderFormDiscovery(result.models, R.string.provider_discovery_hint)
            else -> ProviderFormDiscovery(emptyList(), R.string.provider_discovery_failed)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        // Credentials and server error bodies must never be rendered in the form.
        ProviderFormDiscovery(emptyList(), R.string.provider_discovery_failed)
    }
}

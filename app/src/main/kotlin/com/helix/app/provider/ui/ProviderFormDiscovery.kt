package com.helix.app.provider.ui

import com.helix.app.R
import com.helix.app.provider.ComposeOutcome
import com.helix.app.provider.ProviderComposer
import com.helix.app.provider.ProviderService
import com.helix.provider.api.ModelCatalogResult
import com.helix.provider.api.ModelPurpose
import kotlinx.coroutines.CancellationException

internal data class ProviderFormDiscovery(
    val models: List<String> = emptyList(),
    val message: Int? = null,
    val running: Boolean = false,
)

internal fun ProviderForm.catalogIdentity() =
    listOf(protocol.name, fields.endpoint, fields.apiKey, fields.headerName, fields.headerValue)

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
                form.template.copy(protocol = form.protocol, credentialRequired = false, defaultHeaders = emptyMap()),
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
            )
        when (result) {
            is ModelCatalogResult.Listed -> {
                ProviderFormDiscovery(
                    result.models.filter { id ->
                        result.purposes[id].orEmpty().let { it.isEmpty() || ModelPurpose.CHAT in it }
                    },
                    R.string.provider_discovery_hint,
                )
            }

            ModelCatalogResult.Unsupported -> {
                ProviderFormDiscovery(message = R.string.provider_directory_unsupported)
            }

            is ModelCatalogResult.Failed -> {
                ProviderFormDiscovery(emptyList(), R.string.provider_discovery_failed)
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        // Credentials and server error bodies must never be rendered in the form.
        ProviderFormDiscovery(emptyList(), R.string.provider_discovery_failed)
    }
}

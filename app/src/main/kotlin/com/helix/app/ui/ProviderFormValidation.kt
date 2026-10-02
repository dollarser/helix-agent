@file:Suppress("MatchingDeclarationName") // Field mapping and form-only validation belong together.

package com.helix.app.ui

import com.helix.app.R

internal enum class ProviderFormField { NAME, ENDPOINT, MODEL, HEADER_NAME, HEADER_VALUE }

/** Form-only omissions. Protocol/address/header validation remains in the production composer. */
internal fun validateProviderForm(form: ProviderForm): SaveResult.Rejected? =
    when {
        form.fields.name.isBlank() -> {
            SaveResult.Rejected(R.string.provider_name_required)
        }

        form.fields.endpoint.isBlank() -> {
            SaveResult.Rejected(R.string.provider_endpoint_required)
        }

        form.providerId == null && form.fields.model.isBlank() && form.selectedModels.isEmpty() -> {
            SaveResult.Rejected(R.string.provider_model_required)
        }

        form.fields.headerName.isBlank() && form.fields.headerValue.isNotBlank() -> {
            SaveResult.Rejected(R.string.provider_header_name_required)
        }

        form.fields.headerName.isNotBlank() && form.fields.headerValue.isBlank() -> {
            SaveResult.Rejected(R.string.provider_header_value_required)
        }

        else -> {
            validateProviderModels(form)
        }
    }

@Suppress("SwallowedException") // A model-selection violation is a specific correctable form error.
private fun validateProviderModels(form: ProviderForm): SaveResult.Rejected? {
    if (form.providerId != null) return null
    val entered = form.fields.model.trim()
    return try {
        com.helix.app.provider.ProviderSelectedModels.validate(
            if (form.selectedModels.isEmpty()) listOf(entered) else form.selectedModels.toList(),
        )
        if (entered.isNotEmpty() && form.selectedModels.isNotEmpty() && entered !in form.selectedModels) {
            SaveResult.Rejected(R.string.provider_model_selection_mismatch)
        } else {
            null
        }
    } catch (_: IllegalArgumentException) {
        SaveResult.Rejected(R.string.provider_model_selection_invalid)
    }
}

internal fun providerErrorField(error: SaveResult.Rejected?): ProviderFormField? =
    when (error?.res) {
        R.string.provider_name_required, R.string.provider_compose_name_invalid -> ProviderFormField.NAME

        R.string.provider_endpoint_required, R.string.provider_compose_endpoint_invalid -> ProviderFormField.ENDPOINT

        R.string.provider_model_required, R.string.provider_compose_model_invalid,
        R.string.provider_model_selection_invalid, R.string.provider_model_selection_mismatch,
        -> ProviderFormField.MODEL

        R.string.provider_header_name_required, R.string.provider_compose_header_conflict,
        R.string.provider_compose_header_disallowed,
        -> ProviderFormField.HEADER_NAME

        R.string.provider_header_value_required -> ProviderFormField.HEADER_VALUE

        else -> null
    }

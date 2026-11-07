package com.helix.app.ui

import com.helix.app.R
import com.helix.core.model.ProviderProvisioningKind

internal fun modelSourceDescription(source: ProviderProvisioningKind): Int =
    when (source) {
        ProviderProvisioningKind.BUNDLED_SERVICE -> R.string.builtin_model_description
        ProviderProvisioningKind.USER_CONFIGURED -> R.string.model_source_api_description
        ProviderProvisioningKind.MANAGED_ACCOUNT -> R.string.model_source_account_description
        ProviderProvisioningKind.ON_DEVICE_ASSET -> R.string.model_source_local_description
    }

internal fun modelSourceEmpty(source: ProviderProvisioningKind): Int =
    when (source) {
        ProviderProvisioningKind.BUNDLED_SERVICE -> R.string.builtin_model_unverified
        ProviderProvisioningKind.USER_CONFIGURED -> R.string.provider_empty
        ProviderProvisioningKind.MANAGED_ACCOUNT -> R.string.model_source_account_empty
        ProviderProvisioningKind.ON_DEVICE_ASSET -> R.string.model_source_local_empty
    }

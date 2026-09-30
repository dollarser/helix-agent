package com.helix.app.provider

import com.helix.core.model.ProviderProvisioningKind

/** Billing does not select a channel. Only managed account authentication is Advanced-only. */
internal object ProviderChannelPolicy {
    fun groups(managedEnabled: Boolean): List<ProviderProvisioningKind> =
        listOf(ProviderProvisioningKind.ON_DEVICE_ASSET, ProviderProvisioningKind.USER_CONFIGURED) +
            if (managedEnabled) listOf(ProviderProvisioningKind.MANAGED_ACCOUNT) else emptyList()

    fun permits(
        kind: String,
        managedEnabled: Boolean,
    ): Boolean = kind != ProviderProvisioningKind.MANAGED_ACCOUNT.name || managedEnabled

    fun rank(
        id: String,
        order: List<String>,
    ): Int = order.indexOf(id).takeIf { it >= 0 } ?: Int.MAX_VALUE
}

package com.helix.app.provider

import com.helix.core.model.ProviderProvisioningKind
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderChannelPolicyTest {
    @Test fun standardKeepsApiAndLocalButNoManagedAccountGroup() {
        assertEquals(
            listOf(ProviderProvisioningKind.ON_DEVICE_ASSET, ProviderProvisioningKind.USER_CONFIGURED),
            ProviderChannelPolicy.groups(false),
        )
        assertFalse(ProviderChannelPolicy.permits("MANAGED_ACCOUNT", false))
        assertTrue(ProviderChannelPolicy.permits("USER_CONFIGURED", false))
        assertTrue(ProviderChannelPolicy.groups(true).contains(ProviderProvisioningKind.MANAGED_ACCOUNT))
    }

    @Test fun prescribedOrderDoesNotDependOnDatabaseOrder() {
        val order =
            listOf(
                "subscription-codex",
                "subscription-claude",
                "subscription-antigravity",
                "subscription-copilot",
                "subscription-grok",
            )
        assertEquals(order, order.reversed().sortedBy { ProviderChannelPolicy.rank(it, order) })
    }

    @Test fun missingManagedAdapterCannotFallThroughToNormalApi() {
        val config =
            ProviderConfig.fromStorage(
                "subscription-unavailable",
                "Unavailable",
                "OPENAI_RESPONSES",
                "https://example.test",
                "model",
                "{}",
                null,
                com.helix.provider.api.ProviderCapabilities.toJsonString(
                    com.helix.provider.api.ProviderCapabilities(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null,
                        com.helix.provider.api.CapabilitySource.MANUAL,
                    ),
                ),
                provisioningKind = "MANAGED_ACCOUNT",
                authKind = "MANAGED_ACCOUNT",
            )
        val wire =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse = error("No network allowed")
            }
        val factory = ProviderFactory(CredentialLookup { error("No credential access") }, wire, { error("No images") })
        assertThrows(IllegalArgumentException::class.java) { factory.create(config) }
    }
}

package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderConnectionTest {
    private val network =
        ProviderTransport.Network(
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            NormalizedEndpoint.parse("http://127.0.0.1:11434/v1"),
        )

    @Test
    @Suppress("NestedBlockDepth") // Exhaustive product of three finite configuration dimensions.
    fun combinationsAreClosed() {
        val transports = listOf(network, ProviderTransport.OnDeviceLocal)
        val auths = listOf(ProviderAuth.None, ProviderAuth.Secret(SecretAlias("key")), ProviderAuth.ManagedAccount)
        var accepted = 0
        for (provisioning in ProviderProvisioningKind.entries) {
            for (transport in transports) {
                for (auth in auths) {
                    val allowed =
                        when (provisioning) {
                            ProviderProvisioningKind.USER_CONFIGURED -> {
                                transport == network &&
                                    auth != ProviderAuth.ManagedAccount
                            }

                            ProviderProvisioningKind.MANAGED_ACCOUNT -> {
                                transport == network &&
                                    auth == ProviderAuth.ManagedAccount
                            }

                            ProviderProvisioningKind.ON_DEVICE_ASSET -> {
                                transport == ProviderTransport.OnDeviceLocal &&
                                    auth == ProviderAuth.None
                            }
                        }
                    if (allowed) {
                        ProviderConnection(provisioning, transport, auth)
                        accepted++
                    } else {
                        assertThrows(
                            IllegalArgumentException::class.java,
                        ) { ProviderConnection(provisioning, transport, auth) }
                    }
                }
            }
        }
        assertEquals(4, accepted)
    }

    @Test
    fun residenceAndCacheIdentityFollowTransport() {
        assertEquals(ProviderResidence.ON_DEVICE_LOCAL, ProviderTransport.OnDeviceLocal.residence)
        assertEquals(ProviderResidence.ON_DEVICE_LOOPBACK, network.residence)
        assertEquals(
            ProviderResidence.USER_AUTHORIZED_LAN,
            network.copy(endpoint = NormalizedEndpoint.parse("http://192.168.1.2:11434/v1")).residence,
        )
        assertEquals(
            ProviderResidence.PUBLIC_CLOUD,
            network.copy(endpoint = NormalizedEndpoint.parse("https://example.com/v1")).residence,
        )
        assertNotEquals(network.cacheKey, ProviderTransport.OnDeviceLocal.cacheKey)
        assertNotEquals(network.cacheKey, network.copy(protocol = ProviderProtocol.OPENAI_RESPONSES).cacheKey)
    }
}

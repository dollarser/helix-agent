package com.helix.extensions.mcp

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.NetworkOriginScope
import com.helix.core.policy.SsrfDenialCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpNetworkPolicyTest {
    @Test
    fun publicOriginRejectsTheWholeSetWhenOneCandidateIsPrivate() {
        val gate =
            gate(
                profile = SafetyProfile.STANDARD,
                addresses = listOf(v4(93, 184, 216, 34), v4(127, 0, 0, 1)),
            )

        val error =
            assertThrows(McpEndpointDeniedException::class.java) {
                runBlocking { gate.authorize(NormalizedEndpoint.parse("https://example.com/mcp")) }
            }
        assertEquals(SsrfDenialCode.NON_PUBLIC_ADDRESS, error.code)
    }

    @Test
    fun advancedExactLanScopeProducesPinnedAddressCopies() =
        runBlocking {
            val resolved = v4(192, 168, 1, 20)
            val endpoint = NormalizedEndpoint.parse("http://192.168.1.20:8080/mcp")
            val gate =
                gate(
                    profile = SafetyProfile.ADVANCED,
                    scopes = setOf(NetworkOriginScope("192.168.1.20", 8080)),
                    addresses = listOf(resolved),
                )

            val permit = gate.authorize(endpoint)
            resolved[0] = 1

            assertEquals("192.168.1.20", permit.host)
            assertArrayEquals(v4(192, 168, 1, 20), permit.pinnedAddresses(endpoint.host).single().address)
        }

    @Test
    fun lanWithoutExactScopeAndEmptyDnsFailClosed() {
        val endpoint = NormalizedEndpoint.parse("http://192.168.1.20:8080/mcp")
        val missingScope = gate(SafetyProfile.ADVANCED, addresses = listOf(v4(192, 168, 1, 20)))
        val emptyDns = gate(SafetyProfile.ADVANCED, addresses = emptyList())

        val scopeError =
            assertThrows(McpEndpointDeniedException::class.java) {
                runBlocking { missingScope.authorize(endpoint) }
            }
        val dnsError =
            assertThrows(McpEndpointDeniedException::class.java) {
                runBlocking { emptyDns.authorize(endpoint) }
            }
        assertEquals(SsrfDenialCode.SCOPE_VIOLATION, scopeError.code)
        assertEquals(SsrfDenialCode.NO_ADDRESSES, dnsError.code)
    }

    private fun gate(
        profile: SafetyProfile,
        scopes: Set<NetworkOriginScope> = emptySet(),
        addresses: List<ByteArray>,
    ): McpSsrfEndpointGate =
        McpSsrfEndpointGate(
            profileProvider = { profile },
            lanScopesProvider = { scopes },
            resolver = McpHostResolver { addresses },
        )

    private fun v4(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ): ByteArray = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
}

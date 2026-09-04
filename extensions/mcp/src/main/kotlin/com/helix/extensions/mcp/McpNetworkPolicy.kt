package com.helix.extensions.mcp

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.NetworkOriginScope
import com.helix.core.policy.SsrfAddressPolicy
import com.helix.core.policy.SsrfCheckResult
import com.helix.core.policy.SsrfDenialCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

class McpNetworkPermit internal constructor(
    internal val host: String,
    addresses: List<ByteArray>,
) {
    private val addressCopies = addresses.map(ByteArray::copyOf)

    init {
        require(addressCopies.isNotEmpty()) { "MCP network permit needs at least one address" }
        require(addressCopies.all { it.size == 4 || it.size == 16 }) {
            "MCP network permit contains a malformed address"
        }
    }

    internal fun pinnedAddresses(host: String): List<InetAddress> =
        addressCopies.map { address -> InetAddress.getByAddress(host, address.copyOf()) }
}

fun interface McpHostResolver {
    suspend fun resolve(host: String): List<ByteArray>
}

class McpSsrfEndpointGate(
    private val profileProvider: () -> SafetyProfile,
    private val lanScopesProvider: () -> Set<NetworkOriginScope>,
    private val resolver: McpHostResolver = SYSTEM_MCP_HOST_RESOLVER,
) : McpEndpointGate {
    override suspend fun authorize(endpoint: NormalizedEndpoint): McpNetworkPermit {
        val profile = profileProvider()
        val scopes = lanScopesProvider()
        val addresses = resolver.resolve(endpoint.host).map(ByteArray::copyOf)
        return when (val result = SsrfAddressPolicy.check(addresses, profile, scopes, endpoint)) {
            is SsrfCheckResult.Allowed -> McpNetworkPermit(endpoint.host, result.connectable)
            is SsrfCheckResult.Denied -> throw McpEndpointDeniedException(result.code)
        }
    }
}

class McpEndpointDeniedException(
    val code: SsrfDenialCode,
) : IllegalArgumentException("MCP endpoint denied: $code")

private val SYSTEM_MCP_HOST_RESOLVER =
    McpHostResolver { host ->
        withContext(Dispatchers.IO) {
            InetAddress.getAllByName(host).map { address -> address.address.copyOf() }
        }
    }

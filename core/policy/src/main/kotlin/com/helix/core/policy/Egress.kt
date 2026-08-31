package com.helix.core.policy

import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId

/**
 * The stable identity of where egress data goes — a Provider or MCP server by ID. Template
 * names, product names and display names never gate anything (ADR-0005: the same Ollama/SGLang
 * template can point at loopback, LAN or the public cloud).
 */
sealed interface EgressTarget {
    /** A configured model provider (ProviderConfig.id). */
    data class Provider(
        val id: ProviderId,
    ) : EgressTarget

    /** A configured MCP server (McpServer.id). */
    data class Mcp(
        val id: McpServerId,
    ) : EgressTarget
}

/**
 * The egress facet of one tool call: data actually leaving the device for [target] through
 * [endpoint], carrying [dataSensitivity].
 *
 * Residence is deliberately absent as a field: the engine derives it from [endpoint] alone
 * (NormalizedEndpoint.residence), so no caller — model, MCP or imported content — can declare a
 * lower residence (ADR-0005; architecture doc 6.2).
 */
data class EgressRequest(
    val target: EgressTarget,
    val endpoint: NormalizedEndpoint,
    val dataSensitivity: DataSensitivity,
)

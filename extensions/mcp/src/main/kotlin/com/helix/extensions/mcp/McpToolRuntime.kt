package com.helix.extensions.mcp

import com.helix.tools.framework.ExecutableToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Duration

class McpToolRuntime(
    private val credentials: McpCredentialLookup,
    private val endpointGate: McpEndpointGate,
    private val clientName: String,
    private val clientVersion: String,
) {
    init {
        require(clientName.isNotBlank()) { "clientName must not be blank" }
        require(clientVersion.isNotBlank()) { "clientVersion must not be blank" }
    }

    fun caller(
        config: McpServerConfig,
        limits: McpToolResultLimits = McpToolResultLimits(),
        onSend: (ExecutableToolCall) -> Unit = {},
    ): McpToolCaller {
        require(config.enabled) { "MCP runtime requires an enabled server" }
        return McpToolCaller { call, serverToolName ->
            ensureNotCancelled(call.cancel.isCancelled(), "network start")
            val remainingMillis = Duration.between(java.time.Instant.now(), call.deadline).toMillis()
            require(remainingMillis > 0) { "MCP tool call deadline elapsed before network start" }
            runBlocking {
                withTimeout(remainingMillis) {
                    val permit = endpointGate.authorize(config.endpoint)
                    ensureNotCancelled(call.cancel.isCancelled(), "credential lookup")
                    val bearer =
                        config.bearerSecretAlias?.let { alias ->
                            credentials.lookup(alias).also(::requireBearerCredential)
                        }
                    val session =
                        SdkMcpClientFacade(
                            clientName = clientName,
                            clientVersion = clientVersion,
                            authenticatedEndpoint = config.endpoint.takeIf { bearer != null },
                            bearerToken = bearer,
                            networkPermit = permit,
                        ).connect(config.endpoint.full)
                    try {
                        ensureNotCancelled(call.cancel.isCancelled(), "send")
                        onSend(call)
                        session.callTool(serverToolName, call.args, limits)
                    } finally {
                        withContext(NonCancellable) { session.close() }
                    }
                }
            }
        }
    }
}

private fun ensureNotCancelled(
    cancelled: Boolean,
    stage: String,
) {
    if (cancelled) throw CancellationException("cancelled before $stage")
}

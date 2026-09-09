package com.helix.extensions.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.RequestId

/**
 * Captures the initialize result before the SDK's protocol callback consumes it.
 *
 * Kotlin SDK 0.15.0 exposes [StreamableHttpClientTransport.protocolVersion] for request headers
 * but does not populate it after negotiation. Keep that compatibility fix inside the module.
 */
internal class NegotiatedProtocolTransport(
    private val delegate: StreamableHttpClientTransport,
) : Transport {
    private var initializeRequestId: RequestId? = null

    var negotiatedProtocolVersion: String? = null
        private set

    override suspend fun start() = delegate.start()

    override suspend fun send(
        message: JSONRPCMessage,
        options: TransportSendOptions?,
    ) {
        if (message is JSONRPCRequest && message.method == INITIALIZE_METHOD) {
            initializeRequestId = message.id
        }
        delegate.send(message, options)
    }

    override suspend fun close() = delegate.close()

    override fun onClose(block: () -> Unit) = delegate.onClose(block)

    override fun onError(block: (Throwable) -> Unit) = delegate.onError(block)

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        delegate.onMessage { message ->
            if (message is JSONRPCResponse && message.id == initializeRequestId) {
                val initializeResult = message.result as? InitializeResult
                if (initializeResult != null) {
                    negotiatedProtocolVersion = initializeResult.protocolVersion
                    delegate.protocolVersion = initializeResult.protocolVersion
                }
            }
            block(message)
        }
    }

    private companion object {
        const val INITIALIZE_METHOD = "initialize"
    }
}

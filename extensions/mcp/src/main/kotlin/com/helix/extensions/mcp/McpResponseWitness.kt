package com.helix.extensions.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.IdentityHashMap

/** A correlated wire error response, not a diagnosis inferred from an SDK exception or error code. */
internal class McpProtocolResponseException(
    val code: Int,
    cause: Exception,
) : IOException("MCP server returned a JSON-RPC error response", cause)

/**
 * Associate each SDK call by its unique RequestOptions object, then its actual wire request ID.
 * Kotlin SDK 0.15.0 forwards that options instance to Transport.send without copying it.
 * No bodies/messages are retained. Late, unsolicited and duplicate responses cannot become evidence.
 */
internal class McpResponseWitness {
    private class Exchange(
        val method: String,
    ) {
        var id: RequestId? = null
        var replied = false
        var errorCode: Int? = null
    }

    private val lock = Any()
    private val pending = IdentityHashMap<TransportSendOptions, Exchange>()
    private val requests = mutableMapOf<RequestId, Exchange>()

    fun sent(
        message: JSONRPCMessage,
        options: TransportSendOptions?,
    ) = synchronized(lock) {
        val exchange = pending[options] ?: return@synchronized
        if (message !is JSONRPCRequest) return@synchronized
        if (message.method == exchange.method && exchange.id == null) {
            check(message.id !in requests) { "Duplicate active MCP request ID" }
            exchange.id = message.id
            requests[message.id] = exchange
        }
    }

    fun received(message: JSONRPCMessage) =
        synchronized(lock) {
            val id =
                when (message) {
                    is JSONRPCResponse -> message.id
                    is JSONRPCError -> message.id
                    else -> return@synchronized
                }
            val exchange = requests[id] ?: return@synchronized
            if (exchange.replied) return@synchronized
            exchange.replied = true
            if (message is JSONRPCError) exchange.errorCode = message.error.code
        }

    // Preserve cancellation identities separately from correlated wire failures.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    suspend fun <T> observe(
        method: String,
        operation: suspend (RequestOptions) -> T,
    ): T {
        val exchange = Exchange(method)
        val options = RequestOptions()
        synchronized(lock) { pending[options] = exchange }
        try {
            return operation(options)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: InterruptedException) {
            throw failure
        } catch (failure: Exception) {
            val code = synchronized(lock) { exchange.errorCode }
            if (code != null) throw McpProtocolResponseException(code, failure)
            throw failure
        } finally {
            synchronized(lock) {
                pending.remove(options)
                exchange.id?.let { requests.remove(it) }
            }
        }
    }
}

internal class ResponseWitnessTransport(
    private val delegate: Transport,
    private val witness: McpResponseWitness,
) : Transport {
    override suspend fun start() = delegate.start()

    override suspend fun send(
        message: JSONRPCMessage,
        options: TransportSendOptions?,
    ) {
        witness.sent(message, options)
        delegate.send(message, options)
    }

    override suspend fun close() = delegate.close()

    override fun onClose(block: () -> Unit) = delegate.onClose(block)

    override fun onError(block: (Throwable) -> Unit) = delegate.onError(block)

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) =
        delegate.onMessage { message ->
            witness.received(message)
            block(message)
        }
}

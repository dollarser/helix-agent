package com.helix.extensions.mcp

import com.helix.core.model.TechnicalRecovery
import com.helix.core.policy.network.isTransientNetworkFailure
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Duration

class McpToolRuntime(
    private val credentials: McpCredentialLookup,
    private val endpointGate: McpEndpointGate,
    private val clientName: String,
    private val clientVersion: String,
    private val prepareCredential: suspend (McpServerConfig) -> Unit = {},
    private val clientFactory: (
        McpServerConfig,
        String?,
        McpNetworkPermit,
    ) -> McpClientFacade = { config, bearer, permit ->
        SdkMcpClientFacade(
            clientName,
            clientVersion,
            config.endpoint.takeIf { bearer != null },
            bearer,
            permit,
        )
    },
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(clientName.isNotBlank())
        require(clientVersion.isNotBlank())
    }

    @Suppress("TooGenericExceptionCaught") // Preserve cancellation types and attach host-known delivery facts.
    fun caller(
        config: McpServerConfig,
        limits: McpToolResultLimits = McpToolResultLimits(),
        onSend: (ExecutableToolCall) -> Unit = {},
    ): McpToolCaller {
        require(config.enabled)
        return McpToolCaller { call, serverToolName ->
            var sent = false
            var attempts = 0
            try {
                ensureNotCancelled(call.cancel.isCancelled(), "network start")
                val remainingMillis = Duration.between(java.time.Instant.now(), call.deadline).toMillis()
                require(remainingMillis > 0)
                runBlocking {
                    withTimeout(remainingMillis) {
                        val initialPermit = endpointGate.authorize(config.endpoint)
                        prepareCredential(config)
                        ensureNotCancelled(call.cancel.isCancelled(), "credential refresh")
                        val bearer =
                            config.bearerSecretAlias?.let {
                                credentials
                                    .lookup(
                                        it,
                                    ).also(::requireBearerCredential)
                            }
                        val session = connect(config, call, bearer, initialPermit) { attempts++ }
                        val result =
                            try {
                                ensureNotCancelled(call.cancel.isCancelled(), "send")
                                onSend(call)
                                sent = true
                                session.callTool(serverToolName, call.args, limits)
                            } catch (failure: Throwable) {
                                closeSession(session)?.let(failure::addSuppressed)
                                throw failure
                            }
                        val closed = closeSession(session) == null
                        result.copy(connectionAttempts = attempts, connectionClosed = closed)
                    }
                }
            } catch (failure: Exception) {
                throw trackedFailure(sent, attempts, failure)
            }
        }
    }

    private fun trackedFailure(
        sent: Boolean,
        attempts: Int,
        failure: Exception,
    ): Exception =
        when (failure) {
            is TimeoutCancellationException -> McpCallCancellation(sent, attempts, true, failure)
            is CancellationException -> McpCallCancellation(sent, attempts, false, failure)
            is InterruptedException -> McpCallInterruption(sent, attempts, failure)
            else -> McpCallException(sent, attempts, failure)
        }

    /** Only bootstrap may repeat. No tools/call has been submitted, and each retry rechecks live network scope. */
    @Suppress("TooGenericExceptionCaught") // Retry only classified network failures before business submission.
    private suspend fun connect(
        config: McpServerConfig,
        call: ExecutableToolCall,
        bearer: String?,
        initialPermit: McpNetworkPermit,
        attempted: () -> Unit,
    ): McpClientSession {
        var recoveryStarted: Long? = null
        var retries = 0
        while (true) {
            ensureNotCancelled(call.cancel.isCancelled(), "connect")
            val permit = if (retries == 0) initialPermit else endpointGate.authorize(config.endpoint)
            config.bearerSecretAlias?.let { check(credentials.lookup(it) == bearer) { "MCP credential changed" } }
            attempted()
            try {
                return clientFactory(config, bearer, permit).connect(config.endpoint.full)
            } catch (failure: Exception) {
                if (!isTransientNetworkFailure(failure)) throw failure
                val started = recoveryStarted ?: System.nanoTime().also { recoveryStarted = it }
                val elapsed = (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000
                val remaining = Duration.between(java.time.Instant.now(), call.deadline).toMillis()
                val pause = TechnicalRecovery.delayMillis(retries, elapsed, remaining) ?: throw failure
                retries++
                retryDelay(pause)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Keep received results and original cancellation when cleanup fails.
    private suspend fun closeSession(session: McpClientSession): Exception? =
        withContext(NonCancellable) {
            try {
                withTimeout(1_000) { session.close() }
                null
            } catch (failure: Exception) {
                failure
            }
        }
}

private fun ensureNotCancelled(
    cancelled: Boolean,
    stage: String,
) {
    if (cancelled) throw CancellationException("cancelled before $stage")
}

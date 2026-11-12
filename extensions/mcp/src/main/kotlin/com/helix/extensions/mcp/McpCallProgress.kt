package com.helix.extensions.mcp

import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Delivery facts only. A sent request with no reply is not proof that the remote action stopped. */
internal interface McpCallProgress {
    val requestSent: Boolean
    val connectionAttempts: Int
}

internal class McpCallException(
    override val requestSent: Boolean,
    override val connectionAttempts: Int,
    cause: Exception,
) : IOException("MCP exchange failed", cause),
    McpCallProgress

internal class McpCallCancellation(
    override val requestSent: Boolean,
    override val connectionAttempts: Int,
    val deadlineExceeded: Boolean,
    cause: CancellationException,
) : CancellationException("MCP exchange cancelled"),
    McpCallProgress {
    init {
        initCause(cause)
    }
}

internal class McpCallInterruption(
    override val requestSent: Boolean,
    override val connectionAttempts: Int,
    cause: InterruptedException,
) : InterruptedException("MCP exchange interrupted"),
    McpCallProgress {
    init {
        initCause(cause)
    }
}

/**
 * Local host admission only. Never wrap transport, credential refresh, or other external effects here.
 * Map delivery facts while retaining cancellation/interruption types.
 */
@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
fun requireMcpCallAdmission(admission: () -> Unit) {
    try {
        admission()
    } catch (failure: Exception) {
        val tracked =
            when (failure) {
                is kotlinx.coroutines.TimeoutCancellationException -> McpCallCancellation(false, 0, true, failure)
                is CancellationException -> McpCallCancellation(false, 0, false, failure)
                is InterruptedException -> McpCallInterruption(false, 0, failure)
                else -> McpCallException(false, 0, failure)
            }
        throw tracked
    }
}

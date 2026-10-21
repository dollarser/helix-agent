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

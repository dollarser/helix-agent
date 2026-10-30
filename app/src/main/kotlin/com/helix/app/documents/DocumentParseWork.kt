package com.helix.app.documents

import com.helix.extensions.documents.DocumentException
import com.helix.tools.framework.ExecutableToolCall
import java.time.Instant

/** Liveness only; never a source authorization or a synthetic tool invocation. */
internal class DocumentParseWork(
    val deadline: Instant,
    val cancelled: () -> Boolean,
) {
    fun checkActive() {
        if (cancelled()) throw DocumentException("CANCELLED")
        if (!Instant.now().isBefore(deadline)) throw DocumentException("TIMED_OUT")
    }

    companion object {
        fun from(call: ExecutableToolCall) = DocumentParseWork(call.deadline) { call.cancel.isCancelled() }
    }
}

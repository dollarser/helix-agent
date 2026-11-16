package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.provider.api.ProviderToolNames

/** Subscription transport preserves registered names and enforces current request membership. */
internal class CodexToolNames(
    request: ModelRequest,
) {
    private val names = ProviderToolNames(request)

    fun encode(request: ModelRequest): ModelRequest {
        request.tools.forEach { names.encode(it.name.value) }
        request.messages.forEach { message ->
            message.toolCalls.forEach { names.encode(it.name.value) }
            message.toolName?.let { names.encode(it.value) }
        }
        return request
    }

    fun decode(events: List<ModelEvent>): List<ModelEvent> {
        if (events.any { it is ModelEvent.ToolCallStarted && names.decode(it.name) == null }) {
            (events as? java.io.Closeable)?.close()
            return listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false))
        }
        return events
    }
}

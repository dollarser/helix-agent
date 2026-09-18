package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.core.model.ToolName
import java.security.MessageDigest

/** Request-local wire aliases. Local dispatch, approval bindings and persisted history keep their names. */
internal class CodexToolNames(
    request: ModelRequest,
) {
    private val names =
        buildSet {
            request.tools.forEach { add(it.name) }
            request.messages.forEach { message ->
                message.toolName?.let { add(it) }
                message.toolCalls.forEach { add(it.name) }
            }
        }.associateWith(::alias)
    private val originals = names.entries.associate { (original, wire) -> wire.value to original.value }
    private val allowed = request.tools.map { it.name.value }.toSet()

    init {
        require(originals.size == names.size) { "tool wire name collision" }
    }

    fun encode(request: ModelRequest): ModelRequest =
        request.copy(
            tools = request.tools.map { it.copy(name = names.getValue(it.name)) },
            messages =
                request.messages.map { message ->
                    message.copy(
                        toolName = message.toolName?.let(names::getValue),
                        toolCalls = message.toolCalls.map { it.copy(name = names.getValue(it.name)) },
                    )
                },
        )

    fun decode(events: List<ModelEvent>): List<ModelEvent> {
        if (events.any { it is ModelEvent.ToolCallStarted && originals[it.name] !in allowed }) {
            (events as? java.io.Closeable)?.close()
            return listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false))
        }
        return MappedModelEvents(events) { event ->
            if (event is ModelEvent.ToolCallStarted) event.copy(name = originals.getValue(event.name)) else event
        }
    }

    private fun alias(name: ToolName): ToolName {
        // Alias every name so a valid local name cannot collide with an escaped dotted name.
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(name.value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return ToolName("h" + digest.take(63))
    }
}

package com.helix.provider.api

import com.helix.core.model.ModelRequest

/** Request-scoped name membership. Registered portable names go to every provider unchanged. */
public class ProviderToolNames(
    request: ModelRequest,
) {
    private val allowed = request.tools.mapTo(mutableSetOf()) { it.name.value }
    private val known =
        buildSet {
            addAll(allowed)
            request.messages.forEach { message ->
                message.toolCalls.forEach { add(it.name.value) }
                message.toolName?.let { add(it.value) }
            }
        }

    init {
        require(known.all { Regex("[A-Za-z0-9_-]{1,64}").matches(it) }) { "non-portable registered tool name" }
    }

    public fun encode(name: String): String {
        require(name in known) { "tool name is absent from this request" }
        return name
    }

    /** Historical names can be replayed, but only current declarations can start new calls. */
    public fun decode(name: String): String? = name.takeIf { it in allowed }
}

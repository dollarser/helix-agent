package com.helix.provider.openai.responses

import com.helix.core.model.ModelRequest

/**
 * Responses function names allow only ASCII letters, digits, underscores and hyphens (64 chars).
 * Keep domain identities intact: this immutable, request-scoped bijection translates schemas,
 * historical calls and streamed calls. Reserve every legal name before assigning aliases so
 * dotted names, underscore names and literal alias-shaped names cannot collide. Historical-only
 * tools participate too. No state is shared between concurrent streams or provider instances.
 */
internal class ResponsesToolNames(
    request: ModelRequest,
) {
    private val encoded: Map<String, String>
    private val decoded: Map<String, String>

    init {
        val names =
            buildSet {
                request.tools.forEach { add(it.name.value) }
                request.messages.forEach { message ->
                    message.toolCalls.forEach { add(it.name.value) }
                    message.toolName?.let { add(it.value) }
                }
            }
        val reserved = names.filterTo(mutableSetOf()) { VALID.matches(it) }
        var ordinal = 0
        encoded =
            names.sorted().associateWith { name ->
                if (VALID.matches(name)) {
                    name
                } else {
                    var alias: String
                    do {
                        alias = "helix_tool_${ordinal++}"
                    } while (!reserved.add(alias))
                    alias
                }
            }
        decoded = encoded.entries.associate { (domain, wire) -> wire to domain }
    }

    fun encode(name: String): String = requireNotNull(encoded[name])

    // Unknown names remain unknown and are rejected by normal tool registry admission.
    fun decode(name: String): String = decoded[name] ?: name

    private companion object {
        val VALID = Regex("[a-zA-Z0-9_-]{1,64}")
    }
}

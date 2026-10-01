package com.helix.tools.framework

import com.helix.core.model.ToolBindingRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** A complete candidate; publication never exposes a descriptor without its executor. */
data class ToolBinding(
    val descriptor: ToolDescriptor,
    val executor: ToolExecutor,
    val implementationRevision: String = descriptor.contractHash.hex,
) {
    init {
        require(implementationRevision.isNotBlank())
        require(implementationRevision.length <= 512)
        require(
            executor !is JobObservationExecutor ||
                descriptor.operationClass == com.helix.core.model.ToolOperationClass.READ_ONLY,
        ) { "Job observation bindings must be read-only" }
    }

    val owner: String get() = bindingOwner(descriptor.origin)
}

/** Immutable lease of one registry incarnation. Removal prevents admission, not historical inspection. */
data class RegisteredToolBinding internal constructor(
    val binding: ToolBinding,
    val ref: ToolBindingRef,
) {
    val descriptor: ToolDescriptor get() = binding.descriptor
    val executor: ToolExecutor get() = binding.executor
}

/** Structured identity avoids delimiter collisions; versions belong to revisions, not owner keys. */
fun bindingOwner(origin: ToolOrigin): String =
    JsonArray(
        when (origin) {
            ToolOrigin.BuiltInOrigin -> listOf("built-in")
            is ToolOrigin.PluginOrigin -> listOf("plugin", origin.pluginId)
            is ToolOrigin.McpOrigin -> listOf("mcp", origin.serverId)
            is ToolOrigin.A2aOrigin -> listOf("a2a", origin.agentId)
        }.map(::JsonPrimitive),
    ).toString()

fun bindingOwner(
    kind: String,
    id: String,
): String = JsonArray(listOf(kind, id).map(::JsonPrimitive)).toString()

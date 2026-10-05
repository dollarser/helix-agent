package com.helix.extensions.plugin

import com.helix.core.policy.DataOrigin
import com.helix.core.policy.UserScope
import com.helix.tools.framework.ToolDescriptor

/** Trusted native contributions. Actual execution must still pass the normal Dispatcher. */
data class PluginExecutionContext(
    val scope: UserScope?,
    val dataOrigin: DataOrigin,
)

fun PluginRegistry.executionContext(
    descriptor: ToolDescriptor?,
    sessionId: String,
): PluginExecutionContext? =
    descriptor?.let { tool ->
        runtime(tool)
            ?.takeIf {
                it in selectedRuntimes(sessionId)
            }?.let { PluginExecutionContext(it.scopeFor(tool, sessionId), it.dataOrigin) }
    }

/** Only plugins represented in the admitted tool list contribute instructions. */
fun PluginRegistry.instructions(
    descriptors: List<ToolDescriptor>,
    sessionId: String,
): String? =
    descriptors
        .mapNotNull(::runtime)
        .filter {
            it in selectedRuntimes(sessionId)
        }.distinct()
        .flatMap { it.bundledSkills }
        .distinct()
        .joinToString("\n\n") { it.content }
        .takeIf { it.isNotBlank() }

fun PluginRegistry.preferredTools(sessionId: String): Set<String> =
    selectedRuntimes(sessionId).flatMap { it.preferredTools }.toSet()

fun PluginRegistry.imageScopes(sessionId: String): List<UserScope> =
    selectedRuntimes(sessionId).mapNotNull { it.imageScope(sessionId) }

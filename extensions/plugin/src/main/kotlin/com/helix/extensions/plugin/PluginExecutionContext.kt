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

/** Content uses the same repository and source/selection checks as skills.read. */
fun PluginRegistry.instructions(
    repository: com.helix.extensions.skills.SkillRepository,
    sessionId: String,
): String? =
    skillDocuments(sessionId)
        .map {
            it.catalogEntry.let { entry ->
                com.helix.extensions.skills
                    .SkillKey(entry.source, entry.name, entry.contentHash)
            }
        }.distinct()
        .filter { key -> repository.list(sessionId).any { it.key == key && it.enabled } }
        .joinToString("\n\n") { repository.read(it, sessionId).body }
        .takeIf { it.isNotBlank() }

fun PluginRegistry.preferredTools(sessionId: String): Set<String> =
    selectedRuntimes(sessionId).flatMap { it.preferredTools }.toSet()

fun PluginRegistry.imageScopes(sessionId: String): List<UserScope> =
    selectedRuntimes(sessionId).mapNotNull { it.imageScope(sessionId) }

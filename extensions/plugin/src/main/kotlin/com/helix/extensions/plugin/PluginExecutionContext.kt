package com.helix.extensions.plugin

import com.helix.core.policy.DataOrigin
import com.helix.core.policy.UserScope
import com.helix.tools.framework.ToolDescriptor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    turnId: String? = null,
): String? {
    val eligible =
        repository
            .listForModel(sessionId, turnId)
            .filter { it.enabled }
            .map { it.key }
            .toSet()
    val directory =
        (
            repository.list(sessionId).mapNotNull { item ->
                if (!item.enabled || item.key !in eligible) return@mapNotNull null
                val document = repository.inspect(item.key)
                if (document.catalogEntry.source != com.helix.extensions.skills.SkillSource.BUILT_IN ||
                    document.catalogEntry.name != "helix-docs"
                ) {
                    return@mapNotNull null
                }
                document
            } + skillDocuments(sessionId)
        ).groupBy { it.catalogEntry }
            .map { (entry, docs) -> docs.first { it.catalogEntry == entry } }
            .filter { document ->
                val entry = document.catalogEntry
                com.helix.extensions.skills
                    .SkillKey(entry.source, entry.name, entry.contentHash) in eligible
            }.map { document ->
                val entry = document.catalogEntry
                buildJsonObject {
                    put("source", entry.source.name)
                    put("name", entry.name)
                    put("snapshotHash", entry.contentHash)
                    put("description", entry.description)
                }.toString()
            }.distinct()
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    return selectedRuntimes(sessionId)
        .map { it.requiredInstructions }
        .filter { it.isNotBlank() }
        .plus(directory?.let { "[SKILL CATALOG]\n$it\n[/SKILL CATALOG]" }.let(::listOfNotNull))
        .plus(
            turnId
                ?.let {
                    repository
                        .activatedContent(sessionId, it)
                        .distinctBy { (document, arguments) ->
                            document.catalogEntry to arguments
                        }.map { (document, arguments) ->
                            val entry = document.catalogEntry
                            "[SKILL ${entry.source}:${entry.name}@${entry.contentHash}]\n${document.body}\n" +
                                "[USER SKILL ARGUMENTS]\n$arguments\n[/USER SKILL ARGUMENTS]\n[/SKILL]"
                        }
                }.orEmpty(),
        ).distinct()
        .joinToString("\n\n")
        .takeIf { it.isNotBlank() }
}

fun PluginRegistry.preferredTools(sessionId: String): Set<String> =
    selectedRuntimes(sessionId).flatMap { it.preferredTools }.toSet()

fun PluginRegistry.imageScopes(sessionId: String): List<UserScope> =
    selectedRuntimes(sessionId).mapNotNull { it.imageScope(sessionId) }

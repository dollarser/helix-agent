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

/** Immutable external content facts. Required means budget retention, never trust or authority. */
sealed interface PluginContentContribution {
    data class RequiredInstructions(
        val owner: NativePluginState,
        val text: String,
        val contentHash: String,
    ) : PluginContentContribution

    data class SkillCatalog(
        val key: com.helix.extensions.skills.SkillKey,
        val description: String,
        val owner: NativePluginState? = null,
    ) : PluginContentContribution

    /** Body and already accepted user arguments must be retained/omitted together. */
    data class ActivatedSkill(
        val key: com.helix.extensions.skills.SkillKey,
        val body: String,
        val acceptedArguments: String,
        val owners: List<NativePluginState>,
    ) : PluginContentContribution
}

/** Same snapshot eligibility as skills.read; no frontmatter grants ambient discovery. */
fun PluginRegistry.contentContributions(
    repository: com.helix.extensions.skills.SkillRepository,
    sessionId: String,
    turnId: String? = null,
): List<PluginContentContribution> {
    val eligible =
        repository
            .listForModel(sessionId, turnId)
            .filter { it.enabled }
            .map { it.key }
            .toSet()
    val owners = selectedContentOwners(sessionId)

    fun key(document: com.helix.extensions.skills.SkillDocument) =
        document.catalogEntry.let {
            com.helix.extensions.skills
                .SkillKey(it.source, it.name, it.contentHash)
        }
    return buildList {
        owners.forEach { (owner, plugin) ->
            plugin.requiredInstructions.takeIf { it.isNotBlank() }?.let { text ->
                val hash =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(text.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                add(PluginContentContribution.RequiredInstructions(owner, text, hash))
            }
        }
        repository.list(sessionId).filter { it.enabled && it.key in eligible }.forEach { item ->
            val document = repository.inspect(item.key)
            if (document.catalogEntry.source == com.helix.extensions.skills.SkillSource.BUILT_IN &&
                document.hostCatalogued
            ) {
                add(PluginContentContribution.SkillCatalog(item.key, item.description))
            }
        }
        owners.forEach { (owner, plugin) ->
            plugin.skills.distinctBy(::key).filter { key(it) in eligible }.forEach { document ->
                add(PluginContentContribution.SkillCatalog(key(document), document.catalogEntry.description, owner))
            }
        }
        turnId?.let { turn ->
            repository
                .activatedContent(sessionId, turn)
                .distinctBy { (document, arguments) ->
                    key(document) to arguments
                }.forEach { (document, arguments) ->
                    val skillKey = key(document)
                    add(
                        PluginContentContribution.ActivatedSkill(
                            skillKey,
                            document.body,
                            arguments,
                            owners.filter { (_, plugin) -> plugin.skills.any { key(it) == skillKey } }.map { it.first },
                        ),
                    )
                }
        }
    }
}

fun PluginRegistry.preferredTools(sessionId: String): Set<String> =
    selectedRuntimes(sessionId)
        .flatMap {
            it.preferredTools
        }.toSet()

fun PluginRegistry.imageScopes(sessionId: String): List<UserScope> =
    selectedRuntimes(sessionId).mapNotNull {
        it.imageScope(sessionId)
    }

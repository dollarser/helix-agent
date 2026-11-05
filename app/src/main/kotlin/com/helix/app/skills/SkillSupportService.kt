package com.helix.app.skills

import com.helix.app.plugin.PluginService
import com.helix.app.plugin.PluginSetupReason
import com.helix.extensions.skills.SkillDocument
import com.helix.extensions.skills.SkillFileInfo
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.SkillSource

/** Describes content and published capabilities. It never connects, selects or authorizes a tool. */
class SkillSupportService(
    private val repository: SkillRepository,
    private val plugins: PluginService,
    private val toolNames: () -> Set<String>,
) {
    fun inspect(
        key: SkillKey,
        sessionId: String? = null,
    ): SkillSupportSnapshot {
        val document = repository.inspect(key)
        val dependency =
            if (key.source == SkillSource.BUILT_IN && key.name == "web-research") {
                webDependency(sessionId)
            } else {
                null
            }
        return SkillSupportSnapshot(document, repository.inspectFiles(key), dependency, "skills.run" in toolNames())
    }

    private fun webDependency(sessionId: String?): SkillWebDependency {
        if ("http.fetch" in toolNames()) return SkillWebDependency(true, null, null)
        val record = plugins.list().singleOrNull { it.native?.pluginId == "browser" }
        val facts =
            record?.let { installed ->
                if (sessionId == null) {
                    plugins.componentReadiness(installed)
                } else {
                    plugins.requestReadiness(sessionId).plugins.singleOrNull { it.pluginId == installed.id }
                }
            }
        return if (facts == null) {
            SkillWebDependency(false, record?.id, PluginSetupReason.HOST_UNAVAILABLE)
        } else {
            SkillWebDependency(
                browserCapabilityProvided(facts, sessionId != null),
                record.id,
                facts.setup,
            )
        }
    }
}

data class SkillWebDependency(
    val provided: Boolean,
    val pluginId: String?,
    val reason: PluginSetupReason?,
)

data class SkillSupportSnapshot(
    val document: SkillDocument,
    val files: List<SkillFileInfo>,
    val web: SkillWebDependency?,
    val scriptRunnerProvided: Boolean = false,
) {
    val unsupported: Set<String> get() = document.invocation.unsupportedExecutionFields
    val unapplied: Set<String> get() =
        document.additionalFields.keys - unsupported -
            setOf("user-invocable", "disable-model-invocation")
}

internal fun browserCapabilityProvided(
    snapshot: com.helix.app.plugin.PluginReadinessSnapshot,
    sessionBound: Boolean,
): Boolean =
    snapshot.enabled && (!sessionBound || snapshot.selected) &&
        snapshot.components.any {
            it.kind == com.helix.extensions.plugin.PluginComponentKind.BROWSER && it.ready
        }

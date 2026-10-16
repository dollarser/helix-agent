package com.helix.app.plugin

import com.helix.extensions.plugin.PluginPackage
import com.helix.extensions.skills.SkillLoader

/** Uses the same complete endpoint equality as installation, never guessed OAuth metadata. */
data class PluginUpdateReport(
    val oldVersion: String?,
    val newVersion: String?,
    val addedSkills: List<String>,
    val removedSkills: List<String>,
    val addedEndpoints: List<String>,
    val removedEndpoints: List<String>,
    val changedEndpoints: List<String>,
) {
    val requiresReconnection: Boolean get() = addedEndpoints.isNotEmpty() || changedEndpoints.isNotEmpty()
}

object PluginUpdateDiffEvaluator {
    fun evaluate(
        current: InstalledPlugin,
        incoming: PluginPackage,
    ): PluginUpdateReport {
        val skills =
            incoming.skills
                .map {
                    SkillLoader().validatePackageMember(it.files.getValue("SKILL.md"), it.directory).catalogEntry.name
                }.toSet()
        val oldSkills = current.skills.map { it.name }.toSet()
        val previous = current.endpoints.associate { it.endpoint.name to it.endpoint }
        val next = incoming.endpoints.associateBy { it.name }
        return PluginUpdateReport(
            current.versionLabel,
            incoming.versionLabel,
            (skills - oldSkills).sorted(),
            (oldSkills - skills).sorted(),
            (next.keys - previous.keys).sorted(),
            (previous.keys - next.keys).sorted(),
            (next.keys intersect previous.keys).filter { next[it] != previous[it] }.sorted(),
        )
    }
}

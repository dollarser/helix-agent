package com.helix.app.plugin

import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillListItem

/** Portable dependency identity, distinct from a local installation UUID or execution grant. */
internal fun expertPluginReference(plugin: InstalledPlugin): String =
    plugin.native?.let { "native:${it.pluginId}" } ?: "package:${plugin.hash}"

internal fun expertSkillReference(key: SkillKey): String =
    "${key.source.name}/${key.ownerId}/${key.name}/${key.snapshotHash}"

data class ExpertAbilityChoice(
    val reference: String,
    val name: String,
    val available: Boolean,
)

fun PluginService.expertPluginChoices(): List<ExpertAbilityChoice> =
    list().groupBy(::expertPluginReference).map { (ref, records) ->
        val record = records.first()
        ExpertAbilityChoice(
            ref,
            record.name,
            records.size == 1 && record.enabled && componentReadiness(record).ready > 0,
        )
    }

fun PluginService.expertSkillChoices(): List<ExpertAbilityChoice> = expertSkillChoices(expertSkills())

internal fun expertSkillChoices(skills: List<SkillListItem>): List<ExpertAbilityChoice> {
    val counts = skills.groupingBy { it.key.name }.eachCount()
    return skills.map { skill ->
        val key = skill.key
        val name =
            if (counts.getValue(key.name) > 1) {
                "${key.name} (${key.source.name} · ${key.snapshotHash.take(8)})"
            } else {
                key.name
            }
        ExpertAbilityChoice(expertSkillReference(key), name, skill.invocation.modelInvocable)
    }
}

package com.helix.app.application.context

import com.helix.app.mcp.ToolSourceCatalogSnapshot
import com.helix.app.plugin.PluginReadinessContext
import com.helix.app.plugin.requestSummary
import com.helix.core.agent.PromptContentIdentity
import com.helix.core.agent.PromptContentOwner
import com.helix.core.agent.PromptContentReadReference
import com.helix.core.agent.PromptContentRetention
import com.helix.core.agent.PromptScope
import com.helix.core.agent.PromptSection
import com.helix.core.agent.PromptSource
import com.helix.extensions.plugin.NativePluginState
import com.helix.extensions.plugin.PluginContentContribution
import com.helix.extensions.skills.SkillKey
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** App maps extension facts to the single Core prompt registry; no extension imports Core orchestration. */
internal object PromptContentContributions {
    fun sections(
        contributions: List<PluginContentContribution>,
        catalog: ToolSourceCatalogSnapshot?,
        readiness: PluginReadinessContext?,
        availableTools: Set<String>,
    ): List<PromptSection> =
        buildList {
            contributions.forEach { item -> add(contribution(item, availableTools)) }
            catalog?.let { addAll(catalogSections(it, availableTools)) }
            readiness?.let { addAll(readinessSections(it)) }
        }

    private fun contribution(
        item: PluginContentContribution,
        tools: Set<String>,
    ): PromptSection =
        when (item) {
            is PluginContentContribution.RequiredInstructions -> requiredInstructions(item)
            is PluginContentContribution.SkillCatalog -> skillCatalog(item, tools)
            is PluginContentContribution.ActivatedSkill -> activatedSkill(item, tools)
        }

    private fun requiredInstructions(item: PluginContentContribution.RequiredInstructions): PromptSection {
        val owner = item.owner
        return section(
            kind = "plugin.required",
            order = -1750,
            key = owner.pluginId,
            content = "[PLUGIN GUIDANCE ${owner.pluginId}]\n${item.text}\n[/PLUGIN GUIDANCE]",
            identity =
                PromptContentIdentity(
                    owner = owner.pluginId,
                    revision = item.contentHash,
                    owners = listOf(owner.identity()),
                ),
        )
    }

    private fun skillCatalog(
        item: PluginContentContribution.SkillCatalog,
        availableTools: Set<String>,
    ): PromptSection {
        val key = item.key
        val payload =
            buildJsonObject {
                put("source", key.source.name)
                put("name", key.name)
                put("snapshotHash", key.snapshotHash)
                put("description", item.description)
            }
        return section(
            "skill.catalog",
            -1749,
            key.identity() + ":" + item.owner?.pluginId,
            "[SKILL CATALOG]\n$payload\n[/SKILL CATALOG]",
            PromptContentIdentity(
                "skills:${key.source}",
                key.snapshotHash,
                key.identity(),
                listOfNotNull(item.owner?.identity()),
            ),
            priority = 100,
            readReference = key.readReference(availableTools),
        )
    }

    private fun activatedSkill(
        item: PluginContentContribution.ActivatedSkill,
        availableTools: Set<String>,
    ): PromptSection {
        val key = item.key
        val content =
            "[SKILL ${key.identity()}]\n${item.body}\n" +
                "[USER SKILL ARGUMENTS]\n${item.acceptedArguments}\n[/USER SKILL ARGUMENTS]\n[/SKILL]"
        return section(
            "skill.activation",
            -1748,
            key.identity() + ":" + hash(item.acceptedArguments),
            content,
            PromptContentIdentity(
                "skills:${key.source}",
                key.snapshotHash,
                key.identity(),
                item.owners.map { it.identity() },
            ),
            readReference = key.readReference(availableTools),
        )
    }

    private fun catalogSections(
        snapshot: ToolSourceCatalogSnapshot,
        availableTools: Set<String>,
    ): List<PromptSection> =
        buildList {
            snapshot.entries.forEach { entry ->
                add(
                    section(
                        "tool.catalog",
                        -1747,
                        entry.source,
                        "[TOOL SOURCES: metadata only, not authorization]\n${entry.facts()}\n" +
                            catalogReadHint(availableTools) + "[/TOOL SOURCES]",
                        PromptContentIdentity(entry.source, entry.revision),
                        priority = 100,
                    ),
                )
            }
            if (snapshot.omittedAtAcquisition > 0) {
                add(
                    section(
                        "tool.catalog.omissions",
                        -1747,
                        "acquisition",
                        "Tool source acquisition omitted ${snapshot.omittedAtAcquisition} rows. " +
                            catalogReadHint(availableTools),
                        PromptContentIdentity("tool-directory", "bounded-acquisition"),
                        priority = 101,
                    ),
                )
            }
        }

    private fun readinessSections(snapshot: PluginReadinessContext): List<PromptSection> =
        buildList {
            add(
                section(
                    "plugin.readiness.summary",
                    -1746,
                    "acquisition",
                    snapshot.acquisitionSummary().toString(),
                    PromptContentIdentity("plugin-readiness", "acquisition"),
                    priority = 70,
                ),
            )
            snapshot.plugins.forEach { plugin ->
                add(
                    section(
                        "plugin.readiness",
                        -1746,
                        plugin.pluginId,
                        "[PLUGIN READINESS]\n${plugin.requestSummary()}\n[/PLUGIN READINESS]",
                        PromptContentIdentity(plugin.pluginId, plugin.revision.toString()),
                        priority = if (plugin.selected) 80 else 20,
                    ),
                )
            }
        }

    private fun catalogReadHint(tools: Set<String>): String =
        if ("tools.search" in tools) {
            "Use tools.search for callable schemas when relevant; results remain subject to policy.\n"
        } else {
            "Only the current request's callable schemas are available.\n"
        }

    private fun section(
        kind: String,
        order: Int,
        key: String,
        content: String,
        identity: PromptContentIdentity,
        priority: Int? = null,
        readReference: PromptContentReadReference? = null,
    ): PromptSection =
        PromptSection(
            name = "$kind.${hash(key).take(24)}",
            order = order,
            scope = PromptScope.SKILL,
            source = PromptSource.EXTERNAL_CONTENT,
            retention = if (priority != null) PromptContentRetention.OPTIONAL else PromptContentRetention.REQUIRED,
            priority = priority ?: 0,
            identity = identity,
            readReference = readReference,
        ) { content }

    private fun hash(value: String): String =
        MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

private fun NativePluginState.identity() = PromptContentOwner(pluginId, runtimeId, version, revision, fingerprint)

private fun SkillKey.identity() = "${source.name}:$name@$snapshotHash"

private fun SkillKey.readReference(tools: Set<String>): PromptContentReadReference? =
    if ("skills.read" !in tools) {
        null
    } else {
        PromptContentReadReference(
            "skills.read",
            buildJsonObject {
                put("source", source.name)
                put("name", name)
                put("snapshotHash", snapshotHash)
            }.toString(),
        )
    }

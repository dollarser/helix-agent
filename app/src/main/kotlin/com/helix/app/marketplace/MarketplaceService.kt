package com.helix.app.marketplace

import com.helix.app.plugin.InstalledPlugin
import com.helix.app.plugin.PluginService
import com.helix.extensions.plugin.PluginPackage
import com.helix.extensions.plugin.PluginPackageReader
import com.helix.extensions.plugin.PluginSkill
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillRepository
import java.security.MessageDigest

class MarketplaceService(
    val pluginService: PluginService,
    val skillRepository: SkillRepository,
) {
    fun items(): List<MarketplaceItem> = MarketplaceCatalog.items()

    fun status(item: MarketplaceItem): MarketplaceItemStatus =
        when (item.type) {
            MarketplaceItemType.SKILL -> {
                val name = item.targetSkillName ?: item.id
                val connector = findInstalled(item)
                val matched =
                    skillRepository.list().firstOrNull {
                        it.key in connector?.skills.orEmpty() &&
                            it.key.name == name
                    }
                when {
                    connector == null -> MarketplaceItemStatus.NOT_INSTALLED
                    !connector.enabled || matched == null -> MarketplaceItemStatus.INSTALLED_INACTIVE
                    matched.enabled -> MarketplaceItemStatus.ACTIVE
                    else -> MarketplaceItemStatus.INSTALLED_INACTIVE
                }
            }

            MarketplaceItemType.CONNECTOR, MarketplaceItemType.MCP -> {
                val matched =
                    findInstalled(item)
                when {
                    matched == null -> MarketplaceItemStatus.NOT_INSTALLED

                    matched.enabled &&
                        matched.endpoints.any {
                            pluginService.enabled(
                                it,
                            )
                        }
                    -> MarketplaceItemStatus.ACTIVE

                    else -> MarketplaceItemStatus.INSTALLED_INACTIVE
                }
            }
        }

    fun install(item: MarketplaceItem): InstalledPlugin {
        val existing = findInstalled(item)
        return when (item.type) {
            MarketplaceItemType.CONNECTOR, MarketplaceItemType.MCP -> {
                val reader = PluginPackageReader()
                val bundle = reader.readJson(item.payload.toByteArray(Charsets.UTF_8))
                pluginService.install(
                    bundle.copy(name = item.targetConnectorName ?: item.id, source = "MARKETPLACE"),
                    identity = "marketplace:${item.id}",
                    expectedRevision = existing?.revision,
                )
            }

            MarketplaceItemType.SKILL -> {
                val name = item.targetSkillName ?: item.id
                val skillBytes = item.payload.toByteArray(Charsets.UTF_8)
                val bundle =
                    PluginPackage(
                        name = item.targetConnectorName ?: "${item.id}-skill",
                        source = "MARKETPLACE",
                        contentHash = sha256(skillBytes),
                        endpoints = emptyList(),
                        skills =
                            listOf(
                                PluginSkill(
                                    directory = name,
                                    files = mapOf("SKILL.md" to skillBytes),
                                ),
                            ),
                        diagnostics = emptyList(),
                    )
                val installed =
                    pluginService.install(
                        bundle,
                        "marketplace:${item.id}",
                        existing?.revision,
                        sessionScoped = false,
                    )
                val skillKey = installed.skills.firstOrNull()
                if (skillKey != null) {
                    pluginService.setSkillEnabled(skillKey, true)
                }
                installed
            }
        }
    }

    fun findInstalled(item: MarketplaceItem): InstalledPlugin? =
        pluginService.list().firstOrNull {
            it.identity == "marketplace:${item.id}"
        }

    fun uninstall(item: MarketplaceItem) {
        findInstalled(item)?.let { pluginService.remove(it) }
    }

    fun disable(item: MarketplaceItem) {
        val installed = findInstalled(item) ?: return
        when (item.type) {
            MarketplaceItemType.SKILL -> {
                installed.skills.forEach { key ->
                    pluginService.setSkillEnabled(key, false)
                }
            }

            MarketplaceItemType.CONNECTOR, MarketplaceItemType.MCP -> {
                installed.endpoints.forEach { endpoint ->
                    pluginService.disable(endpoint)
                }
            }
        }
    }

    fun enableSkill(item: MarketplaceItem) {
        val installed = findInstalled(item) ?: return
        pluginService.setEnabled(installed.id, true)
        installed.skills.forEach { key ->
            pluginService.setSkillEnabled(key, true)
        }
    }

    fun setSkillEnabled(
        skillName: String,
        enabled: Boolean,
    ) {
        val matched = skillRepository.list().firstOrNull { it.key.name == skillName } ?: return
        skillRepository.setEnabled(matched.key, enabled, SkillEnablementScope.GLOBAL)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

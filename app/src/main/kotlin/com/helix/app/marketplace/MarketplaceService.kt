package com.helix.app.marketplace

import com.helix.app.connector.ConnectorService
import com.helix.app.connector.InstalledConnector
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.connector.ConnectorPackage
import com.helix.extensions.skills.connector.ConnectorPackageReader
import com.helix.extensions.skills.connector.ConnectorSkill
import java.security.MessageDigest

class MarketplaceService(
    val connectorService: ConnectorService,
    val skillRepository: SkillRepository,
) {
    fun items(): List<MarketplaceItem> = MarketplaceCatalog.items()

    fun status(item: MarketplaceItem): MarketplaceItemStatus =
        when (item.type) {
            MarketplaceItemType.SKILL -> {
                val name = item.targetSkillName ?: item.id
                val connectorName = item.targetConnectorName ?: "${item.id}-skill"
                val connector = connectorService.list().firstOrNull { it.name == connectorName }
                val matched = skillRepository.list().firstOrNull { it.key.name == name }
                when {
                    connector == null -> MarketplaceItemStatus.NOT_INSTALLED
                    matched == null -> MarketplaceItemStatus.NOT_INSTALLED
                    matched.enabled -> MarketplaceItemStatus.ACTIVE
                    else -> MarketplaceItemStatus.INSTALLED_INACTIVE
                }
            }

            MarketplaceItemType.CONNECTOR, MarketplaceItemType.MCP -> {
                val targetName = item.targetConnectorName ?: item.id
                val matched =
                    connectorService.list().firstOrNull { record ->
                        record.name == targetName || record.name == item.id
                    }
                when {
                    matched == null -> MarketplaceItemStatus.NOT_INSTALLED
                    matched.endpoints.any { connectorService.enabled(it) } -> MarketplaceItemStatus.ACTIVE
                    else -> MarketplaceItemStatus.INSTALLED_INACTIVE
                }
            }
        }

    fun install(item: MarketplaceItem): InstalledConnector =
        when (item.type) {
            MarketplaceItemType.CONNECTOR, MarketplaceItemType.MCP -> {
                val reader = ConnectorPackageReader()
                val bundle = reader.readJson(item.payload.toByteArray(Charsets.UTF_8))
                connectorService.install(bundle)
            }

            MarketplaceItemType.SKILL -> {
                val name = item.targetSkillName ?: item.id
                val skillBytes = item.payload.toByteArray(Charsets.UTF_8)
                val bundle =
                    ConnectorPackage(
                        name = item.targetConnectorName ?: "${item.id}-skill",
                        source = "MARKETPLACE",
                        contentHash = sha256(skillBytes),
                        endpoints = emptyList(),
                        skills =
                            listOf(
                                ConnectorSkill(
                                    directory = name,
                                    files = mapOf("SKILL.md" to skillBytes),
                                ),
                            ),
                        diagnostics = emptyList(),
                    )
                val installed = connectorService.install(bundle)
                val skillKey = installed.skills.firstOrNull()
                if (skillKey != null) {
                    connectorService.setSkillEnabled(skillKey, true)
                }
                installed
            }
        }

    fun findInstalled(item: MarketplaceItem): InstalledConnector? {
        val targetName =
            item.targetConnectorName ?: when (item.type) {
                MarketplaceItemType.SKILL -> "${item.id}-skill"
                else -> item.id
            }
        return connectorService.list().firstOrNull { record ->
            record.name == targetName || record.name == item.id
        }
    }

    fun uninstall(item: MarketplaceItem) {
        findInstalled(item)?.let { connectorService.remove(it) }
    }

    fun disable(item: MarketplaceItem) {
        val installed = findInstalled(item) ?: return
        when (item.type) {
            MarketplaceItemType.SKILL -> {
                installed.skills.forEach { key ->
                    connectorService.setSkillEnabled(key, false)
                }
            }

            MarketplaceItemType.CONNECTOR, MarketplaceItemType.MCP -> {
                installed.endpoints.forEach { endpoint ->
                    connectorService.disable(endpoint)
                }
            }
        }
    }

    fun enableSkill(item: MarketplaceItem) {
        val installed = findInstalled(item) ?: return
        installed.skills.forEach { key ->
            connectorService.setSkillEnabled(key, true)
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

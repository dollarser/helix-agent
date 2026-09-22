package com.helix.app.connector

import com.helix.app.marketplace.MarketplaceCatalog
import com.helix.app.marketplace.MarketplaceItemType
import com.helix.extensions.skills.connector.ConnectorPackageReader
import java.security.MessageDigest

/** Only the trusted built-in catalogue plus exact bytes can adopt a legacy marketplace identity. */
internal fun legacyConnectorIdentity(record: InstalledConnector): InstalledConnector {
    if (record.source != "MARKETPLACE" || record.identity != "legacy:${record.id}") return record
    val matches =
        MarketplaceCatalog.items().filter { item ->
            val name =
                item.targetConnectorName ?: if (item.type == MarketplaceItemType.SKILL) "${item.id}-skill" else item.id
            val hash =
                if (item.type == MarketplaceItemType.SKILL) {
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(item.payload.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                } else {
                    ConnectorPackageReader().readJson(item.payload.toByteArray(Charsets.UTF_8)).contentHash
                }
            record.name == name && record.hash == hash
        }
    return matches.singleOrNull()?.let { match ->
        record.copy(identity = "marketplace:${match.id}", sessionScoped = match.type != MarketplaceItemType.SKILL)
    } ?: record
}

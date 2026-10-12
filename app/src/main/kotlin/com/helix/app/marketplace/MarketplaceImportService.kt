package com.helix.app.marketplace

import android.net.Uri
import com.helix.app.R
import com.helix.app.plugin.InstalledPlugin
import com.helix.app.plugin.PluginService
import com.helix.extensions.plugin.PluginMarketplace
import com.helix.extensions.plugin.PluginPackage
import java.security.MessageDigest

/** Discovery-source imports reuse PluginService for every actual install; they own no plugin state. */
class MarketplaceImportService(
    private val pluginService: PluginService,
    private val sourceStore: MarketplaceSourceStore?,
) {
    fun sources(): List<PluginMarketplace> = sourceStore?.list().orEmpty()

    fun previewSource(uri: Uri): MarketplaceSourcePreview =
        requireNotNull(sourceStore).preview(pluginService.captureImport(uri))

    fun addSource(
        preview: MarketplaceSourcePreview,
        cancelled: () -> Boolean = { false },
    ) = requireNotNull(sourceStore).add(preview, cancelled)

    fun removeSource(source: PluginMarketplace) =
        requireNotNull(sourceStore).remove(source.identity, source.contentHash)

    fun items(): List<MarketplaceItem> =
        sources().flatMap { source ->
            source.entries.map { entry ->
                MarketplaceItem(
                    id = "external-" + sha256("${source.identity}:${entry.name}".toByteArray()),
                    nameRes = R.string.marketplace_title,
                    type = MarketplaceItemType.CONNECTOR,
                    summaryRes = R.string.marketplace_subtitle,
                    descriptionRes = R.string.marketplace_subtitle,
                    author = source.name,
                    authRequirement =
                        if (entry.bundle?.endpoints?.any { it.needsCredential } == true) {
                            MarketplaceAuthRequirement.BEARER_TOKEN
                        } else {
                            MarketplaceAuthRequirement.NONE
                        },
                    tags = listOf(source.name, entry.name),
                    payload = "",
                    imported = ImportedMarketplaceItem(source.identity, source.contentHash, source.name, entry),
                )
            }
        }

    fun previewItem(item: MarketplaceItem): PluginPackage {
        val imported = requireNotNull(item.imported)
        val current =
            requireNotNull(
                sourceStore,
            ).candidate(imported.sourceIdentity, imported.sourceHash, imported.entry.name)
        return requireNotNull(current.bundle) { "MARKETPLACE_SOURCE_NOT_BUNDLED" }
    }

    fun previewProvided(
        item: MarketplaceItem,
        uri: Uri,
    ): PluginPackage {
        val bundle = pluginService.preview(uri)
        require(bundle.name == requireNotNull(item.imported).entry.name) { "MARKETPLACE_PLUGIN_NAME_MISMATCH" }
        return bundle
    }

    fun installCandidate(
        item: MarketplaceItem,
        bundle: PluginPackage,
        expectedRevision: Long?,
        cancelled: () -> Boolean = { false },
    ): InstalledPlugin {
        val imported = requireNotNull(item.imported)
        val entry =
            requireNotNull(
                sourceStore,
            ).candidate(imported.sourceIdentity, imported.sourceHash, imported.entry.name)
        val expectedHash = entry.bundle?.contentHash
        require(bundle.name == entry.name && (expectedHash == null || expectedHash == bundle.contentHash)) {
            "MARKETPLACE_CONTENT_CHANGED"
        }
        return pluginService.install(bundle, item.installIdentity(), expectedRevision, cancelled)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

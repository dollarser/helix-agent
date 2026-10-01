package com.helix.app.plugin

import com.helix.app.skills.WorkspaceImportSource
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.plugin.PluginPackage
import com.helix.extensions.plugin.PluginPackageReader
import java.nio.file.Files
import java.nio.file.Path

/** Model-visible paths are scoped; captured bytes never leave this validation/install transaction. */
class PluginInstallationService(
    store: WorkspaceArtifactStore,
    temporaryRoot: Path,
    private val service: () -> PluginService,
) {
    private val source = WorkspaceImportSource(store, temporaryRoot)
    private val reader = PluginPackageReader()

    fun preview(
        path: String,
        cancelled: () -> Boolean = { false },
    ): PluginPackage =
        source.capture(path, cancelled) { captured ->
            require(Files.isRegularFile(captured)) { "CONNECTOR_JSON_OR_ZIP_REQUIRED" }
            val magic = Files.newInputStream(captured).use { it.read() to it.read() }
            if (magic == ('P'.code to 'K'.code)) {
                reader.readZip(captured)
            } else {
                reader.readJson(Files.readAllBytes(captured))
            }
        }

    fun installedId(bundle: PluginPackage): String? =
        service().list().firstOrNull { it.identity == "local:${bundle.source}:${bundle.contentHash}" }?.id

    @Synchronized
    fun install(
        path: String,
        expectedHash: String,
        cancelled: () -> Boolean = { false },
    ): InstalledPlugin {
        require(Regex("[a-f0-9]{64}").matches(expectedHash)) { "CONNECTOR_INVALID_HASH" }
        val bundle = preview(path, cancelled)
        require(bundle.contentHash == expectedHash) { "CONNECTOR_CONTENT_CHANGED: preview again" }
        check(!cancelled()) { "IMPORT_CANCELLED" }
        // No reads of the original source after hash verification; no connection happens on install.
        return service().install(bundle, cancelled = cancelled)
    }
}

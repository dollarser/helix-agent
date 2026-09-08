package com.helix.app.connector

import com.helix.app.skills.WorkspaceImportSource
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.skills.connector.ConnectorPackage
import com.helix.extensions.skills.connector.ConnectorPackageReader
import java.nio.file.Files
import java.nio.file.Path

/** Model-visible paths are scoped; captured bytes never leave this validation/install transaction. */
class ConnectorInstallationService(
    store: WorkspaceArtifactStore,
    temporaryRoot: Path,
    private val service: () -> ConnectorService,
) {
    private val source = WorkspaceImportSource(store, temporaryRoot)
    private val reader = ConnectorPackageReader()

    fun preview(
        path: String,
        cancelled: () -> Boolean = { false },
    ): ConnectorPackage =
        source.capture(path, cancelled) { captured ->
            require(Files.isRegularFile(captured)) { "CONNECTOR_JSON_OR_ZIP_REQUIRED" }
            val magic = Files.newInputStream(captured).use { it.read() to it.read() }
            if (magic == ('P'.code to 'K'.code)) {
                reader.readZip(captured)
            } else {
                reader.readJson(Files.readAllBytes(captured))
            }
        }

    fun installedId(hash: String): String? = service().list().firstOrNull { it.hash == hash }?.id

    @Synchronized
    fun install(
        path: String,
        expectedHash: String,
        cancelled: () -> Boolean = { false },
    ): InstalledConnector {
        require(Regex("[a-f0-9]{64}").matches(expectedHash)) { "CONNECTOR_INVALID_HASH" }
        val bundle = preview(path, cancelled)
        require(bundle.contentHash == expectedHash) { "CONNECTOR_CONTENT_CHANGED: preview again" }
        check(!cancelled()) { "IMPORT_CANCELLED" }
        // No reads of the original source after hash verification; no connection happens on install.
        return service().install(bundle)
    }
}

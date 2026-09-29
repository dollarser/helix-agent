package com.helix.app.vision

import com.helix.app.ToolArtifactRegistrationSink
import com.helix.app.provider.ArtifactVisionImageSource
import com.helix.app.provider.ProviderService
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.resolveFileScopePath
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.runBlocking
import java.io.File

/** App composition for shared image preparation and final destination-bound materialization. */
internal class ToolVisionServices(
    storage: HelixStorage,
    private val workspace: WorkspaceArtifactStore,
    roots: ScopeRootResolver,
    private val scopeId: String,
    temporaryRoot: File,
    clock: Clock,
    providers: ProviderService,
    consent: () -> ToolVisionConsent,
) {
    val imageSource =
        ArtifactVisionImageSource(storage.artifacts, workspace) { image, config ->
            BoundImageAccess(storage, consent()) { providerId, modelId ->
                runBlocking { providers.capabilitiesFor(providerId, modelId)?.vision == true }
            }.verify(image, config)
        }

    val artifactSink =
        ToolArtifactRegistrationSink(storage, workspace::openRead) { path ->
            resolveFileScopePath(path, roots).toFile()
        }

    fun registerTools(
        registry: com.helix.tools.framework.ToolRegistry,
        browser: com.helix.feature.browser.BrowserController,
    ) {
        com.helix.tools.files.ViewImageTool
            .register(registry, preparation)
        com.helix.tools.browser.BrowserTools.registerAll(
            registry,
            com.helix.feature.browser
                .BrowserToolBridgeImpl(browser, workspace, scopeId),
            preparation,
        )
    }

    val preparation =
        ToolImagePreparer(
            storage,
            workspace,
            artifactSink,
            scopeId,
            temporaryRoot,
            clock,
            visionAvailable = { sessionId ->
                runBlocking {
                    val session = storage.sessions.resolve(sessionId)
                    session.providerId?.let { providers.capabilitiesFor(it, session.modelId)?.vision } == true
                }
            },
        )
}

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
    private val storage: HelixStorage,
    private val workspace: WorkspaceArtifactStore,
    roots: ScopeRootResolver,
    private val scopeId: String,
    temporaryRoot: File,
    clock: Clock,
    private val providers: ProviderService,
    consent: () -> ToolVisionConsent,
) {
    private val visionCapabilities =
        VisionCapabilityResolver(
            capabilities = providers::capabilitiesFor,
            probe = providers::runCapabilityTest,
        )

    val imageSource =
        ArtifactVisionImageSource(storage.artifacts, workspace) { image, config ->
            BoundImageAccess(storage, consent()) { providerId, modelId ->
                runBlocking { visionCapabilities.available(providerId, modelId) }
            }.verify(image, config)
        }

    val artifactSink =
        ToolArtifactRegistrationSink(storage, workspace::openRead) { path ->
            resolveFileScopePath(path, roots).toFile()
        }

    val imagePublisher: com.helix.tools.framework.ToolImagePublication by lazy {
        WorkspaceToolImagePublisher(
            workspace,
            artifactSink,
            scopeId,
            preparation,
            registerScreenImage = { call, image, scope -> consent().mobileScreens.register(call, image, scope) },
        ) { session, turn ->
            storage.turns.resolve(turn).sessionId == session
        }
    }

    /** Resolve the displayed recipient off the UI thread; in-flight turns retain their admitted model. */
    suspend fun screenTarget(
        sessionId: String?,
        turnId: String?,
    ): MobileUseScreenTarget? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val session = sessionId?.let(storage.sessions::find) ?: return@withContext null
            val turn = turnId?.let(storage.turns::find)?.takeIf { it.sessionId == session.id }
            val runtime = turn?.let { storage.turnRuntimeRecords.find(it.id) }
            val provider = runtime?.providerId ?: session.providerId ?: return@withContext null
            val model = runtime?.modelId ?: session.modelId ?: return@withContext null
            MobileUseScreenTarget(session.id, providers.storedConfig(provider), model)
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
                    val provider = session.providerId
                    val model = session.modelId
                    provider != null && model != null && visionCapabilities.available(provider, model)
                }
            },
            turnVisionAvailable = { sessionId, turnId ->
                runBlocking {
                    val runtime = storage.turnRuntimeRecords.find(turnId)
                    val session = storage.sessions.resolve(sessionId)
                    val provider = runtime?.providerId ?: session.providerId
                    val model = runtime?.modelId ?: session.modelId
                    provider != null && model != null && visionCapabilities.available(provider, model)
                }
            },
        )
}

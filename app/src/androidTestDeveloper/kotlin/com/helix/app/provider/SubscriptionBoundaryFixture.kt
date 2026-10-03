package com.helix.app.provider

import com.helix.app.HelixApplication
import com.helix.app.chat.AttachmentStagingSupport
import com.helix.app.chat.ChatService
import com.helix.app.internal.InMemoryLineStore
import com.helix.feature.files.AttachmentImporter
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import java.util.UUID

/** Only account readiness is synthetic; ChatService and the offline private Runtime stay real. */
internal class SubscriptionBoundaryFixture(
    app: HelixApplication,
    platforms: List<String>,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    val status = ProviderTestStatusStore(InMemoryLineStore())
    private val account = ManagedAccountSnapshot(ManagedAccountSnapshot.State.LOGGED_IN, UUID.randomUUID().toString())
    val providers =
        ProviderService(
            storage = app.appContainer.storage,
            factory =
                ProviderFactory(
                    credentials = { error("Offline subscription fixture must not resolve credentials") },
                    wire =
                        object : WireClient {
                            override suspend fun open(request: WireRequest): WireResponse {
                                error("Unexpected external request")
                            }
                        },
                    imageSource = { error("No image fixture") },
                    additionalFactory = { SubscriptionProviderModule.create(app, it, null) },
                ),
            testStatus = status,
            idGenerator = { UUID.randomUUID().toString() },
            managed =
                ManagedProviderHooks(
                    providerIds = platforms,
                    isManaged = platforms::contains,
                    accounts = { platforms.associateWith { account } },
                ),
            scope = scope,
        )
    val chat =
        ChatService(
            storage = app.appContainer.storage,
            providerService = providers,
            profileStore = app.appContainer.profileStore,
            toolPipeline = app.appContainer.toolPipeline,
            idGenerator = { UUID.randomUUID().toString() },
            scope = scope,
            attachmentStaging =
                AttachmentStagingSupport(
                    AttachmentImporter(app.appContainer.featureFiles.importPipeline),
                    "app",
                    { error("No attachment fixture") },
                    { error("No attachment fixture") },
                ),
            subscriptionRecovery = { turn, call, stop ->
                SubscriptionProviderModule.inspectInterruptedJob(app, app.appContainer.storage, turn, call, stop)
            },
            subscriptionResultRecovery = { turn, call, localOnly ->
                SubscriptionProviderModule.recoverInterruptedResult(
                    app,
                    app.appContainer.storage,
                    turn,
                    call,
                    localOnly,
                )
            },
        )

    suspend fun close() = job.cancelAndJoin()
}

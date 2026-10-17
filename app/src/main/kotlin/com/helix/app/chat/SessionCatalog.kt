package com.helix.app.chat

import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.SessionRunControlStore
import com.helix.core.agent.RunControlConfig
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Session metadata persistence. No execution lease, pending input or approval owner. */
@Suppress("LongParameterList") // Exact existing persistence/workspace identities; no new execution context.
internal class SessionCatalog(
    private val storage: HelixStorage,
    private val providers: ProviderService,
    private val controls: SessionRunControlStore,
    private val workspaceRecovery: SessionWorkspaceRecovery,
    private val clock: Clock,
    private val ids: () -> String,
    private val bindDirectory: (String) -> String,
) {
    fun persistDraft(draft: SessionDraft) =
        storage.withTransaction {
            val row = draft.session
            val directory = workspaceRecovery.availableDirectory(row.directoryRef)
            storage.sessions.create(row.id, row.title, row.providerId, row.modelId, row.createdAt, directory)
            if (row.directoryRef != null && directory == null) workspaceRecovery.record(row.id, row.createdAt)
            controls.set(row.id, draft.control, row.createdAt)
        }

    fun rename(
        id: String,
        title: String,
    ) {
        require(title.isNotBlank() && title.length <= 200 && '\u0000' !in title)
        storage.withTransaction {
            val row = storage.sessions.resolve(id)
            storage.sessions.updateDetails(id, title, row.directoryRef)
        }
    }

    fun create(
        title: String,
        providerId: String?,
        modelId: String?,
    ): String {
        require((providerId == null) == (modelId == null))
        if (providerId != null) {
            require(providers.chatSelectable(providerId)) {
                "the provider must pass a connection test before a session can use it"
            }
        }
        val id = ids()
        val now = clock.now().toEpochMilli()
        storage.withTransaction {
            storage.sessions.create(id, title, providerId, modelId, now)
            controls.set(id, controls.defaultSnapshot().copy(mode = AgentMode.ACT), now)
        }
        return id
    }

    suspend fun fork(
        sessionId: String,
        messageId: String,
        branchTitle: (String) -> String,
    ): String =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val id = ids()
            val title =
                branchTitle(
                    storage.sessions
                        .resolve(sessionId)
                        .title
                        .take(160),
                )
            val now = clock.now().toEpochMilli()
            storage.withTransaction {
                SessionFork(
                    storage,
                    bindDirectory,
                ).create(sessionId, messageId, id, title, now) { context.ensureActive() }
                controls.set(id, controls.defaultSnapshot().copy(mode = AgentMode.ACT), now)
            }
            id
        }

    fun setArchived(
        id: String,
        archived: Boolean,
    ) {
        if (archived) storage.sessions.archive(id, clock.now().toEpochMilli()) else storage.sessions.restore(id)
    }

    /** Caller already revalidated the original input/session identity under the application gate. */
    fun bindProvider(
        sessionId: String,
        providerId: String,
        modelId: String,
    ) = storage.sessions.bindProvider(sessionId, providerId, modelId)

    /** Canonical bound reference comes from the original user action's workspace resolver. */
    fun setDirectory(
        id: String,
        bound: String?,
    ): Boolean {
        var updated = false
        storage.withTransaction {
            val row = storage.sessions.find(id) ?: return@withTransaction
            storage.sessions.updateDetails(row.id, row.title, bound)
            val binding = storage.workspaces.binding(row.id)
            storage.auditEvents.append(
                ids(),
                row.id,
                "workspace.bound",
                "user",
                buildJsonObject {
                    put("workspaceId", binding?.workspaceId.orEmpty())
                    put("revision", binding?.revision ?: 0L)
                }.toString(),
                clock.now().toEpochMilli(),
            )
            updated = true
        }
        return updated
    }

    /** Caller retains turnGate, the original input identity and Dispatcher authorization revalidation. */
    fun persistSettings(
        sessionId: String,
        providerId: String,
        modelId: String,
        next: RunControlConfig,
        call: ExecutableToolCall?,
    ): Boolean {
        var saved = false
        storage.withTransaction {
            val selected =
                if (call == null) {
                    storage.sessions.selectModel(sessionId, providerId, modelId)
                    true
                } else {
                    storage.sessions.selectFutureModel(
                        sessionId,
                        providerId,
                        modelId,
                        requireNotNull(call.turnId),
                        call.toolCallId,
                    )
                }
            if (selected) {
                controls.set(sessionId, next, clock.now().toEpochMilli())
                saved = true
            }
        }
        return saved
    }
}

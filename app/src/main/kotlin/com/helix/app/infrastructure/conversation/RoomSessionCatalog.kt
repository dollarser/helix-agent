package com.helix.app.infrastructure.conversation

import com.helix.app.chat.SessionFork
import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.SessionRunControlStore
import com.helix.core.agent.RunControlConfig
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.ConversationSessionSelection
import com.helix.feature.conversation.application.ConversationWorkspace
import com.helix.feature.conversation.application.DraftProject
import com.helix.feature.conversation.application.SessionCatalog
import com.helix.feature.conversation.application.SessionDraft
import com.helix.feature.conversation.application.SessionSettingsAuthority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Session metadata persistence. No execution lease, pending input or approval owner. */
@Suppress("LongParameterList", "TooManyFunctions") // One session metadata aggregate; no execution ownership.
internal class RoomSessionCatalog(
    private val storage: HelixStorage,
    private val providers: ProviderService,
    private val controls: SessionRunControlStore,
    private val workspaceRecovery: ConversationWorkspace,
    private val clock: Clock,
    private val ids: () -> String,
    private val bindDirectory: (String) -> String,
) : SessionCatalog {
    override fun selection(sessionId: String): ConversationSessionSelection? =
        storage.sessions.find(sessionId)?.let {
            ConversationSessionSelection(it.id, it.providerId, it.modelId, it.archivedAt != null)
        }

    override fun projectId(sessionId: String): String? = storage.projects.forSession(sessionId)?.id

    override fun nameUntitled(
        sessionId: String,
        title: String,
    ) {
        storage.sessions.nameUntitled(sessionId, title)
    }

    override fun ensureWorkspace(sessionId: String) {
        if (isNewConversation(sessionId) && storage.projects.forSession(sessionId) != null) {
            bindDirectory(requireNotNull(storage.sessions.resolve(sessionId).directoryRef))
        } else {
            workspaceRecovery.recover(sessionId, clock.now().toEpochMilli())
        }
    }

    override fun persistDraft(draft: SessionDraft): Unit =
        storage.withTransaction {
            val row = draft.session
            validateProject(draft.project)
            val directory =
                if (draft.project == null) {
                    workspaceRecovery.availableDirectory(row.directoryRef)
                } else {
                    row.directoryRef?.let(bindDirectory)
                }
            // Match the existing session FK deletion contract: metadata remains editable, while
            // the retained model identifies a deleted source and still blocks generation.
            val providerId = row.providerId?.takeIf { storage.providerConfigs.find(it) != null }
            storage.sessions.create(row.id, row.title, providerId, row.modelId, row.createdAt, directory)
            if (row.directoryRef != null && directory == null) workspaceRecovery.record(row.id, row.createdAt)
            draft.project?.let { storage.projects.assign(row.id, it.id) }
            controls.set(row.id, draft.control, row.createdAt)
            Unit
        }

    override fun projectSelection(
        id: String?,
        validateDirectory: Boolean,
    ): DraftProject? =
        id?.let {
            val project = requireNotNull(storage.projects.find(it)) { "PROJECT_MISSING" }
            check(project.archivedAt == null) { "PROJECT_ARCHIVED" }
            val reference =
                com.helix.core.workspace
                    .FileScopePath(
                        project.workspaceId,
                        project.relativePath,
                    ).toModelReference()
            val directory = if (validateDirectory) bindDirectory(reference) else reference
            DraftProject(project.id, project.revision, directory)
        }

    private fun validateProject(selection: DraftProject?) {
        if (selection == null) return
        val current = requireNotNull(storage.projects.find(selection.id)) { "PROJECT_MISSING" }
        check(current.archivedAt == null) { "PROJECT_ARCHIVED" }
        check(current.revision == selection.revision) { "PROJECT_CHANGED" }
    }

    override fun isNewConversation(id: String): Boolean =
        storage.messages.recentBySession(id, 1).isEmpty() && storage.turns.listBySession(id).isEmpty()

    /** Unsent cached metadata is still a new conversation; accepted input freezes that distinction. */
    override fun selectProject(
        id: String,
        selection: DraftProject?,
    ) = storage.withTransaction {
        validateProject(selection)
        val row = storage.sessions.resolve(id)
        val isNew = isNewConversation(id)
        storage.projects.assign(id, selection?.id)
        if (isNew) storage.sessions.updateDetails(id, row.title, selection?.directory)
    }

    override fun rename(
        id: String,
        title: String,
    ) {
        require(title.isNotBlank() && title.length <= 200 && '\u0000' !in title)
        require(storage.sessionAccess.userRename(id, title)) { "Session metadata changed" }
    }

    override fun create(
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

    override suspend fun fork(
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

    override fun setArchived(
        id: String,
        archived: Boolean,
    ) {
        require(
            storage.sessionAccess.userSetArchived(id, archived, clock.now().toEpochMilli()),
        ) { "Session metadata changed" }
    }

    /** Caller already revalidated the original input/session identity under the application gate. */
    override fun bindProvider(
        sessionId: String,
        providerId: String,
        modelId: String,
    ) = storage.sessions.bindProvider(sessionId, providerId, modelId)

    /** Canonical bound reference comes from the original user action's workspace resolver. */
    override fun setDirectory(
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
    override fun persistSettings(
        sessionId: String,
        providerId: String,
        modelId: String,
        next: RunControlConfig,
        call: SessionSettingsAuthority?,
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

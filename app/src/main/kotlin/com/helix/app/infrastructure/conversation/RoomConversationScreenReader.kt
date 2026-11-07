package com.helix.app.infrastructure.conversation

import com.helix.app.agent.ChatContextProjection
import com.helix.app.chat.SessionForkPlan
import com.helix.app.chat.loadTurnRecoverySources
import com.helix.app.provider.ProviderService
import com.helix.app.todo.TaskLedgerProjection
import com.helix.core.agent.RunControlConfig
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.ConversationScreenReader
import com.helix.feature.conversation.application.ConversationScreenRequest
import com.helix.feature.conversation.application.ConversationScreenSnapshot
import com.helix.feature.conversation.application.ConversationSessionSnapshot
import com.helix.feature.conversation.application.ConversationWorkspace
import com.helix.feature.conversation.application.recovery.RecoveryObservationStore
import com.helix.feature.conversation.presentation.SessionModelSelectionSnapshot

/** All durable display facts are copied out before the feature's StateFlow CAS can retry. */
internal class RoomConversationScreenReader(
    private val storage: HelixStorage,
    private val history: RoomConversationHistoryReader,
    private val providers: ProviderService,
    private val defaultRunControl: () -> RunControlConfig,
    private val workspace: ConversationWorkspace,
    private val recovery: RecoveryObservationStore,
) : ConversationScreenReader {
    override fun read(request: ConversationScreenRequest): ConversationScreenSnapshot {
        lateinit var snapshot: ConversationScreenSnapshot
        storage.withTransaction {
            val historySnapshot = history.readWithinSnapshot(request.history)
            val session = historySnapshot.sessionId?.let(storage.sessions::find)
            if (session == null) {
                snapshot = ConversationScreenSnapshot(request, historySnapshot, null)
                return@withTransaction
            }
            val lastTurn = storage.turns.latestVisible(session.id)
            val retryTarget = history.retryTargetWithinSnapshot(session.id)
            val turnIds = historySnapshot.turnStates.keys + listOfNotNull(lastTurn?.id)
            val turns = storage.turns.byIds(turnIds.toList()).map(history::turnWithinSnapshot)
            val control = storage.sessionRunControls.forSession(session.id)
            snapshot =
                ConversationScreenSnapshot(
                    request = request,
                    history = historySnapshot,
                    session =
                        ConversationSessionSnapshot(
                            id = session.id,
                            title = session.title,
                            directoryRef = session.directoryRef,
                            projectId = storage.projects.forSession(session.id)?.id,
                            isNewConversation =
                                lastTurn == null && storage.messages.recentBySession(session.id, 1).isEmpty(),
                            workspaceRecovered = workspace.hasNotice(session.id),
                            selectedModel = history.selectedModel(session.providerId, session.modelId),
                            modelBindingIssue = providers.bindings.project(session.providerId, session.modelId),
                            modelSelection =
                                SessionModelSelectionSnapshot(
                                    session.providerId,
                                    session.modelId,
                                    control?.reasoning ?: defaultRunControl().reasoning,
                                ),
                            contextUsage = ChatContextProjection.read(storage, session.id, providers),
                            taskLedger = TaskLedgerProjection.forSession(storage, session.id),
                            hasGoals = storage.goalControls.bySession(session.id).isNotEmpty(),
                            isFork =
                                storage.messages.latestOfKind(
                                    session.id,
                                    SessionForkPlan.KIND,
                                    includeSuperseded = true,
                                ) !=
                                    null,
                        ),
                    activeTurn = lastTurn?.let { last -> turns.single { it.id == last.id } },
                    turns = turns,
                    retryTargetTurnId = retryTarget,
                    subscriptionRecoveries = recovery.interruptedSubscriptions(session.id),
                    recoverySources = loadTurnRecoverySources(storage, session.id, includeTurnId = retryTarget),
                )
        }
        // Deliberately no catch: a failed read cannot become a missing-session/navigation fact.
        return snapshot
    }
}

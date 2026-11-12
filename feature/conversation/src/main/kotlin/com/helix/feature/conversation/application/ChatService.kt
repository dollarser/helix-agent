package com.helix.feature.conversation.application

import android.util.Log
import com.helix.core.agent.AgentLoop
import com.helix.core.agent.AgentRuntime
import com.helix.core.agent.AttachmentBindingIntent
import com.helix.core.agent.BufferedModelToolCall
import com.helix.core.agent.ConversationReferenceIntent
import com.helix.core.agent.LocalToolCallBatch
import com.helix.core.agent.MAX_MODEL_TEXT_CHARS
import com.helix.core.agent.ModelStreamState
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SettledCall
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.agent.TurnContextAssembler
import com.helix.core.agent.TurnContextRequest
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.agent.TurnStartBlocked
import com.helix.core.agent.TurnSubmissionBlock
import com.helix.core.agent.TurnSubmissionResult
import com.helix.core.agent.runtime.TurnCancelSignal
import com.helix.core.agent.runtime.TurnEngine
import com.helix.core.agent.runtime.TurnExecutionHooks
import com.helix.core.agent.runtime.TurnObservation
import com.helix.core.agent.runtime.TurnTerminalProjection
import com.helix.core.agent.turnIdOrNull
import com.helix.core.model.AgentMode
import com.helix.core.model.AttachmentPurpose
import com.helix.core.model.Clock
import com.helix.core.model.ErrorCode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalId
import com.helix.core.model.ModelEvent
import com.helix.core.model.PlanExecutionBinding
import com.helix.core.model.ProviderId
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionId
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.model.attachment.AttachmentMaterialization
import com.helix.core.model.attachment.AttachmentSendDecision
import com.helix.core.model.input.ConversationReferenceKind
import com.helix.core.model.input.ConversationReferenceSnapshotInput
import com.helix.core.model.input.InputAttachment
import com.helix.core.model.input.InputAttachmentLimits
import com.helix.core.model.input.InputRecoveryStore
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.model.input.SessionInputSpec
import com.helix.core.policy.DiagnosticRedaction
import com.helix.core.policy.ForbiddenContentGuard
import com.helix.core.workspace.FileScopePath
import com.helix.feature.conversation.R
import com.helix.feature.conversation.application.ComposerInputState
import com.helix.feature.conversation.application.ConversationHistoryReader
import com.helix.feature.conversation.application.ConversationInputCache
import com.helix.feature.conversation.application.ConversationLaunchStore
import com.helix.feature.conversation.application.ConversationLaunchTarget
import com.helix.feature.conversation.application.ConversationMessageEditor
import com.helix.feature.conversation.application.ConversationProviders
import com.helix.feature.conversation.application.ConversationRunControls
import com.helix.feature.conversation.application.ConversationScreenReader
import com.helix.feature.conversation.application.ConversationScreenRequest
import com.helix.feature.conversation.application.ConversationSessionSelection
import com.helix.feature.conversation.application.ConversationSubmissionHistory
import com.helix.feature.conversation.application.ConversationTools
import com.helix.feature.conversation.application.ConversationTurnHistory
import com.helix.feature.conversation.application.DashboardRefreshPort
import com.helix.feature.conversation.application.DraftAttachment
import com.helix.feature.conversation.application.DraftProject
import com.helix.feature.conversation.application.DraftSession
import com.helix.feature.conversation.application.EgressDisclosure
import com.helix.feature.conversation.application.HumanHandoffPort
import com.helix.feature.conversation.application.MessageEditResult
import com.helix.feature.conversation.application.MessageEditSnapshot
import com.helix.feature.conversation.application.MessageRevisionAvailability
import com.helix.feature.conversation.application.SessionActionQueue
import com.helix.feature.conversation.application.SessionCatalog
import com.helix.feature.conversation.application.SessionDirectory
import com.helix.feature.conversation.application.SessionDirectoryService
import com.helix.feature.conversation.application.SessionDraft
import com.helix.feature.conversation.application.SessionSettingsAuthority
import com.helix.feature.conversation.application.SettingsExecutionAuthority
import com.helix.feature.conversation.application.attachment.AttachmentContext
import com.helix.feature.conversation.application.attachment.AttachmentContextBlock
import com.helix.feature.conversation.application.attachment.AttachmentPreparation
import com.helix.feature.conversation.application.attachment.AttachmentRetryCheck
import com.helix.feature.conversation.application.attachment.AttachmentSendAdmission
import com.helix.feature.conversation.application.attachment.FeatureAttachmentPort
import com.helix.feature.conversation.application.attachment.StagedAttachmentCandidate
import com.helix.feature.conversation.application.automaticSessionTitle
import com.helix.feature.conversation.application.goals.ConversationGoalRuntime
import com.helix.feature.conversation.application.goals.ConversationGoals
import com.helix.feature.conversation.application.goals.ConversationPlans
import com.helix.feature.conversation.application.goals.DetachedGoalBudget
import com.helix.feature.conversation.application.goals.PlanReview
import com.helix.feature.conversation.application.input.AcceptedInputDisclosure
import com.helix.feature.conversation.application.input.AcceptedInputReceipt
import com.helix.feature.conversation.application.input.ConversationInputQueue
import com.helix.feature.conversation.application.input.InputAcceptance
import com.helix.feature.conversation.application.input.InputDeliveryKey
import com.helix.feature.conversation.application.input.SessionInputBinding
import com.helix.feature.conversation.application.input.SessionInputCommands
import com.helix.feature.conversation.application.input.SessionInputDeliveryCoordinator
import com.helix.feature.conversation.application.recovery.AutomaticInputRecovery
import com.helix.feature.conversation.application.recovery.AutomaticTurnRecovery
import com.helix.feature.conversation.application.recovery.ChatRecoveryActions
import com.helix.feature.conversation.application.recovery.ProotRecoveryPort
import com.helix.feature.conversation.application.recovery.RecoveryObservationStore
import com.helix.feature.conversation.application.recovery.SessionWorkScheduler
import com.helix.feature.conversation.application.recovery.SessionWorkStore
import com.helix.feature.conversation.application.recovery.TurnRecoveryActions
import com.helix.feature.conversation.application.recovery.TurnRecoveryStore
import com.helix.feature.conversation.application.recovery.subscriptionRecoveriesFor
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.feature.conversation.input.ChatSubmissionReceipt
import com.helix.feature.conversation.input.ComposerEditClock
import com.helix.feature.conversation.input.PendingInputView
import com.helix.feature.conversation.presentation.ChatContextUsage
import com.helix.feature.conversation.presentation.ChatImageRef
import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.feature.conversation.presentation.ChatStatusLabels
import com.helix.feature.conversation.presentation.ChatToolPresentation
import com.helix.feature.conversation.presentation.DisplayExecutionUi
import com.helix.feature.conversation.presentation.HumanHandoffState
import com.helix.feature.conversation.presentation.HumanHandoffUi
import com.helix.feature.conversation.presentation.MessageUi
import com.helix.feature.conversation.presentation.PendingAttachmentUi
import com.helix.feature.conversation.presentation.ProviderBindingIssue
import com.helix.feature.conversation.presentation.SessionModelSelectionResult
import com.helix.feature.conversation.presentation.SessionModelSelectionSnapshot
import com.helix.feature.conversation.presentation.SessionRowUi
import com.helix.feature.conversation.presentation.SessionSearchUiState
import com.helix.feature.conversation.presentation.ToolInspectionContent
import com.helix.feature.conversation.presentation.TurnRecoverySource
import com.helix.feature.conversation.presentation.TurnUi
import com.helix.feature.conversation.presentation.clearAcceptedSubmissionBlock
import com.helix.feature.conversation.presentation.conversationScreenProjection
import com.helix.feature.conversation.presentation.hasSelectedConversationModel
import com.helix.feature.conversation.presentation.mergeDisplayExecution
import com.helix.feature.conversation.presentation.recoveryPanelsFor
import com.helix.feature.conversation.presentation.selectionRejection
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.SubscriptionRecoveredOutput
import com.helix.provider.api.SubscriptionRecoveryStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.jvm.Volatile
import com.helix.feature.conversation.R as ConversationR

/**
 * Session application facade: UI actions retain the original admission, receipt and
 * authorization gates. TurnEngine owns live/durable execution and the sole Core loop.
 * Directory, composer input and dashboard collaborators each own their projections;
 * this facade never creates a second queue, execution registry or persistence authority.
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
class ChatService(
    private val providers: ConversationProviders,
    val profile: StateFlow<SafetyProfile>,
    private val catalog: SessionCatalog,
    private val historyReader: ConversationHistoryReader,
    private val turnHistory: ConversationTurnHistory,
    private val screenReader: ConversationScreenReader,
    private val directoryService: SessionDirectoryService,
    private val recoveryObservationStore: RecoveryObservationStore,
    private val prootRecovery: ProotRecoveryPort,
    private val inputRecoveryStore: InputRecoveryStore,
    private val turnRecoveryStore: TurnRecoveryStore,
    private val sessionWorkStore: SessionWorkStore,
    private val recoverySources: (String, String?) -> List<TurnRecoverySource>,
    val sessionInputDelivery: SessionInputDeliveryCoordinator,
    private val inputCommands: SessionInputCommands,
    private val inputCache: ConversationInputCache,
    private val submissionHistory: ConversationSubmissionHistory,
    private val messageEditor: ConversationMessageEditor,
    private val acceptedInputDisclosure: AcceptedInputDisclosure,
    private val conversationLaunchStore: ConversationLaunchStore,
    private val clock: Clock,
    private val idGenerator: () -> String,
    scope: CoroutineScope,
    private val attachmentPort: FeatureAttachmentPort,
    private val strings: (Int, Array<out Any>) -> String,
    private val turnEngine: TurnEngine,
    private val sessionRunControls: ConversationRunControls,
    private val goals: ConversationGoals,
    private val goalRuntime: ConversationGoalRuntime,
    private val dashboards: DashboardRefreshPort,
    private val humanHandoff: HumanHandoffPort,
    val planReview: ConversationPlans,
    private val detachedGoalBudget: DetachedGoalBudget,
    private val toolCalls: ConversationTools,
    private val submissionCommands: ConversationTurnCommands,
    private val stopCommands: com.helix.core.agent.runtime.StopTurnCommandHandler,
    val agentRuntime: AgentRuntime,
    private val subscriptionResultRecovery: (String, String, Boolean) -> SubscriptionRecoveredOutput?,
    private val subscriptionRecovery: (String, String, Boolean) -> SubscriptionRecoveryStatus,
    private val bindSessionDirectory: (String) -> String,
    private val conversationReferences: ConversationReferences,
    private val knowledge: ConversationKnowledge,
) : ConversationInputQueue {
    // Observers started in init may refresh immediately on another thread.
    private val inputState = ComposerInputState()
    private val drafts get() = inputState.drafts
    private val _runControl = MutableStateFlow(sessionRunControls.defaultSnapshot())
    private val labels = ChatStatusLabels(strings)
    private val projection = conversationScreenProjection(strings)
    private val workScope = scope
    private val sessionActions = SessionActionQueue(workScope)
    val toolPresentation by lazy {
        ChatToolPresentation(
            _screen,
            strings,
            ::refreshBackgroundTasks,
            positionForCall = historyReader::positionForCall,
        )
    }
    val recovery by lazy {
        ChatRecoveryActions(
            recoveryObservationStore,
            prootRecovery,
            workScope,
            _screen,
            subscriptionRecovery,
            subscriptionResultRecovery,
            onObservationPaused = { Log.w("RuntimeCollection", it) },
        )
    }

    /** Resolves a string-resource id (+ optional format args) to the current locale (HXA-069). */
    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    private fun terminalLabel(
        state: TurnState,
        errorCode: String?,
        turnId: String,
    ): String? =
        if (state == TurnState.FAILED && errorCode != null) {
            historyReader.turn(turnId)?.errorLabel ?: labels.terminalLabel(state, errorCode)
        } else {
            labels.terminalLabel(state, errorCode)
        }

    private fun egressRejectedLabel(code: String): String = labels.egressRejectedLabel(code)

    val directory: SessionDirectory get() = directoryService
    val sessionSearch: StateFlow<SessionSearchUiState> get() = directory.search

    private val conversationViews =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<Any>()

    val conversationVisible: Boolean get() = conversationViews.isNotEmpty()

    /** A resumed screen owns only its display lease; closing it never stops a task. */
    fun showConversation(): AutoCloseable {
        val lease = Any()
        conversationViews.add(lease)
        return AutoCloseable { conversationViews.remove(lease) }
    }

    val foregroundTransportState: StateFlow<TurnState?> get() = dashboards.foregroundTransport

    private val _screen = MutableStateFlow(EMPTY_SCREEN)

    /** HXA-204 slice 2: the panel operations — each keeps its own identity and admission. */
    private val turnRecovery =
        TurnRecoveryActions(
            recoverySources,
            prootRecovery.available,
            workScope,
            _screen,
            { sessionId -> historyReader.retryTarget(sessionId) },
            ::continueGoal,
            ::retry,
            ::inspectInterruptedProot,
            ::refreshScreen,
        )

    private val reminderGoalState = MutableStateFlow<String?>(null)
    val reminderGoal: StateFlow<String?> = reminderGoalState.asStateFlow()

    /** A notification is a navigation request, never a Continued event or approval. */
    fun openGoalReminder(goalId: String) {
        workScope.launch {
            val sessionId = goals.sessionForGoal(goalId) ?: return@launch
            openSession(sessionId)
            refreshScreen()
            reminderGoalState.value = goalId
        }
    }

    fun dismissGoalReminder() {
        reminderGoalState.value = null
    }

    val sessions: StateFlow<List<SessionRowUi>> get() = directory.sessions
    val screen: StateFlow<ChatScreenState> = _screen.asStateFlow()
    private val displayExecutionState = MutableStateFlow<List<DisplayExecutionUi>>(emptyList())
    val displayExecutions: StateFlow<List<DisplayExecutionUi>> = displayExecutionState.asStateFlow()

    fun publishDisplayExecution(value: DisplayExecutionUi) {
        displayExecutionState.update { previous ->
            mergeDisplayExecution(previous, value)
        }
    }

    /** Current Session config. Each Turn snapshots this again at admission. */
    val runControl: StateFlow<RunControlConfig> = _runControl.asStateFlow()

    fun setMode(mode: AgentMode) = updateSessionRunControl("mode") { it.copy(mode = mode) }

    /** Composer commands finish their session-bound update before the UI clears the command. */
    suspend fun setModeFromComposer(
        sessionId: String,
        mode: AgentMode,
    ): Boolean =
        sessionActions
            .submit {
                submissionGate.withLock {
                    synchronized(turnGate) {
                        if (openSessionId != sessionId || preparingDraft || pendingSend != null) {
                            return@synchronized false
                        }
                        if (turnEngine.liveExecution.hasActive(sessionId) || turnGateHolds(sessionId)) {
                            return@synchronized false
                        }
                        val draft = sessionDraft?.takeIf { it.session.id == sessionId }
                        if (draft != null) {
                            val next = draft.control.copy(mode = mode)
                            drafts.control(sessionId, next)
                            _runControl.value = next
                        } else {
                            val session = catalog.selection(sessionId) ?: return@synchronized false
                            if (session.archived) return@synchronized false
                            val next =
                                sessionRunControls.ensure(sessionId, clock.now().toEpochMilli()).copy(mode = mode)
                            sessionRunControls.set(sessionId, next, clock.now().toEpochMilli())
                            _runControl.value = next
                        }
                        true
                    }
                }
            }.await()

    fun setReasoning(reasoning: ReasoningEffort) =
        updateSessionRunControl("reasoning") { it.copy(reasoning = reasoning) }

    fun setChatToolsEnabled(enabled: Boolean) = updateSessionRunControl("tools") { it.copy(chatToolsEnabled = enabled) }

    /**
     * Selection stays lightweight; bytes are intentionally materialized later at submission.
     * Capture a fixed history manifest; never inject a transcript or a potentially stale summary.
     */
    @Suppress("TooGenericExceptionCaught") // Unavailable selection; propagate cancellation.
    fun referenceKindForSession(
        targetSessionId: String,
        sourceSessionId: String,
    ): kotlinx.coroutines.Deferred<ConversationReferenceKind?> =
        workScope.async {
            try {
                if (targetSessionId == sourceSessionId ||
                    catalog.selection(sourceSessionId) == null
                ) {
                    return@async null
                }
                val kind = ConversationReferenceKind.HISTORY
                conversationReferences.prepare(targetSessionId, sourceSessionId, kind)
                kind
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    suspend fun materializeOpenSession(): String? {
        val id = screen.value.openSessionId ?: return null
        return materializeDraftSession(id)
    }

    fun setTurnBudgets(budgets: TurnBudgets) =
        updateSessionRunControl("budgets") {
            it.copy(budgets = TurnBudgetBounds.validate(budgets))
        }

    /** Future composer inputs only; safe to change while a Turn is executing. */
    suspend fun setImmediateMessages(
        sessionId: String,
        immediate: Boolean,
    ): Boolean {
        if (materializeDraftSession(sessionId) != sessionId) return false
        return sessionActions
            .submit {
                submissionGate.withLock {
                    synchronized(turnGate) {
                        if (openSessionId != sessionId || catalog.selection(sessionId)?.archived != false) {
                            return@synchronized false
                        }
                        val current = sessionRunControls.ensure(sessionId, clock.now().toEpochMilli())
                        val next = current.copy(immediateMessages = immediate)
                        sessionRunControls.set(sessionId, next, clock.now().toEpochMilli())
                        _runControl.value = next
                        true
                    }
                }
            }.await()
    }

    private fun updateSessionRunControl(
        label: String,
        transform: (RunControlConfig) -> RunControlConfig,
    ) {
        val sessionId = openSessionId ?: return
        require(turnEngine.liveExecution.active(sessionId) == null) { "cannot change $label during a turn" }
        sessionActions.submit {
            submissionGate.withLock {
                synchronized(turnGate) {
                    if (openSessionId != sessionId || turnEngine.liveExecution.hasActive(sessionId)) return@synchronized
                    val draft = sessionDraft?.takeIf { it.session.id == sessionId }
                    if (draft != null) {
                        val next = transform(draft.control)
                        drafts.control(sessionId, next)
                        _runControl.value = next
                    } else {
                        val session = catalog.selection(sessionId) ?: return@synchronized
                        if (session.archived) return@synchronized
                        val current = sessionRunControls.ensure(sessionId, clock.now().toEpochMilli())
                        val next = transform(current)
                        sessionRunControls.set(sessionId, next, clock.now().toEpochMilli())
                        _runControl.value = next
                    }
                }
                refreshScreen()
            }
        }
    }

    /** Serializes per-session turn admission (one active turn per session). */
    private val turnGate = turnEngine.executionGate
    private val automaticTurnRecovery by lazy {
        AutomaticTurnRecovery(
            turnRecoveryStore,
            clock,
            { parent, snapshot ->
                val result =
                    submissionCommands.inspectRecovery(
                        parent,
                        snapshot,
                    )
                projectSubmission(requireNotNull(turnHistory.turn(parent)).sessionId, result)
            },
            { Log.w("TurnRecovery", "Automatic recovery failed", it) },
        )
    }

    private fun requestAutomaticRecovery(turnId: String) {
        workScope.launch {
            submissionGate.withLock {
                automaticTurnRecovery.recover(turnId)
            }
            requestSessionDrain(requireNotNull(turnHistory.turn(turnId)).sessionId)
            refreshScreen()
            refreshBackgroundTasks()
        }
    }

    private val sessionWorkScheduler by lazy {
        SessionWorkScheduler(
            store = sessionWorkStore,
            clock = clock,
            scope = workScope,
            submissionGate = submissionGate,
            turnGate = turnGate,
            hasLiveTurn = turnEngine.liveExecution::hasActive,
            goalContinuation = goalRuntime,
            consumeQueueInput = { input ->
                consumeQueueInput(InputDeliveryKey(input.inputId, input.sessionId, input.revision))
            },
            submitContinuation = { command -> agentRuntime.submit(command) },
            refreshProjection = ::refreshScreen,
            reportFailure = { Log.w("SessionWork", "Session drain failed", it) },
            recoverInputs = { session ->
                AutomaticInputRecovery(inputRecoveryStore, clock, { candidate ->
                    sessionInputDelivery.revalidate(
                        InputDeliveryKey(candidate.inputId, candidate.sessionId, candidate.revision),
                        parkFailure = false,
                    ) != null
                }, { str(R.string.session_input_recovery_ended) }).recover(session)
            },
        )
    }

    private data class InputResumeConfirmation(
        val input: AcceptedInputReceipt,
        val target: EgressDisclosure.EgressTarget,
    )

    @Volatile private var pendingInputResume: InputResumeConfirmation? = null

    suspend fun editGoalObjective(
        goalId: String,
        revision: Long,
        objective: String,
    ): Boolean {
        val session = openSessionId ?: return false
        return goals.editGoalObjective(session, goalId, revision, objective)
    }

    fun stopContinuousGoals(systemReason: String? = null) {
        val turns =
            synchronized(turnGate) {
                goalRuntime.stopContinuousGoals()
                turnEngine.liveExecution.activeTurns().map { it.turnId }
            }
        workScope.launch {
            turns.forEach { turn ->
                if (goals.goalForTurn(turn) != null) {
                    stopTask(turn, pause = true, systemReason = systemReason)
                }
            }
            refreshBackgroundTasks()
        }
    }

    private fun revokeGoalIntent(sessionId: String) {
        submissionCommands.revokeGoalIntent(sessionId)
    }

    // Written on the main thread (open/close/cancel), read from the work-scope IO pool
    // (sendNow): atomic visibility keeps fresh opens visible to racing sends, and
    // compare-and-set prevents a stale failed lookup from clearing a newer selection.
    private val screenNavigationGate = Any()
    private val screenReadRevision =
        java.util.concurrent.atomic
            .AtomicLong()
    private val openSession =
        java.util.concurrent.atomic
            .AtomicReference<String?>(null)
    private var openSessionId: String?
        get() = openSession.get()
        set(value) {
            synchronized(screenNavigationGate) {
                screenReadRevision.incrementAndGet()
                openSession.set(value)
            }
        }

    @Volatile
    private var pendingSend: String? = null

    /**
     * ADR-0014 §5 (HXA-049): the egress TARGET the open [pendingSend]'s disclosure was
     * approved against — the exact provider/origin the dialog showed. [confirmSendNow]
     * re-derives the live target and REQUIRES the provider id and origin to be unchanged;
     * a drifted target voids the old confirmation (blocked, re-send — the send must never
     * leave through an origin the user did not approve). Cleared everywhere [pendingSend]
     * is cleared.
     */
    @Volatile
    private var pendingEgress: EgressDisclosure.EgressTarget? = null

    /**
     * ADR-0014 §5 (HXA-049): the EXACT set of staged attachments the open [pendingSend]'s
     * disclosure enumerated — the ordered artifact ids of the staged snapshot the dialog was
     * built from. [confirmSendNow] requires the CURRENT staged set to be IDENTICAL (same ids,
     * same order) before it sends: a file staged after the dialog (even while it was up) or one
     * removed since voids the old confirmation (blocked, re-send), so an attachment that was
     * never shown in the dialog can never leave the device. Cleared everywhere [pendingEgress]
     * is cleared.
     */
    @Volatile
    private var pendingAttachmentIds: List<String> = emptyList()

    /** Immutable other-conversation bytes frozen before the disclosure is shown. */
    @Volatile
    private var pendingReference: ConversationReferenceSnapshotInput? = null

    /**
     * HXA-056: the shared-in TEXT draft awaiting a one-shot composer pre-fill (set by
     * [acceptShareDraft], cleared by [consumeShareDraftText] and whenever the open session
     * changes — a draft belongs to the session it was opened for, never to a later one).
     */
    private val shareDraftText get() = inputState.shareText
    private val stagedAttachments get() = inputState.staged

    /**
     * ADR-0014 §5 「凭据类内容仍拒绝出网」: the credential-shape scanner the attachment
     * gate injects — the same guard that scans the text typed in the box, applied at
     * send/confirm/retry to the FULL content of every (re-)materialized attachment, not
     * just the bounded inline view.
     */
    private val credentialScan: (String) -> String? = ForbiddenContentGuard::reasonFor

    // --- HXA-036: tool pipeline state (cards/dispatch facts); live cancel/timer ownership is TurnEngine. ---

    fun prepareDetachedJobBudget(
        sessionId: String,
        turnId: String,
        executionId: String,
        millis: Long,
    ): Long = detachedGoalBudget.prepare(sessionId, turnId, executionId, millis)

    fun rejectDetachedJobBudget(
        sessionId: String,
        turnId: String,
        executionId: String,
    ) = detachedGoalBudget.reject(sessionId, turnId, executionId)

    fun settleDetachedJobBudget(
        sessionId: String,
        turnId: String,
        executionId: String,
        terminalElapsedMs: Long?,
    ) = detachedGoalBudget.settle(sessionId, turnId, executionId, terminalElapsedMs)

    private val started =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** Observing the provider and directory is application-owned, never an execution lifecycle. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        refreshSessions()
        workScope.launch {
            // Provider rows can arrive after the initial session projection on cold start.
            providers.changes
                .collect { refreshScreen() }
        }
    }

    // --------------------------------------------------------------------------------
    // Session intents
    // --------------------------------------------------------------------------------

    /** Startup recovery commits before this notification; refresh any already-open conversation. */
    fun onRecoveryCompleted() {
        workScope.launch {
            turnHistory.recoveryTurnIds().forEach { turnId ->
                submissionGate.withLock { automaticTurnRecovery.recover(turnId) }
            }
            refreshSessionsNow()
            refreshScreen()
            inputCommands.sessionsWithPending().forEach(::requestSessionDrain)
        }
    }

    /**
     * Thread-safe (Room read on the service's IO scope). The UI and the
     * container init may call this from any thread; the [sessions] StateFlow
     * updates when the read completes.
     */
    fun refreshSessions() = directory.refresh()

    fun refreshReferenceSessions(): kotlinx.coroutines.Deferred<Unit> =
        workScope.async { directoryService.refreshNow() }

    /**
     * Restores the conversation surface chosen by the user on a normal app launch.
     * Explicit share/deep-link handlers run before this and therefore win; an already-open
     * conversation is never replaced by this preference. Missing/archived sessions fail closed
     * to a fresh ephemeral draft instead of exposing the history list as the default surface.
     */
    fun restoreConversationLaunchTarget() {
        if (openSessionId != null || preparingDraft) return
        workScope.launch {
            if (openSessionId != null || preparingDraft) return@launch
            val target = conversationLaunchStore.target()
            val session = (target as? ConversationLaunchTarget.Session)?.let { catalog.selection(it.sessionId) }
            // Resolve Room off-main, then arbitrate with explicit UI navigation in one UI turn.
            withContext(Dispatchers.Main.immediate) {
                if (openSessionId != null || preparingDraft) return@withContext
                if (session != null && !session.archived) {
                    openSession(session.id)
                } else {
                    newSessionDraft()
                }
            }
        }
    }

    private fun refreshSessionsNow() = directoryService.refreshNow()

    fun searchSessions(rawQuery: String) = directory.search(rawQuery)

    fun clearSessionSearch() = directory.clearSearch()

    private val sessionDraft: SessionDraft? get() = drafts.current
    private val preparingDraft: Boolean get() = drafts.preparing

    fun newSessionDraft(): String? = openSessionDraft(null)

    /** Capture admission against the service owner, not an asynchronously refreshed UI snapshot. */
    suspend fun attachFileToConversation(
        uri: String,
        expectedSessionId: String?,
        newConversation: Boolean,
        projectId: String? = null,
        directory: String? = null,
        admit: () -> Boolean = { true },
    ): String? {
        if (openSessionId != expectedSessionId) return null
        val target =
            if (newConversation) {
                newContextDraft(projectId, directory, expectedSessionId, admit)
            } else {
                withContext(Dispatchers.Main.immediate) {
                    expectedSessionId.takeIf { admit() && openSessionId == it }
                }
            }
        target?.let { stageAttachment(uri, it) }
        return target
    }

    /** Validate the location before replacing a draft. A changed destination never retargets the action. */
    suspend fun newDirectoryDraft(
        reference: String,
        expectedSessionId: String?,
        projectId: String? = null,
        admit: () -> Boolean = { true },
    ): String? = newContextDraft(projectId, reference, expectedSessionId, admit)

    suspend fun newProjectDraft(
        projectId: String,
        expectedSessionId: String?,
        admit: () -> Boolean = { true },
    ): String? = newContextDraft(projectId, null, expectedSessionId, admit)

    private suspend fun newContextDraft(
        projectId: String?,
        reference: String?,
        expectedSessionId: String?,
        admit: () -> Boolean = { true },
    ): String? {
        val changed = withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { openSessionId != expectedSessionId }
        if (changed) return null
        val selection = withContext(Dispatchers.IO) { catalog.projectSelection(projectId) }
        val bound = withContext(Dispatchers.IO) { reference?.let(bindSessionDirectory) ?: selection?.directory }
        return withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            if (openSessionId == expectedSessionId && admit()) openSessionDraft(bound, selection) else null
        }
    }

    private fun openSessionDraft(
        directory: String?,
        project: DraftProject? = null,
    ): String? {
        val current = sessions.value.firstOrNull { it.id == openSessionId }
        val draft = sessionDraft?.session
        val (providerId, modelId) =
            conversationLaunchStore.draftModelFor(
                draft?.let { it.providerId to it.modelId } ?: current?.let { it.providerId to it.model },
            )
        val entity =
            DraftSession(
                idGenerator(),
                "",
                providerId.takeIf { modelId != null },
                modelId.takeIf { providerId != null },
                clock.now().toEpochMilli(),
                directoryRef = directory,
            )
        val control = sessionRunControls.defaultSnapshot().copy(mode = AgentMode.ACT)
        if (!drafts.open(entity, control, project)) return null
        openSessionId = entity.id
        _runControl.value = control
        conversationLaunchStore.selectNewDraft()
        clearStagedAttachments()
        clearSessionSearch()
        inputState.share(null)
        workScope.launch { refreshScreen() }
        return entity.id
    }

    /** Selection edits the same identity; it never clears composer text or staged attachments. */
    suspend fun selectConversationProject(
        expectedSessionId: String,
        projectId: String?,
    ): Boolean =
        withContext(workScope.coroutineContext) {
            submissionGate.withLock {
                if (openSessionId != expectedSessionId) return@withLock false
                val draft = sessionDraft?.takeIf { it.session.id == expectedSessionId }
                val currentProject = draft?.project?.id ?: catalog.projectId(expectedSessionId)
                if (currentProject == projectId && draft == null) return@withLock true
                val selection =
                    catalog.projectSelection(
                        projectId,
                        draft != null || catalog.isNewConversation(expectedSessionId),
                    )
                if (currentProject == projectId && draft?.project == selection) return@withLock true
                if (openSessionId != expectedSessionId) return@withLock false
                if (sessionDraft?.session?.id == expectedSessionId) {
                    if (!drafts.project(expectedSessionId, selection)) return@withLock false
                } else {
                    catalog.selectProject(expectedSessionId, selection)
                }
                dismissBlocked()
                refreshScreen()
                true
            }
        }

    suspend fun saveDraftForGoal(text: String): Boolean =
        withContext(workScope.coroutineContext) {
            if (text.isBlank()) return@withContext false
            val sessionId = openSessionId ?: return@withContext false
            val attachments = saveSessionDraft(sessionId) ?: return@withContext false
            catalog.nameUntitled(sessionId, automaticSessionTitle(text))
            attachments.forEach { stageAttachmentNow(it.uri) }
            stagedAttachments.size == attachments.size
        }

    private fun saveSessionDraft(expectedSessionId: String? = openSessionId): List<DraftAttachment>? {
        val attachments =
            drafts.persist(expectedSessionId, catalog::persistDraft) ?: return null
        expectedSessionId?.let { id ->
            catalog.selection(id)?.takeIf { !it.archived }?.let {
                conversationLaunchStore.selectSession(id)
            }
        }
        refreshSessionsNow()
        refreshScreen()
        return attachments
    }

    fun renameSession(
        id: String,
        title: String,
    ) {
        if (title.isBlank() || title.length > 200 || '\u0000' in title) return
        val wasDraft = sessionDraft?.session?.id == id
        workScope.launch {
            if (wasDraft) {
                drafts.rename(id, title)
                refreshScreen()
                return@launch
            }
            catalog.rename(id, title)
            refreshSessionsNow()
            refreshScreen()
        }
    }

    fun setSessionDirectory(
        reference: String?,
        targetSessionId: String? = screen.value.openSessionId,
    ): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            if (targetSessionId == null) return@async false
            try {
                val bound = reference?.let(bindSessionDirectory)
                val draft = sessionDraft
                if (draft != null && draft.session.id == targetSessionId) {
                    if (!drafts.directory(draft.session.id, bound)) return@async false
                } else {
                    if (!catalog.setDirectory(targetSessionId, bound)) return@async false
                    refreshSessionsNow()
                }
                if (openSessionId == targetSessionId &&
                    screen.value.blockedReason == str(R.string.chat_directory_failed)
                ) {
                    dismissBlocked()
                }
                refreshScreen()
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (openSessionId == targetSessionId) setBlocked(str(R.string.chat_directory_failed))
                false
            }
        }

    // UI admission fails only if a new private directory also cannot be prepared.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun ensureSessionWorkspace(sessionId: String): Boolean =
        try {
            catalog.ensureWorkspace(sessionId)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (openSessionId == sessionId) setBlocked(str(R.string.chat_directory_failed))
            false
        }

    /**
     * Creates a session bound to a (tested) provider + its model. Runs on the
     * service's IO scope; the UI may call it from any thread.
     */
    suspend fun createSession(
        title: String,
        providerId: String,
        modelId: String,
    ): String =
        withContext(workScope.coroutineContext) {
            // Explicit user target, not an expansion of the selectable model catalog.
            val id = catalog.create(title, providerId, modelId)
            refreshSessionsNow()
            id
        }

    private val forkBusy =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** User-only history branching. A fresh session uses the current new-session authorization default. */
    suspend fun forkSession(
        sessionId: String,
        messageId: String,
    ): String =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val id = catalog.fork(sessionId, messageId) { str(R.string.session_fork_title, it) }
            refreshSessionsNow()
            id
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // UI boundary: safe failure, never exception bodies.
    fun forkFromMessage(messageId: String) {
        val source = openSessionId ?: return
        if (!forkBusy.compareAndSet(false, true)) return
        workScope.launch {
            try {
                val id = forkSession(source, messageId)
                if (openSessionId == source) {
                    dismissBlocked()
                    openSession(id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (openSessionId == source) setBlocked(str(R.string.session_fork_failed))
            } finally {
                forkBusy.set(false)
            }
        }
    }

    @Suppress("SwallowedException") // archive race (already archived/gone): the persisted state is the truth
    fun archiveSession(id: String) {
        workScope.launch {
            try {
                catalog.setArchived(id, true)
                refreshSessionsNow()
            } catch (e: IllegalArgumentException) {
                // Already archived or gone (race with the UI); the next
                // refresh shows the persisted state — nothing to show.
            }
        }
    }

    fun restoreSession(id: String) {
        workScope.launch {
            catalog.setArchived(id, false)
            refreshSessionsNow()
            refreshScreen()
        }
    }

    /** Opens a session: loads its persisted messages and selected model identity. */
    fun openSession(id: String) {
        val openingSearchQuery =
            sessionSearch.value
                .takeIf { state ->
                    state.hits.any {
                        it.sessionId == id &&
                            it.matchesMessage
                    }
                }?.query
        dismissGoalReminder()
        if (preparingDraft) return
        cancelPendingSend()
        drafts.clear()
        openSessionId = id
        conversationLaunchStore.selectSession(id)
        clearStagedAttachments()
        clearSessionSearch()
        inputState.share(null) // a draft pre-fill belongs to the session it opened for (HXA-056)
        workScope.launch {
            if (catalog.selection(id) != null) ensureSessionWorkspace(id)
            synchronized(turnGate) {
                if (openSessionId == id && sessionDraft == null) {
                    // Configuration commands publish under this gate. Do not overwrite them with
                    // a snapshot fetched before asynchronous workspace preparation completed.
                    _runControl.value =
                        if (catalog.selection(id) != null) {
                            sessionRunControls.ensure(id, clock.now().toEpochMilli())
                        } else {
                            sessionRunControls.defaultSnapshot()
                        }
                }
            }
            refreshScreen()
            if (openingSearchQuery != null && openSessionId == id) findHistoryMessage(openingSearchQuery, null, null)
        }
    }

    /**
     * Closes the open session: the chat screen goes back to the session list.
     * An in-flight turn keeps running in this service's scope (the UI holds no
     * network Job); its persisted state is shown when the session reopens.
     */
    fun closeSession() {
        dismissGoalReminder()
        if (preparingDraft) return
        cancelPendingSend()
        drafts.clear()
        openSessionId = null
        clearStagedAttachments()
        _runControl.value = sessionRunControls.defaultSnapshot()
        clearSessionSearch()
        inputState.share(null)
        workScope.launch { refreshScreen() }
    }

    /** Fail closed before an irreversible privacy erase; active work must be stopped first. */
    fun preparePermanentDeletion(sessionId: String) {
        synchronized(turnGate) {
            check(turnEngine.liveExecution.active(sessionId) == null) { "SESSION_ACTIVE_STOP_REQUIRED" }
            revokeGoalIntent(sessionId)
        }
        if (openSessionId == sessionId) {
            openSessionId = null
            conversationLaunchStore.selectNewDraft()
            clearStagedAttachments()
            inputState.share(null)
            _runControl.value = sessionRunControls.defaultSnapshot()
        }
    }

    /**
     * The pending list belongs to the session that staged it (ADR-0014 §5: attachments stay
     * local to that session until an explicit send) — switching or closing the session drops
     * it. The imported files themselves remain durable in the workspace `input/` region.
     */
    private fun clearStagedAttachments() {
        inputState.clearStaged()
    }

    /** Dismisses the current user-visible [ChatScreenState.blockedReason] banner. */
    fun dismissBlocked() {
        _screen.update { it.copy(blockedReason = null) }
    }

    // --------------------------------------------------------------------------------
    // Share drafts (HXA-056, ACTION_SEND/SEND_MULTIPLE — local import, NEVER auto-sent)
    // --------------------------------------------------------------------------------

    /**
     * Accepts a share draft from the system share sheet (HXA-056 / PX-06, ADR-0014 §5): creates
     * a dedicated provider-free draft session, opens it, and lands the shared content LOCALLY —
     * [text] becomes the one-shot composer pre-fill ([ChatScreenState.shareDraftText]) and every
     * [imageUris] / [fileUris] reference goes through the EXISTING attachment pipeline
     * (import → closed classification → normalize → stage), so a shared PDF / DOCX / HTML file
     * stages as an extracted-text attachment. Nothing is ever sent: staging and pre-filling are
     * local, and only an explicit [send] after the user's review reaches the model. An empty
     * draft is a no-op. Each share item fails closed individually: one bad item blocks only
     * itself (user-visible reason), the rest still stage.
     *
     * Runs on the work scope; the UI follows [screen] (the draft session opens and its staged
     * attachments appear as they import). Called by the activity for launch intents and
     * `onNewIntent` re-shares.
     */
    fun acceptShareDraft(
        text: String?,
        imageUris: List<String>,
        fileUris: List<String>,
    ) {
        if ((text?.isEmpty() ?: true) && imageUris.isEmpty() && fileUris.isEmpty()) return
        workScope.launch {
            // Preserve the incoming share until the current draft has finished materializing.
            screen.first { !it.preparingDraft }
            // A share intent is an explicit user action aimed at this app: opening the draft
            // session is the expected outcome (any open session's in-memory staging drops,
            // exactly like a manual session switch — ADR-0014 §5).
            if (openSessionId != null) {
                openSessionId = null
                clearStagedAttachments()
                inputState.share(null)
            }
            val id = catalog.create(str(R.string.session_shared_draft), null, null)
            refreshSessionsNow()
            openSession(id)
            (imageUris + fileUris).forEach { uri -> stageAttachmentNow(uri) }
            inputState.share(text)
            refreshScreen()
        }
    }

    /** Trusted library navigation command. Preserves the target composer; never creates a Turn. */
    fun saveReplyAsNote(
        sessionId: String,
        messageId: String,
    ): kotlinx.coroutines.Deferred<Boolean> = workScope.async { knowledge.saveReply(sessionId, messageId) }

    /** Trusted library navigation command. Preserves the target composer; never creates a Turn. */
    fun stageKnowledgeReferences(
        expectedSessionId: String?,
        items: List<com.helix.core.model.input.KnowledgeReference>,
    ): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            try {
                submissionGate.withLock {
                    if (items.isEmpty() || !knowledge.valid(items) ||
                        pendingSend != null
                    ) {
                        return@withLock false
                    }
                    if (openSessionId != expectedSessionId) return@withLock false
                    val id = openSessionId ?: openSessionDraft(null) ?: return@withLock false
                    val old =
                        inputCache.read(id)
                            ?: ChatSubmission(id, 0, idGenerator(), "")
                    val keys = items.map { it.kind to it.resourceId }.toSet()
                    val merged = old.knowledgeReferences.filterNot { (it.kind to it.resourceId) in keys } + items
                    com.helix.core.model.input.KnowledgeReferenceCodec
                        .encode(merged)
                    val next =
                        old.copy(
                            knowledgeReferences = merged,
                            revision = ComposerEditClock.next(old.revision),
                            clientRequestId = idGenerator(),
                        )
                    if (!inputCache.save(next)) return@withLock false
                    refreshScreen()
                    true
                }
            } catch (
                cancel: kotlinx.coroutines.CancellationException,
            ) {
                throw cancel
            } catch (
                _: IllegalArgumentException,
            ) {
                false
            } catch (
                _: IllegalStateException,
            ) {
                false
            } catch (
                _: java.io.IOException,
            ) {
                false
            } catch (_: android.database.sqlite.SQLiteException) {
                false
            }
        }

    /** A trusted skill choice edits only the matching durable composer; it never sends a message. */
    fun stageSkillActivation(
        expectedSessionId: String,
        activation: com.helix.core.model.input.InputSkillActivation,
    ): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            submissionGate.withLock {
                if (openSessionId != expectedSessionId || pendingSend != null) return@withLock false
                val item = activation
                if (!validSkillActivations(expectedSessionId, listOf(item))) return@withLock false
                val old =
                    inputCache.read(expectedSessionId)
                        ?: ChatSubmission(expectedSessionId, 0, idGenerator(), "")
                if (old.revisedMessageId != null) return@withLock false
                val retained =
                    old.skillActivations.filterNot {
                        it.source == item.source && it.name == item.name && it.snapshotHash == item.snapshotHash &&
                            it.ownerId == item.ownerId
                    }
                if (retained.size >= 4) return@withLock false
                val next =
                    old.copy(
                        skillActivations = retained + item,
                        revision = ComposerEditClock.next(old.revision),
                        clientRequestId = idGenerator(),
                    )
                if (!inputCache.save(next)) return@withLock false
                refreshScreen()
                true
            }
        }

    /** One-shot consume: the UI applied [ChatScreenState.shareDraftText] to the composer. */
    fun consumeShareDraftText() {
        inputState.share(null)
        _screen.update { it.copy(shareDraftText = null) }
    }

    /**
     * Binds a connection-tested provider to the OPEN session when it has none (HXA-056 draft
     * sessions): the storage layer only allows the bind on provider-free rows (fail-closed —
     * an already-bound session's egress target is never swapped through this path). A session
     * with a non-terminal turn is not rebound mid-turn.
     */
    fun bindProviderToSession(
        providerId: String,
        modelId: String,
    ) {
        workScope.launch {
            val sessionId = openSessionId ?: return@launch
            if (providers.source(providerId, modelId)?.let {
                    it.chatSelectable && modelId in it.conversationModels
                } != true
            ) {
                setBlocked(str(ConversationR.string.chat_blocked_provider_untested))
                return@launch
            }
            sessionDraft?.takeIf { it.session.id == sessionId }?.let {
                drafts.model(it.session.id, providerId, modelId)
                conversationLaunchStore.selectDraftModel(providerId, modelId)
                refreshScreen()
                return@launch
            }
            if (turnGateHolds(sessionId)) return@launch // a turn in flight owns the session's target
            try {
                catalog.bindProvider(sessionId, providerId, modelId)
                conversationLaunchStore.selectDraftModel(providerId, modelId)
            } catch (_: IllegalArgumentException) {
                // Swallowed deliberately: the IAE message carries the internal session id,
                // and the user-visible label stays the one stable, path-free sentence
                // (ADR-0014 §7: no raw internals in user-visible errors).
                setBlocked(str(R.string.chat_blocked_session_bound))
                return@launch
            }
            refreshScreen()
        }
    }

    /** Enqueue immediately so a subsequent Send cannot overtake the selection. */
    fun selectSessionModel(
        providerId: String,
        modelId: String,
    ) {
        val requestedSession = openSessionId
        val receipt = requestSessionModelSelection(requestedSession, providerId, modelId)
        workScope.launch {
            val result = receipt.await()
            if (result != SessionModelSelectionResult.APPLIED && openSessionId == requestedSession) {
                setBlocked(str(result.messageRes))
            }
        }
    }

    /** A model-management dialog binds its original session and awaits the actual commit. */
    fun requestSessionModelSelection(
        requestedSession: String?,
        providerId: String,
        modelId: String,
        reasoning: ReasoningEffort? = null,
        expected: SessionModelSelectionSnapshot? = null,
    ): kotlinx.coroutines.Deferred<SessionModelSelectionResult> =
        sessionActions.submit {
            val result =
                submissionGate.withLock {
                    commitModelSelection(requestedSession, providerId, modelId, reasoning, expected)
                }
            if (result == SessionModelSelectionResult.APPLIED) {
                refreshSessionsNow()
                refreshScreen()
            }
            result
        }

    private fun modelSelectionBusy(
        sessionId: String?,
        isDraft: Boolean,
    ): Boolean =
        preparingDraft || pendingSend != null ||
            (
                sessionId != null && (
                    turnEngine.liveExecution.hasActive(sessionId) ||
                        (!isDraft && turnGateHolds(sessionId))
                )
            )

    @Suppress("TooGenericExceptionCaught") // A failed local transaction is an explicit receipt, never a success.
    private fun commitModelSelection(
        requestedSession: String?,
        providerId: String,
        modelId: String,
        reasoning: ReasoningEffort?,
        expected: SessionModelSelectionSnapshot?,
    ): SessionModelSelectionResult =
        synchronized(turnGate) {
            val draft = sessionDraft?.takeIf { it.session.id == requestedSession }
            val session =
                draft?.session?.let { ConversationSessionSelection(it.id, it.providerId, it.modelId, false) }
                    ?: requestedSession?.let(catalog::selection)
            val current =
                draft?.control ?: requestedSession?.let(sessionRunControls::forSession)
                    ?: sessionRunControls.defaultSnapshot()
            val actual = SessionModelSelectionSnapshot(session?.providerId, session?.modelId, current.reasoning)
            val nextReasoning = reasoning ?: ReasoningEffort.OFF
            val target = SessionModelSelectionSnapshot(providerId, modelId, nextReasoning)
            val rejection =
                validateModelSelection(
                    requestedSession,
                    actual,
                    target,
                    expected,
                    modelSelectionBusy(requestedSession, draft != null),
                )
            if (rejection != null) return@synchronized rejection
            if (actual == target) return@synchronized SessionModelSelectionResult.APPLIED
            val sessionId = requireNotNull(requestedSession)
            try {
                val next =
                    if (draft != null) {
                        drafts.modelAndReasoning(draft, providerId, modelId, nextReasoning)
                            ?: return@synchronized SessionModelSelectionResult.BUSY
                    } else {
                        current.copy(reasoning = nextReasoning).also {
                            check(catalog.persistSettings(sessionId, providerId, modelId, it, null))
                        }
                    }
                if (openSessionId == sessionId) _runControl.value = next
                conversationLaunchStore.selectDraftModel(providerId, modelId)
                SessionModelSelectionResult.APPLIED
            } catch (_: Exception) {
                SessionModelSelectionResult.FAILED
            }
        }

    private fun validateModelSelection(
        requestedSession: String?,
        actual: SessionModelSelectionSnapshot,
        target: SessionModelSelectionSnapshot,
        expected: SessionModelSelectionSnapshot?,
        busy: Boolean,
    ): SessionModelSelectionResult? {
        val row = providers.source(target.providerId, target.modelId)
        val sameModel = actual.providerId == target.providerId && actual.modelId == target.modelId
        val rejection =
            selectionRejection(
                requestedSession,
                openSessionId,
                busy,
                row != null && (sameModel || row.chatSelectable),
                sameModel || row?.let {
                    target.modelId in it.conversationModels &&
                        target.modelId?.let(it::modelSelectable) == true
                } == true,
            )
        return when {
            expected != null && expected != actual -> SessionModelSelectionResult.SETTINGS_CHANGED

            rejection != null -> rejection

            actual != target && target.reasoning != ReasoningEffort.OFF &&
                target.reasoning !in
                providers.reasoningOptions(
                    requireNotNull(target.providerId),
                    requireNotNull(target.modelId),
                )
            -> SessionModelSelectionResult.SETTINGS_CHANGED

            else -> null
        }
    }

    /** Explicit UI confirmation only; model tool output cannot call this path. */
    @Suppress("CyclomaticComplexMethod") // Atomic revalidation of a user-confirmed multi-field change.
    suspend fun applyUserSettings(
        sessionId: String,
        mode: AgentMode?,
        providerId: String?,
        modelId: String?,
        reasoning: ReasoningEffort?,
    ): Boolean = applySettings(sessionId, mode, providerId, modelId, reasoning, null)

    /** Dispatcher-authorized mutation of future input defaults; never changes the live Turn snapshot. */
    suspend fun applyToolSettings(
        authority: SettingsExecutionAuthority,
        mode: AgentMode?,
        providerId: String?,
        modelId: String?,
        reasoning: ReasoningEffort?,
    ): Boolean = applySettings(authority.sessionId, mode, providerId, modelId, reasoning, authority)

    @Suppress("CyclomaticComplexMethod", "LongParameterList") // Revalidate UI or trusted executor ownership atomically.
    private suspend fun applySettings(
        sessionId: String,
        mode: AgentMode?,
        providerId: String?,
        modelId: String?,
        reasoning: ReasoningEffort?,
        call: SettingsExecutionAuthority?,
    ): Boolean =
        sessionActions
            .submit {
                submissionGate.withLock {
                    val applied =
                        synchronized(turnGate) {
                            if (!settingsChangeAdmitted(sessionId, call)) return@synchronized false
                            val session = catalog.selection(sessionId) ?: return@synchronized false
                            if (session.archived) return@synchronized false
                            val targetProvider = providerId ?: session.providerId ?: return@synchronized false
                            val targetModel = modelId ?: session.modelId ?: return@synchronized false
                            val row =
                                providers.source(targetProvider, targetModel)?.takeIf { it.chatSelectable }
                                    ?: return@synchronized false
                            val modelChanged = targetProvider != session.providerId || targetModel != session.modelId
                            val canSelect = targetModel in row.conversationModels && row.modelSelectable(targetModel)
                            if (modelChanged && !canSelect) {
                                return@synchronized false
                            }
                            val efforts = providers.reasoningOptions(targetProvider, targetModel)
                            if (reasoning != null && reasoning != ReasoningEffort.OFF && reasoning !in efforts) {
                                return@synchronized false
                            }
                            val current = sessionRunControls.ensure(sessionId, clock.now().toEpochMilli())
                            val nextReasoning =
                                when {
                                    reasoning != null -> reasoning
                                    modelChanged -> ReasoningEffort.OFF
                                    else -> current.reasoning
                                }
                            val next = current.copy(mode = mode ?: current.mode, reasoning = nextReasoning)
                            val stopped =
                                call?.let { it.isStopped(clock.now()) } ?: false
                            if (stopped) {
                                return@synchronized false
                            }
                            persistSettings(sessionId, targetProvider, targetModel, next, call)
                        }
                    if (applied) {
                        refreshSessionsNow()
                        refreshScreen()
                    }
                    applied
                }
            }.await()

    /** Caller owns turnGate; model defaults and controls commit together without mutating the active snapshot. */
    private fun persistSettings(
        sessionId: String,
        providerId: String,
        modelId: String,
        next: RunControlConfig,
        call: SettingsExecutionAuthority?,
    ): Boolean {
        val saved =
            catalog.persistSettings(
                sessionId,
                providerId,
                modelId,
                next,
                call?.let {
                    SessionSettingsAuthority(requireNotNull(it.turnId), it.toolCallId)
                },
            )
        if (saved && call == null) conversationLaunchStore.selectDraftModel(providerId, modelId)
        if (saved && openSessionId == sessionId) _runControl.value = next
        return saved
    }

    private fun settingsChangeAdmitted(
        sessionId: String,
        call: SettingsExecutionAuthority?,
    ): Boolean {
        if (pendingSend != null || preparingDraft) return false
        val active = turnEngine.liveExecution.active(sessionId)
        return if (call == null) {
            openSessionId == sessionId && active == null && !turnGateHolds(sessionId)
        } else {
            val turnId = call.turnId
            call.sessionId == sessionId && call.toolName == "settings.apply" && turnId != null &&
                active?.turnId == turnId && !inputCommands.hasPending(sessionId) &&
                !call.isStopped(clock.now()) && turnHistory.turn(turnId)?.state != TurnState.CANCELLING
        }
    }

    /** True when the session's newest turn has not terminalized (the target must be stable). */
    private fun turnGateHolds(sessionId: String): Boolean =
        turnHistory.latest(sessionId)?.state?.let { !it.isTerminal && it != TurnState.NEEDS_REVIEW } ?: false

    // --------------------------------------------------------------------------------
    // Attachment staging (HXA-049, ADR-0014 — pick / import / stage NEVER sends)
    // --------------------------------------------------------------------------------

    /**
     * Stages one picked document as a chat attachment (ADR-0014 §5: no auto-send). Runs the
     * EXISTING one-time private SAF import into the open session's workspace (`input/
     * attachments/<id>/`), verifies the result is a first-batch UTF-8 text attachment,
     * registers the artifact snapshot and adds it to the in-memory pending list. On ANY
     * refusal / unsupported type / error the user sees a blocked reason and nothing is
     * staged, nothing is sent. Runs on the work scope; the visible outcome arrives via
     * [screen]. Staging is NOT a send: only [send]/[confirmSend] reach the model.
     */
    fun stageAttachment(
        uri: String,
        expectedSessionId: String? = openSessionId,
    ) {
        workScope.launch { stageAttachmentNow(uri, expectedSessionId) }
    }

    @Suppress("ReturnCount")
    private suspend fun stageAttachmentNow(
        uri: String,
        expectedSessionId: String? = openSessionId,
    ) {
        val sessionId = expectedSessionId
        if (openSessionId != sessionId) return
        if (sessionId == null) {
            setBlocked(str(R.string.chat_blocked_no_open_session))
            return
        }
        // Fast-fail only. The composer owner enforces the cap again when the import completes.
        val attachmentCount = sessionDraft?.attachments?.size ?: stagedAttachments.size
        if (attachmentCount >= InputAttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE) {
            setBlocked(str(R.string.chat_blocked_max_attachments, InputAttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE))
            return
        }
        val reported = attachmentMetadata(uri, sessionId) ?: return
        if (openSessionId != sessionId) return
        val draft = sessionDraft
        if (draft != null && draft.session.id == sessionId) {
            drafts.addAttachment(
                sessionId,
                DraftAttachment(
                    idGenerator(),
                    uri,
                    reported.displayName.orEmpty(),
                    reported.sizeBytes,
                    isImage = reported.mimeType?.startsWith("image/") == true,
                ),
            )
            refreshScreen()
            return
        }
        val prepared = prepareAttachment(uri, reported, sessionId) ?: return
        // An old import's error must not replace the newly opened session's feedback.
        if (openSessionId != sessionId) return
        when (prepared) {
            is AttachmentPreparation.Rejected -> setBlocked(prepared.reason)
            is AttachmentPreparation.Ready -> if (admitStagedEntry(prepared.entry)) refreshScreen()
        }
    }

    // Metadata failure is visible only in the session that requested it; cancellation is not failure.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun attachmentMetadata(
        uri: String,
        sessionId: String,
    ): com.helix.feature.conversation.application.attachment.AttachmentSourceMetadata? =
        try {
            attachmentPort.sourceMetadata(uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (openSessionId == sessionId) setBlocked(str(R.string.chat_blocked_cannot_read_file))
            null
        }

    // Import refusal cannot overwrite another session's feedback or mutate its staged attachments.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun prepareAttachment(
        uri: String,
        reported: com.helix.feature.conversation.application.attachment.AttachmentSourceMetadata,
        sessionId: String,
    ): AttachmentPreparation? =
        try {
            attachmentPort.importAttachment(uri, reported, sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (openSessionId == sessionId) setBlocked(str(R.string.chat_import_io_failure))
            null
        }

    private fun admitStagedEntry(entry: StagedAttachmentCandidate): Boolean =
        when (inputState.admit(entry) { openSessionId }) {
            ComposerInputState.Admission.ADDED -> {
                true
            }

            ComposerInputState.Admission.STALE_SESSION, ComposerInputState.Admission.DUPLICATE -> {
                false
            }

            ComposerInputState.Admission.FULL -> {
                setBlocked(
                    str(R.string.chat_blocked_max_attachments, InputAttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE),
                )
                false
            }
        }

    /** Preview the currently visible attachment without staging, sending, or changing authorization. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Unavailable is a read failure, never success.
    suspend fun previewImage(reference: ChatImageRef): ByteArray? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (openSessionId != reference.sessionId) return@withContext null
            val draft =
                sessionDraft
                    ?.takeIf { it.session.id == reference.sessionId }
                    ?.attachments
                    ?.find { it.id == reference.attachmentId }
            val staged =
                stagedAttachments.find {
                    it.sessionId == reference.sessionId && it.artifactId == reference.attachmentId
                }
            try {
                val bytes =
                    when {
                        reference.messageId != null -> attachmentPort.historyPreview(reference)
                        draft != null -> attachmentPort.draftPreview(draft)
                        staged != null -> attachmentPort.stagedPreview(staged)
                        else -> null
                    }
                val stillPresent =
                    reference.messageId != null ||
                        sessionDraft?.attachments?.contains(draft) == true || stagedAttachments.contains(staged)
                bytes.takeIf { openSessionId == reference.sessionId && stillPresent }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    /** Removes one staged attachment addressed by its [PendingAttachmentUi.id] (the artifact id). */
    fun removePendingAttachment(id: String) {
        workScope.launch {
            drafts.removeAttachment(id)
            inputState.removeStaged(listOf(id))
            refreshScreen()
        }
    }

    // --------------------------------------------------------------------------------
    // Send path
    // --------------------------------------------------------------------------------

    suspend fun createGoal(
        objective: String,
        criteria: List<String>,
        budgets: GoalBudgets,
    ): String {
        val goal = goals.createGoal(objective, criteria, budgets)
        refreshTaskDashboards()
        return goal
    }

    suspend fun goalSummaries() = goals.goalSummaries(resolvableOpenSessionId())

    /** Cross-session goal list for the Tasks dashboard (doc section 13). */
    suspend fun goalSummariesAll() = goals.goalSummariesAll()

    suspend fun setGoalReminder(
        goalId: String,
        delayMillis: Long?,
    ): Boolean {
        val changed = goals.setGoalReminder(goalId, delayMillis)
        refreshTaskDashboards()
        return changed
    }

    suspend fun recheckGoalBlocker(goalId: String): Boolean {
        val session = resolvableOpenSessionId() ?: return false
        val resolved = goals.recheckGoalBlocker(goalId, session)
        refreshTaskDashboards()
        return resolved
    }

    suspend fun updateGoalBudgets(
        goalId: String,
        budgets: GoalBudgets,
        expectedRevision: Long? = null,
    ): Boolean {
        val changed = goals.updateGoalBudgets(goalId, budgets, expectedRevision)
        refreshTaskDashboards()
        return changed
    }

    // --------------------------------------------------------------------------------
    // Plan review (research doc section 4.2/4.3; HX2-05): the user's decisions on a plan
    // submitted through `plan.submit` — approve / revise / cancel / execute.
    // --------------------------------------------------------------------------------

    suspend fun reviewPlan(planId: String): PlanReview {
        val review = withContext(Dispatchers.IO) { planReview.review(planId) }
        refreshTaskDashboards()
        return review
    }

    /** The ONLY path to a [PlanExecutionBinding] (doc 4.3); requires the plan to be READY. */
    suspend fun approvePlan(planId: String): PlanExecutionBinding {
        val binding = withContext(Dispatchers.IO) { planReview.approve(planId) }
        refreshTaskDashboards()
        return binding
    }

    suspend fun revisePlan(planId: String) {
        withContext(Dispatchers.IO) { planReview.revise(planId) }
        refreshTaskDashboards()
    }

    suspend fun cancelPlan(planId: String) {
        withContext(Dispatchers.IO) { planReview.cancel(planId) }
        refreshTaskDashboards()
    }

    /**
     * Executes an APPROVED plan (doc 4.3: only after the user's approval): creates the Goal
     * bound to the approved version (planId + hash) and drives its first turn through the SAME
     * unified [agentRuntime] every in-app entry uses (HX2-01). The provider is resolved from
     * the currently open session under the same fail-closed gates as the chat send path; a
     * failed gate returns null BEFORE anything is written, with the block reason surfaced.
     *
     * After the plan is committed EXECUTING + its goal READY (one atomic transaction, [planReview]
     * .execute), a refused first turn (a busy session drops it silently; a provider/goal gate sets
     * its own reason) leaves the goal READY and the plan EXECUTING — a RECOVERABLE state, never a
     * dead end. The goal is startable again from its row and re-executing this plan is idempotent
     * (it returns the bound goal, not a second), so this surfaces a "ready, not started" block
     * only when a more-specific gate did not already explain the refusal (research doc 5.1).
     */
    @Suppress("ReturnCount", "SwallowedException") // one early return per gate; the IAE becomes a localized UI block
    suspend fun executeApprovedPlan(
        binding: PlanExecutionBinding,
        budgets: com.helix.core.model.GoalBudgets,
    ): String? = withContext(Dispatchers.IO) { executeApprovedPlanOnIo(binding, budgets) }

    // The plan-review dialog calls [executeApprovedPlan] from a Compose coroutine scope (the main
    // dispatcher), but the whole body is Room I/O — the session and provider-gate reads, the
    // plan->EXECUTING transaction, the first-turn start — and HelixStorage refuses database work on
    // the main thread. Hopping to IO is the same self-dispatch reviewPlan / approvePlan /
    // revisePlan apply to their Room calls, and it matches the chat-send path, which runs this same
    // submitTurn -> application admission on workScope (IO).
    @Suppress("ReturnCount", "SwallowedException")
    private suspend fun executeApprovedPlanOnIo(
        binding: PlanExecutionBinding,
        budgets: com.helix.core.model.GoalBudgets,
    ): String? {
        val boundSession = currentSession()
        boundSession?.let { session ->
            modelBindingProblem(session)?.let {
                setBlocked(it)
                return null
            }
        }
        val providerId = boundSession?.providerId
        if (providerId == null) {
            setBlocked(str(ConversationR.string.chat_blocked_no_provider_bound))
            return null
        }
        if (!providers.chatSelectable(providerId)) {
            setBlocked(str(ConversationR.string.chat_blocked_provider_untested))
            return null
        }
        val goalId =
            try {
                planReview.execute(binding, budgets)
            } catch (e: IllegalArgumentException) {
                setBlocked(str(R.string.plan_execute_failed))
                return null
            }
        // The plan is committed EXECUTING and its goal is READY — both durable. If the first turn
        // does not start (e.g. application admission reports a busy session) the state is
        // RECOVERABLE, not a dead end: the goal is startable again from its row, and re-executing
        // this plan is idempotent (it returns the bound goal, never a second). Surface that, but
        // only when no more-specific gate (provider / goal) already set a reason — never clobber it.
        val priorBlocked = _screen.value.blockedReason
        val started = submitTurn(text = null, providerId = providerId, goalId = goalId)
        if (!started && _screen.value.blockedReason == priorBlocked) {
            setBlocked(str(R.string.plan_execute_not_started))
        }
        refreshTaskDashboards()
        return goalId
    }

    @Suppress("SwallowedException") // Rejected stored Goal/session state becomes a localized UI block; no raw details.
    fun continueGoal(
        goalId: String,
        text: String,
    ) {
        workScope.launch {
            try {
                sendNow(text, goalId)
            } catch (e: IllegalArgumentException) {
                setBlocked(str(R.string.goal_continue_unavailable))
            }
        }
    }

    private var pendingGoalId: String? = null
    private val submissionGate = Mutex()

    @Volatile private var pendingSubmission: ChatSubmission? = null

    fun pendingComposerSubmission(): ChatSubmission? = pendingSubmission

    /**
     * The send intent. Order (fail-closed, user-visible):
     * 1. session has a provider; 2. the provider passed its connection test;
     * 3. HTTP is warning-only; 4. the attachment send
     *    gate (staged attachments re-verified against their bound snapshots — any
     *    unsupported / tampered / missing attachment blocks before any egress);
     * 5. the egress disclosure gate (forbidden content rejected; high-sensitivity
     *    held for per-send confirmation — [confirmSend]; a staged attachment always
     *    maps to high-sensitivity file text, so a send carrying one is NEVER
     *    auto-passed).
     *
     * A send with no staged attachments reproduces the pre-attachment pure-text
     * path exactly (the same egress decide over the typed text — no regression).
     *
     * The whole gate runs on this service's IO scope: the provider reads are
     * Room reads and must never run on the UI thread. The UI may call from
     * any thread; the visible outcome arrives via [screen].
     */
    @Suppress("ReturnCount") // draft admission keeps each rejected state explicit
    fun compactContext(focus: String? = null) {
        if (_screen.value.isDraft || _screen.value.isSending || stagedAttachments.isNotEmpty()) return
        val sessionId = openSessionId ?: return
        sendSubmission(
            ChatSubmission(
                sessionId,
                0,
                idGenerator(),
                com.helix.core.agent.ContextCommands
                    .compact(focus),
            ),
        )
    }

    /** Optional per-session file cache. Its success is never required for submission. */
    suspend fun saveComposerDraft(request: ChatSubmission): Boolean =
        withContext(Dispatchers.IO) {
            val cached = inputCache.save(request)
            rememberNonemptyConversation(request)
            cached
        }

    @Suppress("TooGenericExceptionCaught") // Optional conversation recovery must not become a send prerequisite.
    private suspend fun rememberNonemptyConversation(request: ChatSubmission) {
        val hasInput =
            request.text.isNotEmpty() || request.attachmentIds.isNotEmpty() ||
                request.referenceSourceSessionId != null
        if (!hasInput || sessionDraft?.session?.id != request.sessionId) return
        try {
            submissionGate.withLock { materializeDraftSession(request.sessionId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.w(TAG, "Could not retain unsent conversation metadata")
        }
    }

    /** A dispatched cache write survives cancellation of the screen awaiting its result. */
    fun saveComposerDraftAsync(request: ChatSubmission): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async { saveComposerDraft(request) }

    @Suppress("SwallowedException") // Malformed optional input state must not disable ordinary sending.
    suspend fun loadComposerDraft(sessionId: String): ChatSubmission? =
        withContext(Dispatchers.IO) {
            try {
                inputCache.read(sessionId)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    /** Retains an obsolete unsent revision as ordinary input; never resumes or sends its old action. */
    suspend fun loadComposerInput(sessionId: String): ChatSubmission? =
        withContext(Dispatchers.IO) {
            submissionGate.withLock {
                run {
                    val cached = loadCachedComposerInput(sessionId) ?: return@run null
                    if (cached.revisedMessageId == null) return@run cached
                    val completed = completedSubmission(cached)
                    if (completed is ChatSubmissionOutcome.Accepted || completed is ChatSubmissionOutcome.Enqueued) {
                        inputCache.clear(sessionId, cached.revision, cached.clientRequestId)
                        return@run inputCache.read(sessionId)
                    }
                    if (pendingSubmission == cached) cancelPendingSend()
                    inputCache.replace(
                        cached,
                        cached.copy(
                            revisedMessageId = null,
                            clientRequestId = idGenerator(),
                            revision = ComposerEditClock.next(cached.revision),
                        ),
                    )
                }
            }
        }

    @Suppress("SwallowedException") // Optional malformed cache is not an input prerequisite.
    private fun loadCachedComposerInput(sessionId: String): ChatSubmission? =
        try {
            inputCache.read(sessionId)
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Recovers a durable receipt without admitting or replaying any model/tool work. */
    suspend fun acceptedComposerReceipt(request: ChatSubmission): ChatSubmissionReceipt? =
        withContext(Dispatchers.IO) {
            submissionGate.withLock {
                when (val outcome = completedSubmission(request)) {
                    is ChatSubmissionOutcome.Accepted,
                    is ChatSubmissionOutcome.Enqueued,
                    -> ChatSubmissionReceipt(request, outcome)

                    else -> null
                }
            }
        }

    /** Creates session metadata when settings or attachments need it; input caching does not require it. */
    @Suppress("TooGenericExceptionCaught") // Failed metadata validation preserves the draft and reports failure.
    suspend fun materializeDraftSession(expectedSessionId: String): String? =
        withContext(workScope.coroutineContext) {
            if (catalog.selection(expectedSessionId) != null) return@withContext expectedSessionId
            if (openSessionId != expectedSessionId) return@withContext null
            val draft = sessionDraft ?: return@withContext expectedSessionId
            val id = draft.session.id
            if (id != expectedSessionId || !drafts.beginPreparation(id)) return@withContext null
            try {
                val attachments =
                    saveSessionDraft(id) ?: return@withContext null
                for (attachment in attachments) {
                    if (openSessionId != id) return@withContext null
                    stageAttachmentNow(attachment.uri, id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (openSessionId == id) setBlocked(str(com.helix.ui.shared.R.string.project_failed))
                return@withContext null
            } finally {
                drafts.finishPreparation()
                refreshScreen()
            }
            id
        }

    /**
     * Restores staged attachments from persisted artifacts for a recovered composer draft.
     * Reconstructs and re-verifies staged snapshots; returns the list of missing/unverifiable artifact IDs.
     */
    suspend fun restoreDraftAttachments(
        sessionId: String,
        attachmentIds: List<String>,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val requested = attachmentIds.toList()
            if (openSessionId != sessionId) return@withContext requested
            val expectedRevision = inputState.stagedRevision
            val missing = mutableListOf<String>()
            val restored = mutableListOf<StagedAttachmentCandidate>()
            for (id in requested) {
                val entry = attachmentPort.restore(id, sessionId)
                if (entry != null) restored.add(entry) else missing.add(id)
            }
            // Preserve edits made during re-verification, even in the same open session.
            if (!inputState.replaceStaged(restored, sessionId, expectedRevision) { openSessionId }) {
                return@withContext requested
            }
            refreshScreen()
            missing
        }

    fun currentStagedAttachmentIds(expectedSessionId: String? = null): List<String> =
        inputState.stagedIds(expectedSessionId)

    /** CAS acknowledgement: an old receipt cannot clear an edited draft or another session. */
    suspend fun acknowledgeSubmission(receipt: ChatSubmissionReceipt): Boolean =
        withContext(Dispatchers.IO) {
            val request = receipt.submission
            val input = inputCommands.receipt(request.clientRequestId)
            val valid =
                when (val accepted = receipt.outcome) {
                    is ChatSubmissionOutcome.Enqueued -> {
                        input?.inputId == accepted.inputId && SessionInputBinding.matches(request, input)
                    }

                    is ChatSubmissionOutcome.Accepted -> {
                        if (input != null) {
                            input.consumedTurnId == accepted.turnId && SessionInputBinding.matches(request, input)
                        } else {
                            submissionHistory.acceptedTurnId(request.clientRequestId, request.sessionId) ==
                                accepted.turnId
                        }
                    }

                    else -> {
                        false
                    }
                }
            if (!valid) return@withContext false
            inputCache.clear(request.sessionId, request.revision, request.clientRequestId)
        }

    /** Independent history editor, serialized with input admission but never submitted as a Turn. */
    fun prepareMessageEdit(
        sessionId: String,
        messageId: String,
    ): kotlinx.coroutines.Deferred<MessageEditSnapshot?> =
        workScope.async {
            submissionGate.withLock {
                if (openSessionId != sessionId) return@withLock null
                try {
                    messageEditor.load(sessionId, messageId)
                } catch (_: android.database.sqlite.SQLiteException) {
                    null
                } catch (
                    _: java.io.IOException,
                ) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }

    fun saveMessageEdit(
        snapshot: MessageEditSnapshot,
        text: String,
    ): kotlinx.coroutines.Deferred<MessageEditResult> =
        workScope.async {
            submissionGate.withLock {
                if (openSessionId != snapshot.sessionId) return@withLock MessageEditResult.UNAVAILABLE
                if (pendingSubmission != null || pendingInputResume != null ||
                    preparingDraft
                ) {
                    return@withLock MessageEditResult.BUSY
                }
                val result =
                    try {
                        messageEditor.save(snapshot, text)
                    } catch (
                        _: java.io.IOException,
                    ) {
                        MessageEditResult.FAILED
                    } catch (
                        _: android.database.sqlite.SQLiteException,
                    ) {
                        MessageEditResult.FAILED
                    } catch (_: IllegalArgumentException) {
                        MessageEditResult.UNAVAILABLE
                    }
                if (result == MessageEditResult.SAVED) refreshScreen()
                result
            }
        }

    /** Service-owned admission survives caller cancellation; never reads an outcome from UI state. */
    fun sendQuestionAnswer(request: ChatSubmission): kotlinx.coroutines.Deferred<ChatSubmissionReceipt> =
        sessionActions.submit {
            submissionGate.withLock {
                val outcome =
                    submissionAttempt {
                        if (openSessionId != request.sessionId || !validHumanInput(request)) {
                            ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
                        } else if (pendingSubmission != null || pendingInputResume != null || preparingDraft) {
                            ChatSubmissionOutcome.Rejected("CONFIRMATION_PENDING")
                        } else {
                            completedSubmission(request) ?: sendNow(request.text, submission = request, textOnly = true)
                        }
                    }
                ChatSubmissionReceipt(request, outcome)
            }
        }

    fun sendSubmission(request: ChatSubmission): kotlinx.coroutines.Deferred<ChatSubmissionReceipt> {
        val snapshot = request.copy(attachmentIds = request.attachmentIds.toList())
        return sessionActions.submit {
            submissionGate.withLock {
                if (openSessionId == snapshot.sessionId) showLatestMessages()
                val blockedEpoch = _screen.value.blockedReasonEpoch
                val outcome = submissionAttempt { admitSubmission(snapshot) }
                val receipt = ChatSubmissionReceipt(snapshot, outcome)
                if (outcome is ChatSubmissionOutcome.Accepted || outcome is ChatSubmissionOutcome.Enqueued) {
                    clearAcceptedSubmissionBlock(snapshot.sessionId, blockedEpoch)
                    inputCache.clear(snapshot.sessionId, snapshot.revision, snapshot.clientRequestId)
                }
                receipt
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun submissionAttempt(block: suspend () -> ChatSubmissionOutcome): ChatSubmissionOutcome =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Preserve diagnosis without logging user text, credentials or raw filesystem paths.
            Log.e(TAG, "Submission admission failed: ${error.javaClass.simpleName}")
            ChatSubmissionOutcome.Rejected("ADMISSION_FAILED")
        }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Explicit identity and draft admission guards.
    private suspend fun admitSubmission(request: ChatSubmission): ChatSubmissionOutcome {
        if (openSessionId != request.sessionId) return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        completedSubmission(request)?.let { return it }
        if (pendingInputResume != null) return ChatSubmissionOutcome.Rejected("CONFIRMATION_PENDING")
        if (pendingSubmission != null) {
            return if (pendingSubmission == request) {
                ChatSubmissionOutcome.PendingConfirmation
            } else {
                ChatSubmissionOutcome.Rejected("CONFIRMATION_PENDING")
            }
        }
        val selection =
            sessionDraft?.session?.takeIf { it.id == request.sessionId }?.let {
                ConversationSessionSelection(it.id, it.providerId, it.modelId, false)
            } ?: catalog.selection(request.sessionId)
                ?: return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        if (!hasSelectedConversationModel(selection.providerId, selection.modelId)) {
            return ChatSubmissionOutcome.Rejected("NO_MODEL_SELECTED")
        }
        referenceModelProblem(request, selection)?.let { return submissionBlocked(it) }
        if (!validHumanInput(request)) return ChatSubmissionOutcome.Rejected("INVALID_INPUT")
        if (preparingDraft) return ChatSubmissionOutcome.Rejected("PREPARING_DRAFT")
        if (request.attachmentIds != stagedAttachments.map { it.artifactId }) {
            return ChatSubmissionOutcome.Rejected("ATTACHMENTS_CHANGED")
        }
        val draft = sessionDraft
        if (draft != null) {
            if (!drafts.beginPreparation(draft.session.id)) return ChatSubmissionOutcome.Rejected("PREPARING_DRAFT")
            _screen.update { it.copy(preparingDraft = true) }
            try {
                val attachments =
                    saveSessionDraft()
                        ?: return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
                attachments.forEach { stageAttachmentNow(it.uri) }
                if (stagedAttachments.size != attachments.size) {
                    return ChatSubmissionOutcome.Rejected("ATTACHMENT_PREPARATION_FAILED")
                }
            } finally {
                drafts.finishPreparation()
                refreshScreen()
            }
        }
        if (request.revisedMessageId != null) {
            when (turnHistory.revisionAvailability(request.sessionId, requireNotNull(request.revisedMessageId))) {
                MessageRevisionAvailability.TARGET_CHANGED -> return ChatSubmissionOutcome.Rejected(
                    "REVISION_TARGET_CHANGED",
                )

                MessageRevisionAvailability.BUSY -> return ChatSubmissionOutcome.Rejected("REVISION_SESSION_BUSY")

                MessageRevisionAvailability.READY -> Unit
            }
            return sendNow(request.text, submission = request)
        }
        if (openSessionId != request.sessionId) return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        return sendNow(request.text, submission = request)
    }

    private suspend fun referenceModelProblem(
        request: ChatSubmission,
        selection: ConversationSessionSelection,
    ): String? {
        if (request.referenceSourceSessionId == null && request.knowledgeReferences.isEmpty()) return null
        val control = _runControl.value
        val toolsEnabled = control.mode != AgentMode.CHAT || control.chatToolsEnabled
        val capable =
            providers
                .capabilitiesFor(
                    requireNotNull(selection.providerId),
                    selection.modelId,
                )?.toolCalls
        val available =
            referenceToolsAvailable(
                request.sessionId,
                request.referenceSourceSessionId != null,
                request.knowledgeReferences.isNotEmpty(),
            )
        return if (!toolsEnabled || capable != true || !available) {
            str(
                if (request.knowledgeReferences.isNotEmpty()) {
                    R.string.knowledge_reference_tools_required
                } else {
                    ConversationR.string.reference_tools_required
                },
            )
        } else {
            null
        }
    }

    fun referenceToolsAvailable(
        sessionId: String,
        conversation: Boolean,
        knowledge: Boolean,
    ): Boolean = toolCalls.referenceToolsAvailable(sessionId, conversation, knowledge)

    private fun completedSubmission(request: ChatSubmission): ChatSubmissionOutcome? {
        inputCommands.receipt(request.clientRequestId)?.let {
            return if (SessionInputBinding.matches(request, it)) {
                it.consumedTurnId?.let { turnId -> ChatSubmissionOutcome.Accepted(turnId) }
                    ?: ChatSubmissionOutcome.Enqueued(it.inputId)
            } else {
                ChatSubmissionOutcome.Rejected("REQUEST_ID_ALREADY_USED")
            }
        }
        return submissionHistory.completed(request)
    }

    private fun validHumanInput(request: ChatSubmission): Boolean {
        val text = request.text
        val maxText =
            if (requiresGoalObjective(request.sessionId, request.delivery)) {
                com.helix.core.agent.Goal.MAX_OBJECTIVE_LENGTH
            } else {
                MAX_MODEL_TEXT_CHARS
            }
        val validText = text.length <= maxText && '\u0000' !in text
        val hasContent =
            text.isNotBlank() || stagedAttachments.isNotEmpty() ||
                !sessionDraft?.attachments.isNullOrEmpty() || request.referenceSourceSessionId != null ||
                request.knowledgeReferences.isNotEmpty() || request.skillActivations.isNotEmpty()
        return validText && hasContent
    }

    private fun requiresGoalObjective(
        sessionId: String,
        delivery: SessionInputDelivery,
    ): Boolean =
        synchronized(turnGate) {
            if (sessionId != openSessionId || _runControl.value.mode != AgentMode.GOAL ||
                delivery == SessionInputDelivery.STEER
            ) {
                false
            } else {
                !turnEngine.liveExecution.hasActive(sessionId) && !goalRuntime.hasActivation(sessionId)
            }
        }

    @Suppress(
        "ReturnCount",
        "CyclomaticComplexMethod",
        "LongMethod",
    ) // Linear fail-closed send gate stays auditable as one choke point.
    private suspend fun sendNow(
        rawText: String,
        goalId: String? = null,
        submission: ChatSubmission? = null,
        textOnly: Boolean = false,
    ): ChatSubmissionOutcome {
        val text =
            com.helix.feature.conversation.application.input.skillInputText(
                rawText,
                submission?.skillActivations.orEmpty(),
            )
        if (text.length > MAX_MODEL_TEXT_CHARS || text.indexOf('\u0000') >= 0) {
            return submissionBlocked(str(R.string.chat_blocked_message_invalid, MAX_MODEL_TEXT_CHARS))
        }
        val staged = if (textOnly) emptyList() else stagedAttachments
        val hasReference =
            submission?.referenceSourceSessionId != null || !submission?.knowledgeReferences.isNullOrEmpty()
        // An attachment-only send is valid (ADR-0014 §5): blank text is admitted while
        // staged attachments ride the send; blank text with nothing staged is still the
        // empty-send block of today.
        val startingGoal =
            requiresGoalObjective(
                submission?.sessionId ?: openSessionId.orEmpty(),
                submission?.delivery ?: SessionInputDelivery.QUEUE,
            )
        if (missingSendContent(text, hasReference, staged.isNotEmpty(), startingGoal)) {
            return submissionBlocked(str(R.string.chat_blocked_message_invalid, MAX_MODEL_TEXT_CHARS))
        }
        val session = currentSession() ?: return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        val request = submission ?: ChatSubmission(session.id, 0, idGenerator(), text, staged.map { it.artifactId })
        if (session.id != request.sessionId) return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        if (catalog.selection(session.id) != null && !ensureSessionWorkspace(session.id)) {
            return submissionBlocked(str(R.string.chat_directory_failed))
        }
        val reference =
            request.referenceSourceSessionId?.let { sourceSessionId ->
                try {
                    conversationReferences.prepare(
                        request.sessionId,
                        sourceSessionId,
                        requireNotNull(request.referenceKind),
                    )
                } catch (_: IllegalArgumentException) {
                    return submissionBlocked(str(ConversationR.string.chat_blocked_reference_unavailable))
                }
            }
        modelBindingProblem(session)?.let { return submissionBlocked(it) }
        val providerId =
            session.providerId ?: run {
                return submissionBlocked(str(ConversationR.string.chat_blocked_no_provider_bound))
            }
        if (!providers.chatSelectable(providerId)) {
            return submissionBlocked(str(ConversationR.string.chat_blocked_provider_untested))
        }
        val target = providers.egressTargetFor(providerId)
        // HXA-055, before the gate: a staged image whose on-device normalization failed at
        // staging is local-only (save/preview) — block with the actionable reason, and no
        // raw bytes may ever reach the wire as a fallback.
        staged.firstNotNullOfOrNull { it.imageSendError }?.let { reason ->
            return submissionBlocked(reason)
        }
        // HXA-055 (ADR-0014 §4): an image leaves ONLY when the target provider's vision
        // capability is CONFIRMED — a real probe (connection test phase 5) or a user-visible
        // manual declaration. Unconfirmed vision blocks BEFORE the disclosure is shown.
        if (staged.any { it.normalizedArtifactId != null }) {
            // capabilitiesFor is fail-closed by contract (null on any stored-snapshot failure).
            val visionConfirmed =
                runCatching {
                    providers.capabilitiesFor(
                        providerId,
                        openSessionId?.let { catalog.selection(it)?.modelId },
                    )
                }.getOrNull()
                    ?.vision
                    ?: false
            if (!visionConfirmed) {
                return submissionBlocked(
                    str(R.string.chat_blocked_vision_unconfirmed),
                )
            }
        }
        // The single admission choke point (ADR-0014 §5): the fail-closed attachment gate
        // first (re-hash every staged file against its bound snapshot — for images the
        // NORMALIZED artifact — AND scan the FULL content for credential shapes —
        // [credentialScan]), then the egress disclosure over the typed text plus one source
        // per staged attachment. With an empty gate this reproduces today's pure-text decide
        // call exactly (no regression).
        val gate = attachmentPort.evaluate(staged, credentialScan)
        if (!validKnowledgeReferences(
                request,
            )
        ) {
            return submissionBlocked(str(ConversationR.string.knowledge_reference_changed))
        }
        val outcome = AttachmentSendAdmission.admit(gate, text, target, strings, reference, request.knowledgeReferences)
        return when (outcome) {
            is AttachmentSendAdmission.Outcome.Blocked -> {
                // The staged attachments STAY pending: the user removes the problem file
                // and re-sends — a gate block is never a silent drop.
                submissionBlocked(outcome.reason)
            }

            is AttachmentSendAdmission.Outcome.Egress -> {
                applyEgressDecision(
                    outcome.decision,
                    staged,
                    text,
                    providerId,
                    target,
                    goalId,
                    request,
                    reference,
                )
            }
        }
    }

    private fun missingSendContent(
        text: String,
        hasReference: Boolean,
        hasAttachments: Boolean,
        startingGoal: Boolean,
    ): Boolean =
        text.isBlank() &&
            (startingGoal || (!hasReference && !hasAttachments))

    /**
     * Applies the egress decision of an admitted send (ADR-0014 §5). Proceed is reachable ONLY
     * with no staged attachment (a FileText source always forces Confirm) — otherwise it is a
     * construction bug and fails closed. Confirm holds the send for per-send confirmation and
     * BINDS the approval to the exact [target] shown in the dialog (the staged attachments
     * STAY pending; the confirm path re-materializes them). Rejected blocks.
     */
    @Suppress("ReturnCount", "LongParameterList") // The immutable submission binds the existing egress gates.
    private suspend fun applyEgressDecision(
        decision: EgressDisclosure.Decision,
        staged: List<StagedAttachmentCandidate>,
        text: String,
        providerId: String,
        target: EgressDisclosure.EgressTarget,
        goalId: String?,
        submission: ChatSubmission,
        reference: ConversationReferenceSnapshotInput?,
    ): ChatSubmissionOutcome {
        if (openSessionId != submission.sessionId) return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        return when (decision) {
            EgressDisclosure.Decision.Proceed -> {
                if (staged.isNotEmpty() || reference != null || submission.knowledgeReferences.isNotEmpty()) {
                    // Unreachable by construction; fail closed so a staged file/reference is never
                    // silently dropped from the outgoing request or skips its disclosure.
                    return submissionBlocked(str(R.string.chat_blocked_egress_unconfirmed))
                }
                // A pure-text Proceed carries NO attachments, so it clears none: the staged
                // list is already empty (the snapshot above), and a file picked in the microsecond
                // since that snapshot is the user's for the NEXT send — a send is never a silent
                // drop. (The confirm path clears exactly the approved set, not the live list.)
                submitMessageTurn(submission, text, providerId, goalId, reference = reference)
            }

            is EgressDisclosure.Decision.Confirm -> {
                // Bind the pending confirmation to BOTH the exact target the dialog shows AND the
                // exact attachment set it enumerated (ADR-0014 §5): confirmSendNow re-derives the
                // live target (blocking on provider/origin drift) and requires the current staged
                // set to be IDENTICAL to [staged] — an attachment never shown in the dialog can
                // never leave the device; an old confirmation is never reusable.
                pendingGoalId = goalId
                pendingSend = text
                pendingEgress = target
                pendingAttachmentIds = staged.map { it.artifactId }
                pendingReference = reference
                // HX2-01 §2e: one stable id for this pending submission, carried through a possibly
                // re-driven confirm so a double-confirm dedups to the single turn it started.
                pendingSubmission = submission
                _screen.update { it.copy(pendingDisclosure = decision.summary, blockedReason = null) }
                ChatSubmissionOutcome.PendingConfirmation
            }

            is EgressDisclosure.Decision.Rejected -> {
                submissionBlocked(egressRejectedLabel(decision.reason))
            }
        }
    }

    /** The user confirmed the high-sensitivity disclosure for [pendingSend]. */
    fun confirmSend() {
        pendingSessionInputResume()?.let { (id, revision) ->
            confirmSessionInputResume(id, revision)
            return
        }
        val request = pendingSubmission ?: return
        confirmSubmission(request)
    }

    fun confirmSubmission(request: ChatSubmission): kotlinx.coroutines.Deferred<ChatSubmissionReceipt> =
        workScope.async {
            submissionGate.withLock {
                val outcome =
                    completedSubmission(request)
                        ?: if (pendingSubmission != request || openSessionId != request.sessionId) {
                            ChatSubmissionOutcome.Rejected("CONFIRMATION_CHANGED")
                        } else {
                            submissionAttempt { confirmSendNow(request) }
                        }
                if (outcome is ChatSubmissionOutcome.Accepted || outcome is ChatSubmissionOutcome.Enqueued) {
                    inputCache.clear(request.sessionId, request.revision, request.clientRequestId)
                }
                ChatSubmissionReceipt(request, outcome)
            }
        }

    @Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught") // fail-closed gate checks
    private suspend fun confirmSendNow(request: ChatSubmission): ChatSubmissionOutcome {
        val text = pendingSend ?: return ChatSubmissionOutcome.Rejected("CONFIRMATION_CHANGED")
        if (!validKnowledgeReferences(request)) {
            cancelPendingSend()
            return submissionBlocked(str(ConversationR.string.knowledge_reference_changed))
        }
        val goalId = pendingGoalId
        // Capture the stable client-request id with the pending state it belongs to (HX2-01 §2e):
        // a possibly-re-driven confirm carries the same id, so the runtime dedups to the single
        // turn it started.
        // Capture the approved target AND attachment set BEFORE clearing the pending state: the
        // binding checks below compare the LIVE target and the CURRENT staged set against exactly
        // what the dialog showed.
        val approvedTarget = pendingEgress
        val approvedAttachmentIds = pendingAttachmentIds
        val approvedReference = pendingReference
        val session = currentSession() ?: return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        modelBindingProblem(session)?.let { return submissionBlocked(it) }
        val providerId = session.providerId ?: return ChatSubmissionOutcome.Rejected("NO_PROVIDER")
        pendingSubmission = null
        pendingGoalId = null
        pendingSend = null
        pendingEgress = null
        pendingAttachmentIds = emptyList()
        pendingReference = null
        _screen.update { it.copy(pendingDisclosure = null) }
        // Fail-closed re-check (the gate already ran when the disclosure was
        // shown): a provider re-test/revocation between the dialog and this
        // confirmation must not open a wire path the user has not approved.
        if (!providers.chatSelectable(providerId)) {
            return submissionBlocked(str(ConversationR.string.chat_blocked_provider_untested))
        }
        // ADR-0014 §5: the approval bound a SPECIFIC egress target (provider + origin).
        // A provider edit/re-test that moved the endpoint between the dialog and this
        // tap voids the old confirmation — block and make the user re-send; the
        // disclosure is NOT re-shown, and nothing may leave through a drifted origin.
        val liveTarget =
            try {
                providers.egressTargetFor(providerId)
            } catch (e: Exception) {
                // The provider row vanished between the dialog and this tap: fail closed.
                return submissionBlocked(str(R.string.chat_blocked_egress_target_changed))
            }
        if (
            approvedTarget == null ||
            approvedTarget.providerId != liveTarget.providerId ||
            approvedTarget.origin != liveTarget.origin
        ) {
            return submissionBlocked(str(R.string.chat_blocked_egress_target_changed))
        }
        // Delegate the staged-attachment handling (enumeration drift check + re-verify + launch);
        // a pure-text pending (no staged) takes the exact pre-attachment path (no regression).
        return confirmStagedSend(
            text,
            providerId,
            approvedAttachmentIds,
            liveTarget,
            goalId,
            request,
            approvedReference,
        )
    }

    /**
     * The confirmed, target-bound send of a pending send's staged attachments (ADR-0014 §5), run
     * AFTER [confirmSendNow]'s provider/target drift re-checks. The current staged set
     * must EXACTLY match [approvedAttachmentIds] (the set the dialog enumerated) — any drift
     * blocks with a re-send, so a file never shown in the dialog never leaves and a removed one is
     * not silently turned into a pure-text send. A ready set is re-verified (re-hash + credential
     * scan), cleared (exactly the approved ids), and launched; both-empty (pure text) is the exact
     * pre-attachment path (no regression).
     */
    @Suppress("ReturnCount", "LongMethod") // one fail-closed early return per staged-set gate
    private suspend fun confirmStagedSend(
        text: String,
        providerId: String,
        approvedAttachmentIds: List<String>,
        liveTarget: EgressDisclosure.EgressTarget,
        goalId: String?,
        submission: ChatSubmission,
        reference: ConversationReferenceSnapshotInput?,
    ): ChatSubmissionOutcome {
        // A question answer has its own text-only input identity, independent of the composer.
        val questionAnswer =
            submission.clientRequestId.startsWith("answer:question:") && submission.attachmentIds.isEmpty()
        val staged =
            if (questionAnswer) {
                if (approvedAttachmentIds.isNotEmpty()) {
                    return submissionBlocked(str(ConversationR.string.chat_blocked_attachments_changed))
                }
                emptyList()
            } else {
                inputState.snapshotForSend(submission.sessionId, approvedAttachmentIds)
                    ?: return submissionBlocked(str(ConversationR.string.chat_blocked_attachments_changed))
            }
        if (staged.isEmpty()) {
            return submitMessageTurn(submission, text, providerId, goalId, reference = reference)
        }
        // HXA-055: a staged image whose on-device normalization failed at staging time is
        // local-only (save/preview) — the send is blocked with the actionable reason, and no
        // raw bytes may ever reach the wire as a fallback.
        staged.firstNotNullOfOrNull { it.imageSendError }?.let { reason ->
            return submissionBlocked(reason)
        }
        // RE-VERIFY before the user-approved egress goes out: re-hash every staged file
        // against its bound snapshot (images: the NORMALIZED artifact, the bytes that leave)
        // AND re-scan the FULL content for credential shapes — fail closed if any file
        // changed, vanished or carries a credential in the meantime.
        val materialized =
            reVerifyStagedForEgress(staged, text, liveTarget)
                ?: return ChatSubmissionOutcome.Rejected("ATTACHMENT_VERIFICATION_FAILED")
        // HXA-055 (ADR-0014 §4): an image leaves ONLY when the target provider's vision
        // capability is CONFIRMED — a real probe (the connection test's phase 5) or a
        // user-visible manual declaration. Unconfirmed vision blocks with an actionable
        // error (re-run the test or declare it manually); the text parts stay sendable in a
        // retry of the same message only if the user removes the image.
        if (!confirmedVisionFor(materialized, providerId)) {
            return submissionBlocked(str(R.string.chat_blocked_vision_unconfirmed))
        }
        // Ready: the gate's attachments are in staged order — pair each with its staged entry
        // and build (a) the expanded model-visible user message (bounded UNTRUSTED blocks;
        // image blocks describe the pixels that travel as the message's image parts) and
        // (b) the message_attachments bindings committed by the admission transaction in the turn's
        // transaction. An image BINDS THE NORMALIZED ARTIFACT (the bytes that leave) — the raw
        // artifact stays registered but unbound (local save/preview source).
        val blocks = attachmentContextBlocks(materialized, staged)
        val bindings =
            staged.map { entry ->
                com.helix.core.agent.turn.TurnInputAttachment(
                    artifactId =
                        entry.normalizedArtifactId
                            ?: entry.artifactId,
                    purpose = AttachmentPurpose.REFERENCE,
                    boundSha256 =
                        entry.normalizedSha256
                            ?: entry.boundSha256,
                )
            }
        // The unified HX2-01 entry dispatches the turn — it persists the user message and its
        // bindings in the turn's transaction and returns whether the start was admitted. The
        // approved attachments are consumed only on an admitted start; rejected typed submissions
        // leave the persisted composer draft untouched.
        val outcome =
            submitMessageTurn(
                submission = submission,
                text = AttachmentContext.buildUserMessageContent(text, blocks),
                providerId = providerId,
                attachments = bindings.map { AttachmentBindingIntent(it.artifactId, it.boundSha256) },
                goalId = goalId,
                reference = reference,
            )
        if (isAcceptedInput(outcome)) {
            // Only consume attachments after the user message and its bindings are durable.
            inputState.consumeStaged(staged)
        }
        refreshScreen()
        return outcome
    }

    private suspend fun confirmedVisionFor(
        materialized: List<AttachmentMaterialization>,
        providerId: String,
    ): Boolean =
        if (materialized.none { it is AttachmentMaterialization.Image }) {
            true
        } else {
            runCatching {
                providers.capabilitiesFor(providerId, openSessionId?.let { catalog.selection(it)?.modelId })
            }.getOrNull()?.vision ?: false
        }

    private fun isAcceptedInput(outcome: ChatSubmissionOutcome): Boolean =
        outcome is ChatSubmissionOutcome.Accepted || outcome is ChatSubmissionOutcome.Enqueued

    /**
     * The model-visible attachment context blocks for a Ready egress (in staged order): text
     * blocks carry the bounded content, image blocks describe the NORMALIZED artifact (the bytes
     * that travel as the message's image parts). A non-materializable entry is unreachable (the
     * gate returns only Text/Image in Ready) — fail closed if that invariant ever changes.
     */
    private fun attachmentContextBlocks(
        materialized: List<AttachmentMaterialization>,
        staged: List<StagedAttachmentCandidate>,
    ): List<AttachmentContextBlock> =
        materialized.mapIndexed { index, m ->
            when (m) {
                is AttachmentMaterialization.Text -> {
                    AttachmentContextBlock.Text(m, staged[index].relativePath, attachmentPort.workspaceScopeId)
                }

                is AttachmentMaterialization.Image -> {
                    AttachmentContextBlock.Image(
                        fileName = m.fileName,
                        mediaType = m.mediaType,
                        sha256 = m.sha256,
                        sizeBytes = m.sizeBytes,
                        width = m.width,
                        height = m.height,
                    )
                }

                else -> {
                    error("non-materializable attachment reached the send path")
                }
            }
        }

    /**
     * The fail-closed re-verification that runs right before a user-approved egress goes out
     * (ADR-0014 §5): every staged file is re-hashed against its bound snapshot and its FULL
     * content is re-scanned for credential shapes ([credentialScan]). Any changed, vanished or
     * credentialed file sets the blocked state and returns null — the staged attachments STAY
     * pending (a block is never a silent drop) and the disclosure is NOT re-shown. The
     * materialized attachments (in staged order) are returned when the gate is Ready.
     */
    private fun reVerifyStagedForEgress(
        staged: List<StagedAttachmentCandidate>,
        text: String,
        target: EgressDisclosure.EgressTarget,
    ): List<AttachmentMaterialization>? {
        val gate = attachmentPort.evaluate(staged, credentialScan)
        if (gate is AttachmentSendDecision.Ready) return gate.attachments
        if (gate is AttachmentSendDecision.CredentialDetected) {
            // A refusal (never Proceed/Confirm), the same outcome as a credential typed in the
            // box; the guard reason is shown, the matched content never is.
            setBlocked(egressRejectedLabel(gate.reason))
        } else {
            // Reuse the admission's user-visible blocked copy (one source for the reasons).
            val outcome = AttachmentSendAdmission.admit(gate, text, target, strings)
            if (outcome is AttachmentSendAdmission.Outcome.Blocked) {
                setBlocked(outcome.reason)
            } else {
                // UnsupportedType/SnapshotBroken can only block; fail closed if that
                // invariant ever changes.
                setBlocked(str(ConversationR.string.chat_blocked_snapshot_verify_failed))
            }
        }
        return null
    }

    /** A stale disclosure cannot cancel a newer session/revision's pending request. */
    fun cancelSubmission(request: ChatSubmission): kotlinx.coroutines.Deferred<ChatSubmissionReceipt> =
        workScope.async {
            submissionGate.withLock {
                val outcome =
                    if (pendingSubmission == request) {
                        cancelPendingSend()
                        ChatSubmissionOutcome.Rejected("USER_CANCELLED")
                    } else {
                        ChatSubmissionOutcome.Rejected("CONFIRMATION_CHANGED")
                    }
                ChatSubmissionReceipt(request, outcome)
            }
        }

    fun cancelPendingSend() {
        pendingInputResume = null
        pendingSubmission = null
        pendingGoalId = null
        pendingSend = null
        pendingEgress = null
        pendingAttachmentIds = emptyList()
        pendingReference = null
        _screen.update { it.copy(pendingDisclosure = null) }
    }

    /**
     * A stale card cannot stop a newer Turn in the same session. Pause is Goal-only.
     * On the stable-ID match it durably persists CANCELLING before signalling the
     * cancellation (HXA-202 slice 3), so the Tasks dashboard shows "cancelling, awaiting
     * settlement" until the single settlement transaction commits the terminal state.
     */
    @Suppress("TooGenericExceptionCaught") // UI stop failures must be visible without fabricating cancellation success.
    fun stopTask(
        turnId: String,
        pause: Boolean = false,
        systemReason: String? = null,
    ) {
        workScope.launch {
            try {
                stopCommands.stopTask(turnId, pause, systemReason)
                refreshStoppedTurn(turnId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Task stop failed: ${error.javaClass.simpleName}")
                setBlocked(str(ConversationR.string.tool_state_failed))
            }
        }
    }

    /** Stable-ID entry shared by chat, Tasks and the runtime; never resolves a newer live turn. */
    suspend fun stopTurn(turnId: String): com.helix.core.agent.CancelResult =
        agentRuntime.cancel(TurnId(turnId)).also { refreshStoppedTurn(turnId) }

    private fun refreshBackgroundTasks() = dashboards.refreshBackground()

    /** Re-read App-owned dashboard facts after a durable Goal/Plan decision. */
    private suspend fun refreshTaskDashboards() = dashboards.refreshTasks()

    private fun refreshTaskDashboardsNow() {
        workScope.launch { refreshTaskDashboards() }
    }

    /** Explicit native human control. Never changes a target or revives the original driver. */
    @Suppress("TooGenericExceptionCaught") // Surface stop failure without claiming rollback or settlement.
    fun takeOverTask(
        sessionId: String,
        turnId: String,
    ) {
        workScope.launch {
            try {
                submissionGate.withLock {
                    val turn = turnHistory.turn(turnId) ?: return@withLock
                    if (turn.sessionId != sessionId || turnHistory.latestVisible(sessionId)?.id != turnId) {
                        return@withLock
                    }
                    if (turn.state.isTerminal || humanHandoff.state(turnId) != null) {
                        return@withLock
                    }
                    humanHandoff.record(turnId, HumanHandoffState.REQUESTED)
                    stopCommands.stopTask(
                        turnId,
                        pause = goals.goalForTurn(turnId) != null,
                        systemReason = null,
                    )
                    refreshStoppedTurn(turnId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Human takeover stop unconfirmed: ${error.javaClass.simpleName}")
                setBlocked(str(R.string.human_stop_unconfirmed))
            }
        }
    }

    suspend fun handoffPresentation(turnId: String): HumanHandoffUi? =
        withContext(Dispatchers.IO) { humanHandoff.project(turnId) }

    /** Hand-back is fresh human input through existing admission, not retry/resume of a stopped Turn. */
    fun returnHumanControl(
        sessionId: String,
        turnId: String,
        completed: Boolean,
        note: String,
    ) = sessionActions.submit {
        submissionGate.withLock {
            if (openSessionId != sessionId || !humanHandoff.canReturn(sessionId, turnId)) {
                return@withLock ChatSubmissionOutcome.Rejected("HANDOFF_CHANGED")
            }
            if (completed) {
                humanHandoff.record(turnId, HumanHandoffState.FINISHED)
                refreshScreen()
                return@withLock ChatSubmissionOutcome.Rejected("USER_COMPLETED_NO_EXECUTION")
            }
            if (stagedAttachments.isNotEmpty() || pendingSubmission != null || pendingInputResume != null) {
                return@withLock ChatSubmissionOutcome.Rejected("CONFIRMATION_PENDING")
            }
            val context = humanHandoff.continuationContext(turnId)
            val request =
                ChatSubmission(
                    sessionId,
                    ComposerEditClock.next(0),
                    "human-return:$turnId",
                    "I have finished the manual step and explicitly return control. Start by observing the current " +
                        "screen/files again; previous node/frame tokens are obsolete. " +
                        "Do not repeat unknown effects. " +
                        "Stopping the prior task does not prove background-display release " +
                        "or transfer to my main screen. Use fresh host observations.\n" +
                        "User note: $note\n$context",
                )
            // Consume the old button before admission. Failure/confirmation cannot cause an automatic replay.
            humanHandoff.record(turnId, HumanHandoffState.SUBMITTED)
            val outcome = submissionAttempt { admitSubmission(request) }
            refreshScreen()
            outcome
        }
    }

    fun dismissHumanControl(
        sessionId: String,
        turnId: String,
    ) = sessionActions.submit {
        submissionGate.withLock {
            if (humanHandoff.canReturn(sessionId, turnId)) {
                humanHandoff.record(turnId, HumanHandoffState.CANCELLED)
                refreshScreen()
            }
        }
    }

    fun stop(explicitTurnId: String? = null) {
        val turnId = explicitTurnId ?: _screen.value.activeTurn?.id ?: return
        workScope.launch { stopTurn(turnId) }
    }

    fun showBlockedReason(reason: String) {
        setBlocked(reason)
    }

    fun inspectInterruptedSubscription(
        turnId: String,
        modelCallId: String,
        stop: Boolean,
    ) = recovery.inspectInterruptedSubscription(turnId, modelCallId, stop)

    fun recoverInterruptedSubscriptionResult(
        turnId: String,
        modelCallId: String,
    ) = recovery.recoverInterruptedSubscriptionResult(turnId, modelCallId)

    fun inspectInterruptedProot(
        turnId: String,
        callId: String,
        stop: Boolean,
    ) = recovery.inspectInterruptedProot(turnId, callId, stop)

    fun recoverInterruptedProot(
        turnId: String,
        callId: String,
    ) = recovery.recoverInterruptedProot(turnId, callId)

    fun retryProotAcknowledgement(
        turnId: String,
        callId: String,
    ) = recovery.retryProotAcknowledgement(turnId, callId)

    /** HXA-204 slice 2: the turn recovery panel operations (own identity, re-checked admission). */
    fun recoveryReconnect(turnId: String) = turnRecovery.reconnect(turnId)

    fun recoveryQueryResult(turnId: String) = turnRecovery.queryResult(turnId)

    fun recoveryGrantPermission(turnId: String) = turnRecovery.grantPermission(turnId)

    fun recoveryContinueGoal(turnId: String) = turnRecovery.continueGoal(turnId)

    fun recoveryRetryNewCall(turnId: String) = turnRecovery.retryNewCall(turnId)

    fun nativeApprovals(turnId: String) = toolPresentation.nativeApprovals(turnId)

    fun reviewNativeApproval(
        sessionId: String,
        turnId: String,
        approvalId: String,
        approve: Boolean,
    ) = toolCalls.reviewNativeApproval(sessionId, turnId, approvalId, approve)

    fun approveApproval(approvalId: String) = toolCalls.approveApproval(approvalId)

    fun denyApproval(approvalId: String) = toolCalls.denyApproval(approvalId)

    /**
     * Retries the newest FAILED turn: a NEW turn re-sends the SAME user
     * message (already persisted) — an explicit user action, never an
     * automatic replay (doc 02 section 5.2; acceptance scenario #10).
     *
     * ADR-0014 §5 (HXA-049): when the retried turn carries bound attachments, its bound
     * files are re-verified against their bound snapshots BEFORE any new turn starts — the
     * persisted user message already carries the inlined snapshot and is re-sent verbatim,
     * so what the retry re-proves is that the FULL file the message points at is still the
     * bound, credential-clean bytes. A tampered/missing file (or an artifact row that no
     * longer resolves) BLOCKS the retry fail-closed with a user-visible reason and NO new
     * turn row is written. A retried turn WITHOUT bound attachments reproduces today's
     * retry exactly (no regression).
     */
    fun retry() {
        workScope.launch {
            val targetTurnId = _screen.value.retryTargetTurnId ?: return@launch
            val session = currentSession() ?: return@launch
            val source = turnHistory.retrySource(session.id, targetTurnId) ?: return@launch
            val turnId = source.inputTurnId
            val continueResults = source.continueResults
            modelBindingProblem(session)?.let {
                setBlocked(it)
                return@launch
            }
            val providerId = session.providerId ?: return@launch
            if (!providers.chatSelectable(providerId)) {
                setBlocked(str(ConversationR.string.chat_blocked_provider_untested))
                return@launch
            }
            if (!verifyRetryAttachments(session.id, turnId)) return@launch
            submitCheckedRetry(providerId, turnId, continueResults)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun verifyRetryAttachments(
        sessionId: String,
        turnId: String,
    ): Boolean {
        when (val check = attachmentPort.retry(sessionId, turnId, credentialScan)) {
            AttachmentRetryCheck.None -> {
                return true
            }

            AttachmentRetryCheck.Unavailable -> {
                setBlocked(str(R.string.chat_blocked_snapshot_recheck_failed))
                return false
            }

            is AttachmentRetryCheck.Verified -> {
                val gate = check.gate
                if (gate is AttachmentSendDecision.Ready) return true
                val reason =
                    if (gate is AttachmentSendDecision.CredentialDetected) {
                        egressRejectedLabel(gate.reason)
                    } else {
                        str(R.string.chat_blocked_snapshot_recheck_failed)
                    }
                setBlocked(reason)
                return false
            }
        }
    }

    /** Called only after attachment and session checks; Goal keeps its existing activation path. */
    private suspend fun submitCheckedRetry(
        providerId: String,
        turnId: String,
        continueResults: Boolean,
    ) {
        if (continueResults) {
            submitTurn(
                text = str(R.string.budget_continue_prompt),
                providerId = providerId,
                isBudgetContinuation = true,
            )
        } else {
            val goalId = goals.goalForTurn(turnId)
            submitTurn(text = null, providerId = providerId, retryTurnId = turnId, goalId = goalId)
        }
    }

    /**
     * Regenerate the latest assistant turn in the open session.
     * The target assistant message (and any subsequent turn artifacts) are superseded in Room,
     * retaining their audit history while re-driving the model with the original user input.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Session, history and provider checks precede retry.
    fun regenerateLatestTurn(assistantMessageId: String) {
        val clickedSessionId = openSessionId ?: return
        if (turnEngine.liveExecution.hasActive(clickedSessionId)) return
        workScope.launch {
            if (openSessionId != clickedSessionId) return@launch
            val session = requireNotNull(catalog.selection(clickedSessionId))
            val turnId = turnHistory.regenerateSource(session.id, assistantMessageId) ?: return@launch

            modelBindingProblem(session)?.let {
                setBlocked(it)
                return@launch
            }
            val providerId = session.providerId ?: return@launch
            if (!providers.chatSelectable(providerId)) {
                setBlocked(str(ConversationR.string.chat_blocked_provider_untested))
                return@launch
            }

            if (!verifyRetryAttachments(session.id, turnId)) return@launch

            val requestId = idGenerator()
            val goalId = goals.goalForTurn(turnId)
            val started =
                submitTurn(
                    text = null,
                    providerId = providerId,
                    retryTurnId = turnId,
                    goalId = goalId,
                    clientRequestId = requestId,
                    regenerateMessageId = assistantMessageId,
                    expectedSessionId = clickedSessionId,
                )
            if (started) {
                refreshScreen()
            }
        }
    }

    // --------------------------------------------------------------------------------
    // AgentRuntime command adapter — execution ownership lives in TurnEngine.
    // --------------------------------------------------------------------------------

    suspend fun stopScheduledTurn(
        turnId: String,
        sessionId: String,
        reason: String,
    ) {
        stopCommands.stopScheduled(turnId, sessionId, reason)
        refreshStoppedTurn(turnId)
    }

    /** Presentation only: a refusal never selects another session or discards its draft. */
    fun projectSubmission(
        sessionId: String,
        result: TurnSubmissionResult,
    ): String? {
        // Automatic inspection belongs to its original task; never park a session-wide banner.
        if (result is TurnSubmissionResult.Blocked &&
            result.reason == TurnSubmissionBlock.RECOVERY_UNAVAILABLE
        ) {
            return null
        }
        if (result is TurnSubmissionResult.Blocked && openSessionId == sessionId) {
            val label =
                when (result.reason) {
                    TurnSubmissionBlock.PROVIDER_CHANGED, TurnSubmissionBlock.INPUT_CONFIGURATION_CHANGED -> {
                        R.string.chat_blocked_provider_state_changed
                    }

                    TurnSubmissionBlock.SESSION_BUSY -> {
                        R.string.chat_blocked_session_busy
                    }

                    TurnSubmissionBlock.RECEIPT_CONFLICT -> {
                        R.string.turn_submit_conflict
                    }

                    TurnSubmissionBlock.GOAL_REFUSED, TurnSubmissionBlock.CONTINUATION_REFUSED -> {
                        R.string.goal_continue_unavailable
                    }

                    TurnSubmissionBlock.RECOVERY_UNAVAILABLE -> {
                        error("Automatic recovery refusals are projected on their original task")
                    }

                    TurnSubmissionBlock.SESSION_UNAVAILABLE, TurnSubmissionBlock.INPUT_NO_LONGER_CONSUMABLE,
                    TurnSubmissionBlock.CONTINUATION_DEFERRED, TurnSubmissionBlock.INVALID_HISTORY_MUTATION,
                    TurnSubmissionBlock.FRESH_GUARD_REJECTED,
                    -> {
                        ConversationR.string.tool_state_failed
                    }
                }
            setBlocked(str(label))
        }
        return result.turnIdOrNull()
    }

    private fun refreshStoppedTurn(turnId: String) {
        refreshScreen()
        refreshBackgroundTasks()
        refreshTaskDashboardsNow()
        syncGoalReminderForTurn(turnId)
    }

    // --------------------------------------------------------------------------------
    // Turn admission + application projections. Engine owns the live driver.
    // --------------------------------------------------------------------------------

    /**
     * The in-app turn entry (HX2-01): send, confirmed egress, retry and goal start all drive the
     * turn through the unified [agentRuntime] — the command carries the current run-control
     * snapshot (the per-turn facts) and the producer's approved attachment intents. A start the
     * session refused yields a typed [TurnStartBlocked]; this page boundary projects its label
     * while keeping the draft and original session identity.
     */
    @Suppress("SwallowedException", "ReturnCount") // Session binding is checked before the existing runtime gate.
    private suspend fun submitTurn(
        text: String?,
        providerId: String,
        retryTurnId: String? = null,
        attachments: List<AttachmentBindingIntent> = emptyList(),
        references: List<ConversationReferenceSnapshotInput> = emptyList(),
        goalId: String? = null,
        clientRequestId: String? = null,
        isBudgetContinuation: Boolean = false,
        expectedSessionId: String? = null,
        revisedMessageId: String? = null,
        regenerateMessageId: String? = null,
    ): Boolean {
        val session = currentSession() ?: return false
        if (expectedSessionId != null && session.id != expectedSessionId) return false
        val blockedEpoch = _screen.value.blockedReasonEpoch
        val currentControl = sessionRunControls.ensure(session.id, clock.now().toEpochMilli())
        val control =
            if (revisedMessageId != null && currentControl.mode == AgentMode.GOAL) {
                currentControl.copy(mode = AgentMode.CHAT)
            } else {
                currentControl
            }
        return try {
            agentRuntime.submit(
                SubmitTurnCommand(
                    session = SessionId(session.id),
                    providerId = ProviderId(providerId),
                    mode = control.mode,
                    text = text,
                    budgets = control.budgets,
                    chatToolsEnabled = control.chatToolsEnabled,
                    reasoning = control.reasoning,
                    goalId = goalId?.let { GoalId(it) },
                    retryTurnId = retryTurnId?.let { TurnId(it) },
                    attachments = attachments,
                    references =
                        references.map { reference ->
                            ConversationReferenceIntent(
                                sourceSessionId = reference.sourceSessionId,
                                sourceSessionTitle = reference.sourceSessionTitle,
                                selectionKind = reference.selectionKind.name,
                                sourceMessageIds = reference.sourceMessageIds,
                                content = reference.content,
                                contentSha256 = reference.contentSha256,
                            )
                        },
                    // A caller that supplies a stable [clientRequestId] (the confirmed-egress
                    // re-drive) dedups to the turn it already started; every other entry point is a
                    // fresh intent and gets a fresh id.
                    clientRequestId = clientRequestId ?: idGenerator(),
                    revisedMessageId = revisedMessageId,
                    regenerateMessageId = regenerateMessageId,
                    continuousGoal = control.mode == AgentMode.GOAL || goalId != null,
                    goalBudgets = control.goalBudgets,
                    directUserRequest = !isBudgetContinuation && retryTurnId == null && !text.isNullOrBlank(),
                ),
            )
            // A turn row was committed and its loop launched: the start truly happened.
            clearAcceptedSubmissionBlock(session.id, blockedEpoch)
            true
        } catch (e: TurnStartBlocked) {
            projectSubmission(e.sessionId, TurnSubmissionResult.Blocked(e.reason))
            false
        }
    }

    private fun submissionBlocked(reason: String): ChatSubmissionOutcome.Rejected {
        setBlocked(reason)
        return ChatSubmissionOutcome.Rejected(reason)
    }

    @Suppress("ReturnCount") // Distinct accepted input and legacy explicit retry/revision admission.
    private suspend fun submitMessageTurn(
        submission: ChatSubmission,
        text: String,
        providerId: String,
        goalId: String?,
        attachments: List<AttachmentBindingIntent> = emptyList(),
        reference: ConversationReferenceSnapshotInput? = null,
    ): ChatSubmissionOutcome {
        if (reference != null) {
            try {
                require(conversationReferences.matches(reference))
            } catch (_: IllegalArgumentException) {
                return submissionBlocked(str(ConversationR.string.chat_blocked_reference_unavailable))
            }
        }
        if (submission.revisedMessageId == null && goalId == null) {
            return acceptSessionInput(submission, providerId, attachments, reference)
        }
        if (submission.knowledgeReferences.isNotEmpty()) {
            return submissionBlocked(
                str(R.string.knowledge_reference_new_input),
            )
        }
        if (submission.skillActivations.isNotEmpty()) return submissionBlocked("SKILL_ACTIVATION_REQUIRES_NEW_INPUT")
        val started =
            submitTurn(
                text,
                providerId,
                attachments = attachments,
                references = listOfNotNull(reference),
                goalId = goalId,
                clientRequestId = submission.clientRequestId,
                expectedSessionId = submission.sessionId,
                revisedMessageId = submission.revisedMessageId,
            )
        return if (!started) {
            ChatSubmissionOutcome.Rejected("TURN_NOT_ACCEPTED")
        } else {
            val turn =
                requireNotNull(submissionHistory.acceptedTurnId(submission.clientRequestId, submission.sessionId))
            ChatSubmissionOutcome.Accepted(turn)
        }
    }

    override fun observeSessionInputDelivery(sessionId: String): kotlinx.coroutines.flow.Flow<Long> =
        inputCommands.observeDeliveryRevision(sessionId)

    /** Same accepted input, explicitly delivered to the displayed live Turn at its next boundary. */
    override fun sendQueuedInputNow(
        inputId: String,
        expectedRevision: Long,
        expectedTurnId: String,
    ): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            submissionGate.withLock {
                val input = inputCommands.receipt(inputId) ?: return@withLock false
                val confirmationPending = pendingSubmission != null || pendingInputResume != null
                val originalQueue = input.isPending && input.delivery == SessionInputDelivery.QUEUE
                val sameInput = input.sessionId == openSessionId && input.revision == expectedRevision
                if (!sameInput || !originalQueue || confirmationPending) {
                    return@withLock false
                }
                val live = synchronized(turnGate) { turnEngine.liveExecution.active(input.sessionId)?.turnId }
                if (live != expectedTurnId) return@withLock false
                // Revalidate the proposed target without altering the persisted queue identity.
                if (sessionInputDelivery.revalidateForSteering(input.key, expectedTurnId) == null) return@withLock false
                synchronized(turnGate) {
                    if (input.sessionId != openSessionId ||
                        turnEngine.liveExecution.active(input.sessionId)?.turnId != expectedTurnId
                    ) {
                        false
                    } else {
                        inputCommands.steer(input.key, expectedTurnId, clock.now().toEpochMilli())
                    }
                }
            }
        }

    override fun pendingInputViews(sessionId: String): kotlinx.coroutines.Deferred<List<PendingInputView>> =
        workScope.async {
            inputCommands.pendingViews(sessionId)
        }

    override fun readSessionInput(inputId: String): kotlinx.coroutines.Deferred<String?> =
        workScope.async {
            inputCommands.readText(inputId)
        }

    override fun withdrawSessionInput(
        inputId: String,
        expectedRevision: Long,
    ): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            submissionGate.withLock {
                val input = inputCommands.receipt(inputId) ?: return@withLock false
                val withdrawn =
                    synchronized(turnGate) {
                        inputCommands.withdraw(
                            InputDeliveryKey(inputId, input.sessionId, expectedRevision),
                            clock.now().toEpochMilli(),
                        )
                    }
                if (withdrawn) requestSessionDrain(input.sessionId)
                withdrawn
            }
        }

    override fun editSessionInput(
        inputId: String,
        expectedRevision: Long,
        text: String,
    ): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            submissionGate.withLock {
                val input = inputCommands.receipt(inputId) ?: return@withLock false
                val invalidShape = text.length > MAX_MODEL_TEXT_CHARS || '\u0000' in text
                val invalidText = invalidShape || credentialScan(text) != null
                if (invalidText) return@withLock false
                synchronized(turnGate) {
                    inputCommands.editTextAndPark(
                        InputDeliveryKey(inputId, input.sessionId, expectedRevision),
                        text,
                        clock.now().toEpochMilli(),
                    )
                }
            }
        }

    override fun resumeSessionInput(
        inputId: String,
        expectedRevision: Long,
    ): kotlinx.coroutines.Deferred<ChatSubmissionOutcome> =
        workScope.async {
            submissionGate.withLock {
                submissionAttempt {
                    val input =
                        inputCommands.receipt(inputId)
                            ?: return@submissionAttempt ChatSubmissionOutcome.Rejected("INPUT_NOT_FOUND")
                    if (openSessionId != input.sessionId || pendingSubmission != null || pendingInputResume != null) {
                        return@submissionAttempt ChatSubmissionOutcome.Rejected("CONFIRMATION_PENDING")
                    }
                    if (input.revision != expectedRevision || validatedInput(input) == null) {
                        return@submissionAttempt ChatSubmissionOutcome.Rejected("INPUT_REVALIDATION_FAILED")
                    }
                    prepareInputResumeConfirmation(input)
                }
            }
        }

    fun pendingSessionInputResume(): Pair<String, Long>? = pendingInputResume?.input?.let { it.inputId to it.revision }

    fun confirmSessionInputResume(
        inputId: String,
        revision: Long,
    ): kotlinx.coroutines.Deferred<ChatSubmissionOutcome> =
        workScope.async {
            submissionGate.withLock {
                submissionAttempt {
                    val approval =
                        pendingInputResume
                            ?: return@submissionAttempt ChatSubmissionOutcome.Rejected("CONFIRMATION_CHANGED")
                    if (approval.input.inputId != inputId || approval.input.revision != revision ||
                        approval.input.sessionId != openSessionId
                    ) {
                        return@submissionAttempt ChatSubmissionOutcome.Rejected("CONFIRMATION_CHANGED")
                    }
                    cancelPendingSend()
                    val input =
                        inputCommands.receipt(inputId)
                            ?: return@submissionAttempt ChatSubmissionOutcome.Rejected("INPUT_NOT_FOUND")
                    if (input != approval.input || validatedInput(input) == null ||
                        providers.egressTargetFor(input.providerId) != approval.target
                    ) {
                        return@submissionAttempt ChatSubmissionOutcome.Rejected("INPUT_REVALIDATION_FAILED")
                    }
                    resumeValidatedInput(input)
                }
            }
        }

    fun cancelSessionInputResume(
        inputId: String,
        revision: Long,
    ): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            submissionGate.withLock {
                val pending = pendingInputResume?.input
                if (pending?.inputId != inputId || pending.revision != revision) return@withLock false
                cancelPendingSend()
                true
            }
        }

    private suspend fun prepareInputResumeConfirmation(input: AcceptedInputReceipt): ChatSubmissionOutcome {
        val target = providers.egressTargetFor(input.providerId)
        val admission =
            acceptedInputDisclosure.disclosure(input.key, target, strings)
        if (openSessionId != input.sessionId) return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        return when (admission) {
            is AttachmentSendAdmission.Outcome.Blocked -> {
                ChatSubmissionOutcome.Rejected(admission.reason)
            }

            is AttachmentSendAdmission.Outcome.Egress -> {
                when (val decision = admission.decision) {
                    EgressDisclosure.Decision.Proceed -> {
                        resumeValidatedInput(input)
                    }

                    is EgressDisclosure.Decision.Rejected -> {
                        ChatSubmissionOutcome.Rejected(egressRejectedLabel(decision.reason))
                    }

                    is EgressDisclosure.Decision.Confirm -> {
                        pendingInputResume = InputResumeConfirmation(input, target)
                        _screen.update { it.copy(pendingDisclosure = decision.summary, blockedReason = null) }
                        ChatSubmissionOutcome.PendingConfirmation
                    }
                }
            }
        }
    }

    private fun resumeValidatedInput(input: AcceptedInputReceipt): ChatSubmissionOutcome {
        val resumed =
            synchronized(turnGate) {
                inputCommands.resume(input.key, clock.now().toEpochMilli())
            }
        if (!resumed) return ChatSubmissionOutcome.Rejected("INPUT_CHANGED")
        requestSessionDrain(input.sessionId)
        return ChatSubmissionOutcome.Enqueued(input.inputId)
    }

    /** Egress has already been approved for these exact bytes and provider before this admission. */
    @Suppress("ReturnCount") // Activation rejection precedes acceptance; original delivery outcomes remain.
    private suspend fun acceptSessionInput(
        request: ChatSubmission,
        providerId: String,
        attachments: List<AttachmentBindingIntent>,
        reference: ConversationReferenceSnapshotInput?,
    ): ChatSubmissionOutcome {
        if (!validKnowledgeReferences(
                request,
            )
        ) {
            return submissionBlocked(str(ConversationR.string.knowledge_reference_changed))
        }
        if (!validSkillActivations(request.sessionId, request.skillActivations)) {
            return submissionBlocked("SKILL_ACTIVATION_UNAVAILABLE")
        }
        val accepted = persistSessionInput(request, providerId, attachments, reference)
        if (accepted is InputAcceptance.Rejected) return ChatSubmissionOutcome.Rejected(accepted.reason)
        refreshSessions()
        val input = (accepted as InputAcceptance.Accepted).receipt
        synchronized(turnGate) {
            val interruptedOwner =
                !turnEngine.liveExecution.hasActive(input.sessionId) &&
                    turnEngine.sessionBlocker(input.sessionId) != null
            if (interruptedOwner) {
                inputCommands.park(
                    input.key,
                    "SESSION_NEEDS_ATTENTION",
                    clock.now().toEpochMilli(),
                )
            }
        }
        // A fresh user action can start after a Stop without releasing older parked inputs.
        val bypassParked =
            synchronized(turnGate) {
                val noActiveTurn = !turnEngine.liveExecution.hasActive(input.sessionId)
                noActiveTurn && inputCommands.queueHeadNeedsAttention(input.sessionId)
            }
        if (input.delivery == SessionInputDelivery.QUEUE) consumeAcceptedInput(input, !bypassParked)
        requestSessionDrain(input.sessionId)
        val current = requireNotNull(inputCommands.receipt(input.inputId))
        return current.consumedTurnId?.let { ChatSubmissionOutcome.Accepted(it) }
            ?: ChatSubmissionOutcome.Enqueued(current.inputId)
    }

    private suspend fun validKnowledgeReferences(request: ChatSubmission): Boolean =
        request.knowledgeReferences.isEmpty() || knowledge.valid(request.knowledgeReferences)

    private suspend fun persistSessionInput(
        request: ChatSubmission,
        providerId: String,
        attachments: List<AttachmentBindingIntent>,
        reference: ConversationReferenceSnapshotInput?,
    ): InputAcceptance {
        val session = requireNotNull(catalog.selection(request.sessionId))
        val facts = providerSnapshot(providerId, session.modelId)
        val modelId = session.modelId ?: providers.defaultModel(providerId)
        val selected = sessionRunControls.ensure(request.sessionId, clock.now().toEpochMilli())
        return synchronized(turnGate) {
            val active = turnEngine.liveExecution.active(request.sessionId)
            val stopped = active?.let { turnHistory.turn(it.turnId)?.state == TurnState.CANCELLING } == true
            if (stopped) return@synchronized InputAcceptance.Rejected("TURN_CANCELLING")
            val control =
                inputControlForAcceptance(request, active, selected)
                    ?: return@synchronized InputAcceptance.Rejected("STEER_TARGET_NOT_LIVE")
            if (request.delivery == SessionInputDelivery.STEER) {
                val originalCall = turnHistory.steeringTarget(requireNotNull(request.expectedTurnId))
                if (active?.turnId != request.expectedTurnId || originalCall?.providerSnapshot != facts) {
                    return@synchronized InputAcceptance.Rejected("INPUT_CONFIGURATION_CHANGED")
                }
            }
            val configuration =
                SessionInputBinding.configuration(
                    request,
                    providerId,
                    modelId,
                    facts,
                    control,
                    if (request.delivery == SessionInputDelivery.STEER) control.mode.name else selected.mode.name,
                )
            inputCommands.acceptAndName(
                SessionInputSpec(
                    request.clientRequestId,
                    request.sessionId,
                    request.delivery,
                    request.expectedTurnId,
                    request.revision,
                    request.text,
                    attachments.map { InputAttachment(it.artifactId, it.boundSha256) },
                    configuration,
                    clock.now().toEpochMilli(),
                    reference = reference,
                    skillActivations = request.skillActivations,
                    knowledgeReferences = request.knowledgeReferences,
                ),
                automaticSessionTitle(request.text).ifBlank { str(R.string.chat_attachment_button) },
            )
        }
    }

    private fun inputControlForAcceptance(
        request: ChatSubmission,
        active: com.helix.core.agent.runtime.LiveTurnHandle?,
        selected: RunControlConfig,
    ): RunControlConfig? =
        when {
            request.delivery == SessionInputDelivery.STEER -> sessionInputDelivery.steerControl(request.expectedTurnId)

            selected.mode == AgentMode.GOAL &&
                (
                    active != null ||
                        goalRuntime.hasActivation(request.sessionId)
                ) -> selected.copy(mode = AgentMode.ACT)

            else -> selected
        }

    fun validSkillActivations(
        session: String,
        activations: List<com.helix.core.model.input.InputSkillActivation>,
    ): Boolean = toolCalls.validSkillActivations(session, activations)

    @Suppress("TooGenericExceptionCaught") // Admission already committed: retain its receipt and park failed delivery.
    private suspend fun consumeAcceptedInput(input: AcceptedInputReceipt, requireHead: Boolean) {
        try {
            consumeQueueInput(input.key, requireHead)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            synchronized(turnGate) {
                inputCommands.park(
                    input.key,
                    "INPUT_DELIVERY_FAILED",
                    clock.now().toEpochMilli(),
                )
            }
            Log.e(TAG, "Accepted input delivery failed", error)
        }
    }

    private suspend fun consumeQueueInput(
        key: InputDeliveryKey,
        requireHead: Boolean = true,
    ): String? = sessionInputDelivery.consumeQueue(key, requireHead)

    private suspend fun validatedInput(input: AcceptedInputReceipt) = sessionInputDelivery.revalidate(input.key)

    fun bindInputAuthority(
        sessionId: String,
        turnId: String,
        modelCallId: String,
        messageIds: Set<String>,
    ) = goalRuntime.bindInputAuthority(sessionId, turnId, modelCallId, messageIds)

    /** Every wake queues on the same admission mutex; a wake arriving during drain is never lost. */
    @Suppress("TooGenericExceptionCaught")
    // Background admission failure parks inputs rather than silently dropping them.
    private fun requestSessionDrain(sessionId: String, handoffTurnId: String? = null) {
        sessionWorkScheduler.requestDrain(sessionId, handoffTurnId)
    }

    /** Presentation consumes the immutable text already reduced by Core. */
    fun publishModelText(
        turnId: String,
        text: String,
    ) {
        publishTurn(TurnUi(turnId, TurnState.RECEIVING_MODEL, text, null, false))
    }

    fun executionHooks(sessionId: String): TurnExecutionHooks =
        object : TurnExecutionHooks {
            override fun beforeExecution(turnId: String) {
                if (openSessionId == sessionId) {
                    refreshScreen()
                    publishTurn(TurnUi(turnId, TurnState.WAITING_MODEL, null, null, false))
                }
            }

            override fun beforeReviewRelease(turnId: String): String? {
                synchronized(turnGate) {
                    turnEngine.clearSystemStop(turnId)
                    toolCalls.finishTurn(turnId)
                }
                sessionWorkScheduler.disarm(sessionId)
                return str(R.string.tool_failure_requires_review)
            }

            override fun afterReviewRelease(turnId: String) {
                requestAutomaticRecovery(turnId)
                publishTurn(
                    TurnUi(
                        turnId,
                        TurnState.NEEDS_REVIEW,
                        null,
                        str(R.string.tool_failure_requires_review),
                        false,
                    ),
                )
                refreshScreen()
                refreshBackgroundTasks()
                syncGoalReminderForTurn(turnId)
            }

            override fun beforeTerminalRelease(
                turnId: String,
                outcome: ModelStreamTerminal,
                release: com.helix.core.agent.turn.TurnTerminalReleaseReceipt,
            ): TurnTerminalProjection =
                synchronized(turnGate) {
                    var completed = false
                    try {
                        endTurnSettlement(turnId)
                        release.discardedGoals.forEach { goalRuntime.disarmGoal(it.sessionId, it.goalId) }
                        goalRuntime.releaseTurnAuthority(turnId)
                        val continueDelivery =
                            sessionWorkScheduler.prepareAfterTerminal(
                                sessionId,
                                turnId,
                                outcome.state,
                            )
                        completed = true
                        TurnTerminalProjection(
                            continueDelivery = continueDelivery,
                            errorLabel = terminalLabel(outcome.state, outcome.errorCode, turnId),
                        )
                    } finally {
                        if (!completed) {
                            goalRuntime.releaseTurnAuthority(turnId)
                            sessionWorkScheduler.disarm(sessionId)
                        }
                    }
                }

            override fun afterTerminalRelease(
                turnId: String,
                outcome: ModelStreamTerminal,
                continueDelivery: Boolean,
            ) {
                publishTurn(
                    TurnUi(
                        turnId,
                        outcome.state,
                        null,
                        terminalLabel(outcome.state, outcome.errorCode, turnId),
                        outcome.state == TurnState.FAILED,
                    ),
                )
                refreshScreen()
                syncGoalReminderForTurn(turnId)
                if (continueDelivery) requestSessionDrain(sessionId, turnId)
            }

            override fun beforeUnknownRelease(turnId: String) {
                synchronized(turnGate) {
                    turnEngine.clearSystemStop(turnId)
                    toolCalls.finishTurn(turnId)
                }
                sessionWorkScheduler.disarm(sessionId)
            }

            override fun afterUnknownRelease(turnId: String) {
                requestAutomaticRecovery(turnId)
                refreshScreen()
                refreshBackgroundTasks()
                syncGoalReminderForTurn(turnId)
            }
        }

    /**
     * The post-settlement per-turn cleanup shared by the live unwind ([terminalize]) and the
     * parked-turn discard ([cancelTurn]): when this runs the terminal row and its goal
     * settlement are already durable — only in-memory per-turn state is released here.
     */
    private fun endTurnSettlement(turnId: String) {
        // HXA-036: the turn is over — clear the dispatcher's same-turn denial set for it
        // (a later turn may re-request a previously denied action and get a fresh card)
        // and drop this turn's pipeline state.
        toolCalls.endTurn(turnId)
        toolCalls.finishTurn(turnId)
    }

    private fun syncGoalReminderForTurn(turnId: String) {
        try {
            goals.syncReminderForTurn(turnId)
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Goal reminder sync failed after durable turn settlement", error)
            setBlocked(str(R.string.goal_reminder_sync_failed))
        }
    }

    // --------------------------------------------------------------------------------
    // Screen state
    // --------------------------------------------------------------------------------

    /**
     * The open-session id this refresh should render, or null for the session list. The open
     * id is IN-MEMORY (never persisted), so it can name a session row that does not exist at
     * refresh time: opened by id before the row was persisted, or orphaned by a storage loss
     * (sessions are archived, never deleted — a future retention wipe, if ever authorized,
     * would orphan it too). Such a refresh must degrade to the session list, never throw on
     * this work-scope coroutine — an uncaught exception there kills the whole app process.
     */
    @Suppress("ReturnCount") // Ephemeral drafts must exit before the persisted orphan recovery path.
    private fun resolvableOpenSessionId(): String? {
        val captured =
            synchronized(screenNavigationGate) {
                val id = openSessionId ?: return null
                if (sessionDraft?.session?.id == id) return null
                id to screenReadRevision.get()
            }
        // A failed read propagates. Only a successful missing-row read can close this identity.
        val exists = catalog.selection(captured.first) != null
        return synchronized(screenNavigationGate) {
            if (openSessionId != captured.first || screenReadRevision.get() != captured.second ||
                sessionDraft?.session?.id == captured.first
            ) {
                return@synchronized null
            }
            if (exists) {
                captured.first
            } else {
                if (openSession.compareAndSet(captured.first, null)) screenReadRevision.incrementAndGet()
                null
            }
        }
    }

    fun refreshScreen() {
        refreshBackgroundTasks()
        sessionDraft?.takeIf { it.session.id == openSessionId }?.let { draft ->
            val refreshed =
                EMPTY_SCREEN.copy(
                    sessions = sessions.value,
                    openSessionId = draft.session.id,
                    sessionTitle = draft.session.title,
                    selectedModel =
                        historyReader.selectedModel(
                            draft.session.providerId,
                            draft.session.modelId,
                        ),
                    modelSelectionSnapshot =
                        SessionModelSelectionSnapshot(
                            draft.session.providerId,
                            draft.session.modelId,
                            draft.control.reasoning,
                        ),
                    modelBindingIssue =
                        providers.bindingIssue(
                            draft.session.providerId,
                            draft.session.modelId,
                        ),
                    isDraft = true,
                    contextUsage = ChatContextUsage.emptyConversation(),
                    preparingDraft = preparingDraft,
                    directoryRef = draft.session.directoryRef,
                    projectId = draft.project?.id,
                    blockedReason = _screen.value.takeIf { it.openSessionId == draft.session.id }?.blockedReason,
                    blockedReasonEpoch = _screen.value.blockedReasonEpoch,
                    isNewConversation = true,
                    pendingAttachments =
                        draft.attachments.map {
                            PendingAttachmentUi(it.id, it.name, it.size, !it.isImage, it.isImage)
                        },
                )
            _screen.update { if (openSessionId == draft.session.id) refreshed else it }
            return
        }
        refreshPersistedScreen()
        recovery.collectAutomatically()
    }

    private var historySearchJob: kotlinx.coroutines.Job? = null
    private val historySearchRevision =
        java.util.concurrent.atomic
            .AtomicLong()

    // A read failure retains the visible history and reports it; cancellation must still propagate.
    @Suppress("TooGenericExceptionCaught")
    fun findHistoryMessage(
        query: String,
        before: Long?,
        after: Long?,
    ) {
        synchronized(screenNavigationGate) { screenReadRevision.incrementAndGet() }
        val revision = historySearchRevision.incrementAndGet()
        historySearchJob?.cancel()
        val sessionId = openSessionId ?: return
        historySearchJob =
            workScope.launch {
                val focus =
                    try {
                        turnHistory.locate(sessionId, query, before, after)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "History search failed", error)
                        _screen.update { current ->
                            if (openSessionId == sessionId && current.openSessionId == sessionId &&
                                historySearchRevision.get() == revision
                            ) {
                                current.copy(historyReadFailed = true)
                            } else {
                                current
                            }
                        }
                        return@launch
                    } ?: return@launch
                kotlin.coroutines.coroutineContext.ensureActive()
                _screen.update { current ->
                    if (openSessionId == sessionId && current.openSessionId == sessionId &&
                        historySearchRevision.get() == revision
                    ) {
                        current.copy(
                            historyLimit = 100,
                            historyThrough = focus.through,
                            focusedHistoryMessage = focus.messageId,
                            historyFocusRevision = revision,
                        )
                    } else {
                        current
                    }
                }
                refreshPersistedScreen()
            }
    }

    fun showLatestMessages() {
        synchronized(screenNavigationGate) { screenReadRevision.incrementAndGet() }
        historySearchRevision.incrementAndGet()
        historySearchJob?.cancel()
        _screen.update { it.copy(historyLimit = 100, historyThrough = Long.MAX_VALUE, focusedHistoryMessage = null) }
        workScope.launch { refreshPersistedScreen() }
    }

    fun loadOlderMessages() {
        synchronized(screenNavigationGate) { screenReadRevision.incrementAndGet() }
        historySearchRevision.incrementAndGet()
        historySearchJob?.cancel()
        val expected = _screen.value
        if (!expected.hasOlderMessages) return
        workScope.launch {
            _screen.update { current ->
                if (current.openSessionId == expected.openSessionId && current.historyLimit == expected.historyLimit) {
                    current.copy(historyLimit = Math.addExact(current.historyLimit, 100))
                } else {
                    current
                }
            }
            refreshPersistedScreen()
        }
    }

    private fun refreshPersistedScreen() {
        val request =
            synchronized(screenNavigationGate) {
                ConversationScreenRequest.capture(openSessionId, _screen.value, screenReadRevision.incrementAndGet())
            }
        val snapshot = readPersistedScreen(request) ?: return
        synchronized(screenNavigationGate) {
            if (openSessionId != request.history.sessionId ||
                sessionDraft?.let { it.session.id == openSessionId } == true
            ) {
                return@synchronized
            }
            val applied = applyPersistedScreen(snapshot)
            if (!applied || !snapshot.sessionMissing) return@synchronized
            if (screenReadRevision.get() == request.history.revision &&
                openSession.compareAndSet(request.history.sessionId, null)
            ) {
                screenReadRevision.incrementAndGet()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed read preserves the visible conversation and navigation owner.
    private fun readPersistedScreen(request: ConversationScreenRequest): ConversationScreenSnapshot? =
        try {
            screenReader.read(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Conversation display read failed", failure)
            _screen.update { current ->
                if (openSessionId == request.history.sessionId && request.matches(current, screenReadRevision.get())) {
                    current.copy(historyReadFailed = true)
                } else {
                    current
                }
            }
            null
        }

    /** Caller holds screenNavigationGate; retrying the projection never repeats a repository read. */
    private fun applyPersistedScreen(snapshot: ConversationScreenSnapshot): Boolean {
        while (true) {
            val current = _screen.value
            val projected = projection.applyScreen(snapshot, current, screenReadRevision.get())
            if (projected === current) return false
            val refreshed =
                projected.copy(
                    sessions = sessions.value,
                    preparingDraft = preparingDraft,
                    pendingAttachments = stagedAttachmentsUi(),
                    shareDraftText = shareDraftText,
                    historyReadFailed = false,
                )
            if (_screen.compareAndSet(current, refreshed)) return true
        }
    }

    /**
     * The staged attachments as UI facts (ADR-0014 §5): the sanitized artifact id (the
     * removal address — never a filesystem path), the display name and the size. No real
     * path, hash or workspace detail crosses into the observable state.
     */
    private fun stagedAttachmentsUi(): List<PendingAttachmentUi> =
        stagedAttachments.map { entry ->
            PendingAttachmentUi(
                id = entry.artifactId,
                fileName = entry.fileName,
                sizeBytes = entry.sizeBytes,
                isText = entry.normalizedArtifactId == null && entry.imageSendError == null,
                isImage = entry.normalizedArtifactId != null || entry.imageSendError != null,
            )
        }

    private fun publishTurn(turn: TurnUi) {
        turnEngine.publishObservation(
            TurnObservation(
                turnId = turn.id,
                state = turn.state,
                streamingText = turn.streamingText,
                errorLabel = turn.errorLabel,
                retryable = turn.retryable,
            ),
        )
        dashboards.onTurnChanged(turn.id, turn.state)
        val expectedTurnId = _screen.value.activeTurn?.id
        val persisted = turnHistory.visibleObservationTarget(turn.id) ?: return
        _screen.update { current ->
            com.helix.feature.conversation.presentation.applyTurnObservation(
                current,
                expectedTurnId,
                persisted.sessionId,
                persisted.id,
                persisted.state,
                persisted.modelStep,
                turn,
            )
        }
    }

    private suspend fun modelBindingProblem(session: ConversationSessionSelection): String? =
        providers.validateBinding(session.providerId, session.modelId)?.let {
            str(it.messageResource)
        }

    private fun setBlocked(reason: String) {
        _screen.update { it.copy(blockedReason = reason, blockedReasonEpoch = it.blockedReasonEpoch + 1) }
    }

    private fun clearAcceptedSubmissionBlock(
        sessionId: String,
        epoch: Long,
    ) {
        _screen.update { clearAcceptedSubmissionBlock(it, sessionId, epoch) }
    }

    private fun currentSession() = openSessionId?.let { requireNotNull(catalog.selection(it)) }

    private suspend fun providerSnapshot(
        providerId: String,
        modelId: String?,
    ): String = providers.snapshot(providerId, modelId)

    private companion object {
        const val TAG = "HelixChat"

        val EMPTY_SCREEN =
            ChatScreenState(
                sessions = emptyList(),
                openSessionId = null,
                selectedModel = null,
                messages = emptyList(),
                toolTimeline = emptyList(),
                activeTurn = null,
                pendingDisclosure = null,
                blockedReason = null,
                retryTargetTurnId = null,
                pendingAttachments = emptyList(),
            )
    }
}

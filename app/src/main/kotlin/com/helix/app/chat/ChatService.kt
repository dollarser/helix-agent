package com.helix.app.chat

import android.util.Log
import com.helix.app.R
import com.helix.app.agent.AgentLoop
import com.helix.app.agent.BufferedModelToolCall
import com.helix.app.agent.ChatContextProjection
import com.helix.app.agent.ChatContextRequest
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.GoalRunSettlement
import com.helix.app.agent.GoalTimeBudget
import com.helix.app.agent.LocalToolCallBatch
import com.helix.app.agent.MAX_MODEL_TEXT_CHARS
import com.helix.app.agent.ModelStreamState
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.SettledCall
import com.helix.app.agent.TurnCancelSignal
import com.helix.app.agent.TurnContextAssembler
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnInputDelivery
import com.helix.app.agent.TurnMessageDraft
import com.helix.app.agent.TurnStartSpec
import com.helix.app.agent.TurnSteeringDraft
import com.helix.app.agent.TurnToolExecutor
import com.helix.app.agent.UnresolvedEffectPolicy
import com.helix.app.chat.ChatAttachmentRetry.RetryStagedCheck
import com.helix.app.engine.EngineCancelDecision
import com.helix.app.engine.TurnEngine
import com.helix.app.engine.TurnExecutionHooks
import com.helix.app.engine.TurnExecutionRequest
import com.helix.app.engine.TurnObservation
import com.helix.app.engine.TurnTerminalProjection
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.plan.PlanReview
import com.helix.app.plan.PlanReviewService
import com.helix.app.plan.StoragePlanReviewPort
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ProviderService
import com.helix.app.provider.SubscriptionRecoveredOutput
import com.helix.app.provider.SubscriptionRecoveryStatus
import com.helix.app.runcontrol.PersistedRunControlStore
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.runcontrol.RunControlStore
import com.helix.app.runcontrol.SessionRunControlStore
import com.helix.app.runcontrol.TurnBudgetBounds
import com.helix.app.todo.TaskLedgerProjection
import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.AgentRuntime
import com.helix.core.agent.AttachmentBindingIntent
import com.helix.core.agent.ConversationReferenceIntent
import com.helix.core.agent.GoalWakeReason
import com.helix.core.agent.SubmitTurnCommand
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
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.policy.NetworkOriginScope
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.repository.ConversationReferenceKind
import com.helix.core.storage.repository.ConversationReferenceSnapshotInput
import com.helix.core.storage.repository.ExpertProfile
import com.helix.core.storage.repository.InputAttachment
import com.helix.core.storage.repository.MessageAttachmentRepository
import com.helix.core.storage.repository.SessionInputAcceptResult
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputSpec
import com.helix.core.storage.repository.SessionInputState
import com.helix.core.storage.repository.SessionSearchMatchKind
import com.helix.core.workspace.FileScopePath
import com.helix.feature.files.AttachmentClassifier
import com.helix.feature.files.AttachmentImportResult
import com.helix.feature.files.AttachmentMaterialization
import com.helix.feature.files.AttachmentSendDecision
import com.helix.feature.files.AttachmentSendGate
import com.helix.feature.files.ImportRefusal
import com.helix.feature.files.ImportStatus
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.StagedAttachment
import com.helix.provider.api.ProviderCapabilities
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.ToolDispatchOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** The artifact center's files section is a digest, not the file manager (that is the Files page). */
private const val ARTIFACT_FILES_LIMIT = 50

/** Session-search debounce: one bounded scan per pause of typing, on the service's IO scope. */
private const val SEARCH_DEBOUNCE_MILLIS = 200L

/**
 * The chat service (HXA-028): owns the chat send path end to end. The UI
 * dispatches intents ([send]/[stop]/[retry]/…) and observes [sessions] +
 * [screen] — it NEVER holds a network Job (doc 02 section 12; HXA-028 task
 * text): the streaming Job lives in this service's scope, one active turn per
 * session (doc 02: single SessionTurnCoordinator, Mutex).
 *
 * Persistence (doc 02 section 5.3: stream events are persisted as they are
 * received, under the storage API available in M2):
 * - the user message row is persisted BEFORE the request is sent (a process
 *   death never loses the committed user message, NFR-004);
 * - the turn row and the model-call row are persisted before and during the
 *   stream (state transitions + usage at the terminal);
 * - the assistant content row is persisted at the terminal (the M2 storage
 *   API has no message-content update; the in-flight text is observable via
 *   [TurnUi.streamingText] and survives only as committed content from the
 *   terminal on — an interrupted process parks the turn, no blind replay).
 *
 * This facade owns session admission and live Turn orchestration. Tool dispatch/approval
 * and recovery actions have explicit collaborators; UI state updates remain atomic.
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
class ChatService(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    profileStore: SafetyProfileStore,
    private val runControlStore: RunControlStore =
        PersistedRunControlStore(InMemoryLineStore()).also { it.setMode(AgentMode.ACT) },
    private val conversationLaunchStore: ConversationLaunchStore = ConversationLaunchStore(InMemoryLineStore()),
    private val toolPipeline: ToolPipeline,
    private val clock: Clock = SystemClock(),
    private val idGenerator: () -> String,
    private val turnEngine: TurnEngine = TurnEngine(storage, clock, idGenerator),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val attachmentStaging: AttachmentStagingSupport,
    /**
     * HXA-055: binds the session for the in-flight turn build BEFORE the model stream — the
     * protocol image resolver runs inside `provider.stream`, and the shared production image
     * source (app-private artifacts, session-scoped, fail-closed) refuses to resolve without
     * the bound session. Production passes the source's [bindSession]; a no-op keeps test
     * services without image artifacts fail-closed on their own (their resolver throws).
     */
    private val visionSessionBinder: (String) -> Unit = {},
    /**
     * HXA-069: resolves the string-resource IDs this service emits (approval codes, terminal
     * labels, tool states, the egress blocks) to the current locale — ChatService is pure JVM
     * (no [android.content.Context]), so the production container injects a locale-aware
     * resolver. The default is a stable placeholder so JVM/device construction without a locale
     * still compiles.
     */
    private val strings: (Int, Array<out Any>) -> String = { resId, _ -> "§$resId" },
    private val lanScopes: () -> Set<NetworkOriginScope> = { emptySet() },
    private val subscriptionResultRecovery: (
        String,
        String,
        Boolean,
    ) -> SubscriptionRecoveredOutput? =
        { _, _, _ -> null },
    private val subscriptionRecovery: (String, String, Boolean) -> SubscriptionRecoveryStatus =
        { _, _, _ -> SubscriptionRecoveryStatus.UNKNOWN },
    private val goalReminderSync: (String) -> Unit = {},
    /**
     * P1 (research doc section 8): resolves the session workspace's project-instruction file to
     * the bounded, trust-framed PROJECT section of the goal system prompt. The production
     * container injects the workspace reader; the default yields "" so JVM/device construction
     * without a workspace behaves exactly as before.
     */
    private val bindSessionDirectory: (String) -> String = { it },
    private val projectInstructionsReader: (com.helix.core.workspace.FileScopePath) -> String = { "" },
    private val memory: com.helix.app.memory.MemoryService? = null,
) {
    // The unified AgentRuntime (HX2-01): every in-app turn entry drives the turn through this —
    // none reaches launchTurn directly. The container re-exposes the SAME instance as the
    // production entry point (AppContainer.agentRuntime).
    internal val agentRuntime: AgentRuntime =
        AppAgentRuntime(
            startTurn = ::startRuntimeTurn,
            cancelTurn = ::cancelRuntimeTurn,
            runtime = turnEngine.runtimeView,
        )

    // The Plan closed loop (research doc section 4.2/4.3; HX2-05): the review state machine the
    // plan surface drives — approve is the ONLY source of a PlanExecutionBinding, and execute
    // creates the plan-bound Goal. The production port is backed by [storage] (the same
    // repository the `plan.submit` tool persists through).
    internal val planReview: PlanReviewService = PlanReviewService(StoragePlanReviewPort(storage, clock, idGenerator))

    // Observers started in init may refresh immediately on another thread.
    private val drafts = ChatDraftStore()
    private val conversationReferences = ConversationReferenceResolver(storage)
    private val sessionRunControls = SessionRunControlStore(storage, runControlStore)
    private val workspaceRecovery = SessionWorkspaceRecovery(storage, bindSessionDirectory)
    private val _runControl = MutableStateFlow(sessionRunControls.defaultSnapshot())
    val toolVisionConsent =
        com.helix.app.vision
            .ToolVisionConsent(storage.interactionReceipts, clock)
    private val requestAssembler =
        ChatRequestAssembler(
            storage,
            providerService,
            toolPipeline,
            attachmentStaging,
            visionSessionBinder,
            projectInstructionsReader,
            memory,
            toolVisionConsent,
        )
    private val attachmentRetry = ChatAttachmentRetry(storage, attachmentStaging)
    private val labels = ChatStatusLabels(strings)
    private val projection = ChatScreenProjection(storage, providerService, strings, labels::modelTerminalCodeRes)
    private val stagingProcessor = StagedAttachmentProcessor(storage, attachmentStaging, idGenerator, strings)
    private val workScope = scope
    private val sessionActions = SessionActionQueue(workScope)
    private val toolCalls by lazy {
        ChatToolCalls(
            storage,
            toolPipeline,
            clock,
            idGenerator,
            profile,
            workScope,
            _screen,
            turnEngine.liveHandles,
            strings,
            lanScopes,
            attachmentStaging.workspaceScopeId,
        )
    }
    private val agentLoop by lazy {
        AgentLoop(
            storage,
            providerService,
            requestAssembler,
            toolCalls,
            clock,
            idGenerator,
            turnEngine.liveHandles,
            strings,
            ::refreshScreen,
            ::applyEvent,
            turnEngine::persistTerminalInTransaction,
            inputDelivery =
                object : TurnInputDelivery {
                    override suspend fun prepareSteering(
                        sessionId: String,
                        turnId: String,
                    ): TurnSteeringDraft? = sessionInputDelivery.prepareSteering(sessionId, turnId)

                    override fun requestStarting(
                        sessionId: String,
                        turnId: String,
                        modelCallId: String,
                        messageIds: Set<String>,
                    ) {
                        bindInputAuthority(sessionId, turnId, modelCallId, messageIds)
                    }
                },
        )
    }
    private val goals by lazy {
        ChatGoalActions(
            storage,
            clock,
            idGenerator,
            requestAssembler,
            { sessionId -> sessionRunControls.ensure(sessionId, clock.now().toEpochMilli()) },
            ::resolvableOpenSessionId,
            goalReminderSync,
        )
    }
    private val recovery by lazy {
        ChatRecoveryActions(storage, workScope, _screen, subscriptionRecovery, subscriptionResultRecovery)
    }

    /** Resolves a string-resource id (+ optional format args) to the current locale (HXA-069). */
    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    private fun terminalLabel(
        state: TurnState,
        errorCode: String?,
    ): String? = labels.terminalLabel(state, errorCode)

    private fun egressRejectedLabel(code: String): String = labels.egressRejectedLabel(code)

    private val _sessions = MutableStateFlow<List<SessionRowUi>>(emptyList())

    /**
     * Bounded session/history search (HXA-191 slice): service-owned state — the UI observes,
     * it holds no Job (doc 02 section 12). The scan runs on [workScope] (IO by default),
     * never on the Compose main thread.
     */
    private val _sessionSearch = MutableStateFlow(SessionSearchUiState())
    val sessionSearch: StateFlow<SessionSearchUiState> = _sessionSearch.asStateFlow()

    private var searchJob: Job? = null
    private val _backgroundTasks = MutableStateFlow<List<BackgroundTaskUi>>(emptyList())
    val backgroundTasks: StateFlow<List<BackgroundTaskUi>> = _backgroundTasks
    private val backgroundJobsState = MutableStateFlow<List<com.helix.app.proot.BackgroundJobUi>>(emptyList())
    internal val backgroundJobs: StateFlow<List<com.helix.app.proot.BackgroundJobUi>> = backgroundJobsState
    private val jobActions =
        com.helix.app.proot.BackgroundJobActions(
            workScope,
            com.helix.app.proot.ProotToolModule::performBackgroundJobAction,
        ) {
            refreshBackgroundTasks()
            refreshTaskDashboards()
        }
    internal val backgroundJobAction = jobActions.state

    internal fun performBackgroundJobAction(
        job: com.helix.app.proot.BackgroundJobUi,
        action: com.helix.app.proot.BackgroundJobAction,
    ) = jobActions.submit(job, action)

    private val transportState = MutableStateFlow<TurnState?>(null)
    val foregroundTransportState: StateFlow<TurnState?> = transportState
    private val goalDashboardState = MutableStateFlow<List<GoalSummaryUi>>(emptyList())
    internal val goalDashboard: StateFlow<List<GoalSummaryUi>> = goalDashboardState
    private val planDashboardState = MutableStateFlow<List<PlanRowUi>>(emptyList())
    internal val planDashboard: StateFlow<List<PlanRowUi>> = planDashboardState
    private val artifactFilesState = MutableStateFlow<List<ArtifactRowUi>>(emptyList())
    internal val artifactFiles: StateFlow<List<ArtifactRowUi>> = artifactFilesState
    private val _screen = MutableStateFlow(EMPTY_SCREEN)

    /** UI-owned deep links for recovery repair; ChatService still holds no NavController. */
    internal var recoveryModelsNavigation: (() -> Unit)? = null
    internal var recoveryPermissionsNavigation: (() -> Unit)? = null

    /** HXA-204 slice 2: the panel operations — each keeps its own identity and admission. */
    private val turnRecovery =
        TurnRecoveryActions(
            storage,
            workScope,
            _screen,
            { sessionId -> projection.retryTargetFor(sessionId) },
            RecoveryRepairNavigation(
                models = { recoveryModelsNavigation?.invoke() },
                permissions = { recoveryPermissionsNavigation?.invoke() },
            ),
            ::continueGoal,
            ::retry,
            ::inspectInterruptedProot,
            ::refreshScreen,
        )

    private val reminderGoalState = MutableStateFlow<String?>(null)
    internal val reminderGoal: StateFlow<String?> = reminderGoalState.asStateFlow()

    /** A notification is a navigation request, never a Continued event or approval. */
    internal fun openGoalReminder(goalId: String) {
        workScope.launch {
            val sessionId = storage.goalTurnBindings.sessionForGoal(goalId) ?: return@launch
            openSession(sessionId)
            refreshScreen()
            reminderGoalState.value = goalId
        }
    }

    internal fun dismissGoalReminder() {
        reminderGoalState.value = null
    }

    val sessions: StateFlow<List<SessionRowUi>> = _sessions.asStateFlow()
    val screen: StateFlow<ChatScreenState> = _screen.asStateFlow()

    /** The runtime safety profile for the chat header (ADR-0005 display). */
    val profile: StateFlow<SafetyProfile> = profileStore.flow

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
                            val session = storage.sessions.find(sessionId) ?: return@synchronized false
                            if (session.archivedAt != null) return@synchronized false
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

    /** Durable Session behavior profile. Active Turns keep their admitted snapshot. */
    fun loadSessionExpert(sessionId: String): kotlinx.coroutines.Deferred<ExpertProfile?> =
        workScope.async { storage.sessionExperts.forSession(sessionId) }

    fun saveSessionExpert(
        sessionId: String,
        displayName: String,
        instruction: String,
    ): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            if (storage.sessions.find(sessionId) == null) return@async false
            val name = displayName.trim()
            val body = instruction.trim()
            if (!validExpertProfileInput(name, body)) return@async false
            val current = storage.sessionExperts.forSession(sessionId)
            val profile =
                ExpertProfile(
                    id = current?.id ?: idGenerator(),
                    displayName = name,
                    instruction = body,
                    recommendedSkillIds = current?.recommendedSkillIds.orEmpty(),
                    recommendedConnectorIds = current?.recommendedConnectorIds.orEmpty(),
                    recommendedMode = current?.recommendedMode,
                )
            storage.sessionExperts.setForSession(sessionId, profile, clock.now().toEpochMilli())
            true
        }

    private fun validExpertProfileInput(
        name: String,
        instruction: String,
    ): Boolean =
        validExpertField(name, ExpertProfile.MAX_NAME_LENGTH) &&
            validExpertField(instruction, ExpertProfile.MAX_INSTRUCTION_LENGTH)

    private fun validExpertField(
        value: String,
        maxLength: Int,
    ): Boolean = value.isNotBlank() && value.length <= maxLength && '\u0000' !in value

    fun clearSessionExpert(sessionId: String): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            if (storage.sessions.find(sessionId) == null) return@async false
            storage.sessionExperts.clearForSession(sessionId)
            true
        }

    /**
     * Selection stays lightweight; bytes are intentionally materialized later at submission.
     * Prefer an existing compacted summary, otherwise use the bounded recent-message snapshot.
     */
    fun referenceKindForSession(
        targetSessionId: String,
        sourceSessionId: String,
    ): kotlinx.coroutines.Deferred<ConversationReferenceKind?> =
        workScope.async {
            if (targetSessionId == sourceSessionId || storage.sessions.find(sourceSessionId) == null) return@async null
            val kind =
                if (conversationReferences.hasSummary(sourceSessionId)) {
                    ConversationReferenceKind.SUMMARY
                } else {
                    ConversationReferenceKind.RECENT_MESSAGES
                }
            runCatching {
                conversationReferences.prepare(targetSessionId, sourceSessionId, kind)
                kind
            }.getOrNull()
        }

    fun setTurnBudgets(budgets: TurnBudgets) =
        updateSessionRunControl("budgets") { it.copy(budgets = TurnBudgetBounds.validate(budgets)) }

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
                        val session = storage.sessions.find(sessionId) ?: return@synchronized
                        if (session.archivedAt != null) return@synchronized
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
    private val turnGate = Any()
    private val goalContinuation = GoalContinuationDriver(storage)
    private val automaticTurnRecovery by lazy {
        AutomaticTurnRecovery(storage, clock, ::startRecoveryInspection, turnEngine::finishAutomaticRecovery) {
            str(R.string.automatic_recovery_unavailable)
        }
    }

    @Suppress("ReturnCount") // Stop before admitting a successor when any original binding is unavailable.
    private suspend fun startRecoveryInspection(
        parentId: String,
        snapshot: com.helix.app.engine.TurnRuntimeSnapshot,
    ): String? {
        val parent = storage.turns.resolve(parentId)
        val session = storage.sessions.resolve(parent.sessionId)
        if (session.archivedAt != null || turnEngine.liveExecution.hasActive(session.id)) return null
        if (session.providerId != snapshot.providerId || session.modelId != snapshot.modelId) return null
        if (providerSnapshot(snapshot.providerId, snapshot.modelId) != snapshot.providerSnapshot) return null
        val executorObservation =
            try {
                recovery.inspectOriginal(parentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "Original executor reconciliation unavailable; effects remain unknown."
            }
        val goalId = storage.goalTurnBindings.byTurn(parentId)?.let { storage.goalRuns.resolve(it.runId).goalId }
        val limits =
            com.helix.app.engine.AutomaticRecoveryPolicy
                .limits(snapshot, goalId != null) ?: return null
        return launchTurn(
            text = packagedPromptTemplates.text("automatic-recovery").trim() + "\n\n" + executorObservation,
            providerId = snapshot.providerId,
            goalId = goalId,
            requestedSessionId = parent.sessionId,
            controlOverride = snapshot.control.copy(mode = AgentMode.PLAN, budgets = limits),
            clientRequestId =
                com.helix.app.engine.AutomaticRecoveryPolicy
                    .requestId(parentId),
            recoveryParent = parentId,
        )
    }

    private fun requestAutomaticRecovery(turnId: String) {
        workScope.launch {
            submissionGate.withLock {
                automaticTurnRecovery.recover(turnId)
            }
            requestSessionDrain(storage.turns.resolve(turnId).sessionId)
            refreshScreen()
            refreshBackgroundTasks()
        }
    }

    private val sessionWorkScheduler by lazy {
        SessionWorkScheduler(
            storage = storage,
            clock = clock,
            scope = workScope,
            submissionGate = submissionGate,
            turnGate = turnGate,
            hasLiveTurn = turnEngine.liveExecution::hasActive,
            goalContinuation = goalContinuation,
            consumeQueueInput = { input -> consumeQueueInput(input) },
            submitContinuation = { command -> agentRuntime.submit(command) },
            refreshProjection = {
                refreshScreen()
                refreshBackgroundTasks()
            },
            recoverInputs = { session ->
                AutomaticInputRecovery(storage, clock, { validatedInput(it) != null }) {
                    str(R.string.session_input_recovery_ended)
                }.recover(session)
            },
        )
    }

    private data class InputResumeConfirmation(
        val input: SessionInputRecord,
        val target: EgressDisclosure.EgressTarget,
    )

    @Volatile private var pendingInputResume: InputResumeConfirmation? = null

    private data class GoalUserRequest(
        val sessionId: String,
        val text: String,
        val providerId: String,
        val control: RunControlConfig,
        val providerSnapshot: String,
        val sourceMessageId: String? = null,
        val inputId: String? = null,
    )

    private val goalUserRequests = java.util.concurrent.ConcurrentHashMap<String, List<GoalUserRequest>>()
    private val goalLifecycle by lazy {
        com.helix.app.goal.GoalLifecycleService(
            storage,
            clock,
            idGenerator,
            authorize = { call, quote ->
                goalUserRequests[call.turnId]
                    ?.firstOrNull {
                        it.sessionId == call.sessionId && it.text.contains(quote) &&
                            (
                                it.sourceMessageId == null ||
                                    storage.messages.resolve(it.sourceMessageId).turnId == call.turnId
                            )
                    }?.control
                    ?.goalBudgets
            },
            staged = { session, turn, goal, activate ->
                if (activate == false) goalContinuation.disarmGoal(session, goal)
                if (activate == true) {
                    val request = requireNotNull(goalUserRequests[turn]?.firstOrNull())
                    goalContinuation.prepare(
                        session,
                        goal,
                        request.providerId,
                        request.control,
                        turn,
                        request.providerSnapshot,
                    )
                }
            },
            armed = goalContinuation::isArmed,
        )
    }

    internal fun executeGoalTool(
        call: com.helix.tools.framework.ExecutableToolCall,
    ): com.helix.tools.framework.ToolExecutorResult = synchronized(turnGate) { goalLifecycle.execute(call) }

    internal suspend fun editGoalObjective(
        goalId: String,
        revision: Long,
        objective: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(turnGate) {
                val session = openSessionId ?: return@synchronized false
                var changed = false
                storage.withTransaction {
                    val goal = storage.goals.find(goalId) ?: return@withTransaction
                    val owner =
                        storage.goalControls.find(goalId)?.sessionId
                            ?: storage.goalTurnBindings.sessionForGoal(goalId)
                    if (owner != null && owner != session) return@withTransaction
                    goalLifecycle.bind(goalId, session)
                    val control = requireNotNull(storage.goalControls.find(goalId))
                    val editable =
                        goal.planId == null &&
                            goal.state in setOf("READY", "PAUSED", "INPUT_REQUIRED", "BLOCKED")
                    val valid =
                        objective.isNotBlank() &&
                            objective.length <= com.helix.core.agent.Goal.MAX_OBJECTIVE_LENGTH
                    val unchanged = control.revision == revision && control.pendingJson == null
                    if (unchanged && editable && valid) {
                        storage.goals.updateObjective(goalId, objective)
                        check(storage.goalControls.settle(goalId, revision) == 1)
                        storage.auditEvents.append(
                            idGenerator(),
                            goal.correlationId,
                            "goal.objective_updated",
                            "USER",
                            """{"revision":${revision + 1}}""",
                            clock.now().toEpochMilli(),
                        )
                        changed = true
                    }
                }
                changed
            }
        }

    fun stopContinuousGoals(systemReason: String? = null) {
        val turns =
            synchronized(turnGate) {
                goalContinuation.disarmAll()
                goalUserRequests.clear()
                turnEngine.liveExecution.activeTurns().map { it.turnId }
            }
        workScope.launch {
            turns.forEach { turn ->
                if (storage.goalTurnBindings.byTurn(turn) != null) {
                    stopTask(turn, pause = true, systemReason = systemReason)
                }
            }
            refreshBackgroundTasks()
        }
    }

    private fun revokeGoalIntent(sessionId: String) {
        goalContinuation.disarm(sessionId)
        goalUserRequests.entries.removeAll { entry -> entry.value.any { it.sessionId == sessionId } }
    }

    // Written on the main thread (open/close/cancel), read from the work-scope IO pool
    // (sendNow): atomic visibility keeps fresh opens visible to racing sends, and
    // compare-and-set prevents a stale failed lookup from clearing a newer selection.
    private val openSession =
        java.util.concurrent.atomic
            .AtomicReference<String?>(null)
    private var openSessionId: String?
        get() = openSession.get()
        set(value) {
            openSession.set(value)
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
    @Volatile
    private var shareDraftText: String? = null

    /**
     * HXA-049 (ADR-0014 §5): the open session's staged attachments — in memory, local until
     * an EXPLICIT send. Staging (pick/import) never reaches the model.
     * [StagedAttachmentEntry.file] is a REAL workspace path used ONLY for hashing /
     * re-materialization inside this service — it never enters UI state, logs, audit or
     * model context (the scope-relative [StagedAttachmentEntry.relativePath] is what does).
     *
     * Thread-safety: EVERY mutation is serialized on [stagedLock] (stage/remove/clear/send/
     * confirm all run on the work-scope IO pool — a read-modify-write outside the lock could
     * drop a concurrently staged file); the @Volatile keeps a single READ of the whole list
     * atomic without the lock.
     */
    @Volatile
    private var stagedAttachments: List<StagedAttachmentEntry> = emptyList()

    /**
     * Serializes every MUTATION of [stagedAttachments] (the staged-list writers race on the
     * work-scope IO pool: stage append, remove, clear, the send/confirm clears). Single
     * reads stay lock-free — [stagedAttachments] is @Volatile, so one read sees exactly one
     * whole list.
     */
    private val stagedLock = Any()

    /**
     * ADR-0014 §5 「凭据类内容仍拒绝出网」: the credential-shape scanner the attachment
     * gate injects — the same guard that scans the text typed in the box, applied at
     * send/confirm/retry to the FULL content of every (re-)materialized attachment, not
     * just the bounded inline view.
     */
    private val credentialScan: (String) -> String? = ForbiddenContentGuard::reasonFor
    private val sessionInputDelivery by lazy {
        SessionInputDeliveryCoordinator(
            storage = storage,
            providerService = providerService,
            sessionRunControls = sessionRunControls,
            turnEngine = turnEngine,
            attachmentStaging = attachmentStaging,
            credentialScan = credentialScan,
            providerSnapshot = ::providerSnapshot,
            clock = clock,
            turnGate = turnGate,
            launchQueuedTurn = { input, content, bindings, control, requireHead ->
                launchTurn(
                    text = content,
                    providerId = input.configuration.providerId,
                    attachmentBindings = bindings,
                    referenceSnapshots = listOfNotNull(storage.sessionInputs.readReference(input)),
                    requestedSessionId = input.sessionId,
                    controlOverride = control,
                    clientRequestId = input.inputId,
                    continuousGoal = input.configuration.mode == AgentMode.GOAL.name,
                    queuedInput = input,
                    requireQueueHead = requireHead,
                )
            },
        )
    }

    // --- HXA-036: tool pipeline state (cards/dispatch facts); live cancel/timer ownership is TurnEngine. ---

    internal fun prepareDetachedJobBudget(
        sessionId: String,
        turnId: String,
        executionId: String,
        millis: Long,
    ): Long =
        com.helix.app.agent
            .GoalDetachedBudget(storage, clock, turnEngine.liveHandles::goalTime, idGenerator)
            .prepare(sessionId, turnId, executionId, millis)

    internal fun rejectDetachedJobBudget(
        sessionId: String,
        turnId: String,
        executionId: String,
    ) = com.helix.app.agent
        .GoalDetachedBudget(storage, clock, turnEngine.liveHandles::goalTime, idGenerator)
        .reject(sessionId, turnId, executionId)

    internal fun settleDetachedJobBudget(
        sessionId: String,
        turnId: String,
        executionId: String,
        terminalElapsedMs: Long?,
    ) = com.helix.app.agent
        .GoalDetachedBudget(storage, clock, turnEngine.liveHandles::goalTime, idGenerator)
        .settle(sessionId, turnId, executionId, terminalElapsedMs)

    init {
        refreshSessions()
        workScope.launch { providerService.contextRevision.collect { refreshScreen() } }
    }

    // --------------------------------------------------------------------------------
    // Session intents
    // --------------------------------------------------------------------------------

    /** Startup recovery commits before this notification; refresh any already-open conversation. */
    fun onRecoveryCompleted() {
        workScope.launch {
            storage.sessions.list().filter { it.archivedAt == null }.forEach { session ->
                storage.turns.listBySession(session.id).lastOrNull()?.let { turn ->
                    submissionGate.withLock { automaticTurnRecovery.recover(turn.id) }
                }
            }
            refreshSessionsNow()
            refreshScreen()
            storage.sessions
                .list()
                .filter { it.archivedAt == null }
                .forEach { requestSessionDrain(it.id) }
        }
    }

    /**
     * Thread-safe (Room read on the service's IO scope). The UI and the
     * container init may call this from any thread; the [sessions] StateFlow
     * updates when the read completes.
     */
    fun refreshSessions() {
        workScope.launch { refreshSessionsNow() }
    }

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
            val session = (target as? ConversationLaunchTarget.Session)?.let { storage.sessions.find(it.sessionId) }
            // Resolve Room off-main, then arbitrate with explicit UI navigation in one UI turn.
            withContext(Dispatchers.Main.immediate) {
                if (openSessionId != null || preparingDraft) return@withContext
                if (session != null && session.archivedAt == null) {
                    openSession(session.id)
                } else {
                    newSessionDraft()
                }
            }
        }
    }

    private fun refreshSessionsNow() {
        val providerNames = providerService.rows.value.associate { it.id to it.displayName }
        _sessions.value =
            storage.sessions
                .list()
                .map { entity ->
                    SessionRowUi.from(entity, entity.providerId?.let { providerNames[it] })
                }
    }

    /**
     * Bounded session/history search (HXA-191 slice). A blank query clears the search;
     * otherwise the query is reflected in the state immediately, the previous pass is
     * cancelled, and one bounded scan runs on [workScope] after a short debounce. The
     * result states its own scope (scanned/skipped/truncated) so the UI never implies
     * "only the current page" was searched.
     */
    @Synchronized
    fun searchSessions(rawQuery: String) {
        val query = rawQuery
        searchJob?.cancel()
        searchJob = null
        if (query.isBlank()) {
            _sessionSearch.value = SessionSearchUiState()
            return
        }
        // The list's search field is controlled by this state, so the typed query must be
        // reflected before the debounced scan completes: a stale [query] would let the next
        // recomposition pull the field back and cancel the in-flight scan.
        // Clear old hits so they cannot appear to match the new query.
        _sessionSearch.value = SessionSearchUiState(query = query)
        searchJob =
            workScope.launch {
                delay(SEARCH_DEBOUNCE_MILLIS)
                val context = currentCoroutineContext()
                val result = storage.sessionSearch.search(query, checkActive = { context.ensureActive() })
                synchronized(this@ChatService) {
                    context.ensureActive()
                    _sessionSearch.value =
                        SessionSearchUiState(
                            query = query,
                            hits =
                                result.hits.map { hit ->
                                    SessionSearchHitUi(
                                        sessionId = hit.sessionId,
                                        title = hit.sessionTitle,
                                        isArchived = hit.isArchived,
                                        matchesTitle = hit.matchedKinds.contains(SessionSearchMatchKind.TITLE),
                                        matchesMessage = hit.matchedKinds.contains(SessionSearchMatchKind.MESSAGE),
                                        messageSnippet = hit.messageSnippet,
                                    )
                                },
                            scannedMessages = result.scannedMessages,
                            skippedMessages = result.skippedMessages,
                            truncated = result.truncated,
                        )
                }
            }
    }

    /** Clears the search: the session list returns to its normal state. */
    @Synchronized
    fun clearSessionSearch() {
        searchJob?.cancel()
        searchJob = null
        _sessionSearch.value = SessionSearchUiState()
    }

    private val sessionDraft: SessionDraft? get() = drafts.current
    private val preparingDraft: Boolean get() = drafts.preparing

    fun newSessionDraft() {
        if (preparingDraft) return
        val inherited =
            sessionDraft?.session?.providerId
                ?: _sessions.value.firstOrNull { it.id == openSessionId }?.providerId
        val provider =
            providerService.rows.value.firstOrNull { it.chatSelectable && it.id == inherited }
                ?: providerService.rows.value.firstOrNull { it.chatSelectable }
        val entity =
            SessionEntity(
                idGenerator(),
                "",
                provider?.id,
                provider?.model,
                clock.now().toEpochMilli(),
                null,
            )
        val control = sessionRunControls.defaultSnapshot().copy(mode = AgentMode.ACT)
        if (!drafts.open(entity, control)) return
        openSessionId = entity.id
        _runControl.value = control
        conversationLaunchStore.selectNewDraft()
        clearStagedAttachments()
        clearSessionSearch()
        shareDraftText = null
        workScope.launch { refreshScreen() }
    }

    suspend fun saveDraftForGoal(text: String): Boolean =
        withContext(workScope.coroutineContext) {
            if (text.isBlank()) return@withContext false
            val attachments = saveSessionDraft(text) ?: return@withContext false
            attachments.forEach { stageAttachmentNow(it.uri) }
            stagedAttachments.size == attachments.size
        }

    private fun saveSessionDraft(
        text: String,
        expectedSessionId: String? = openSessionId,
        fallbackTitle: String = str(R.string.chat_attachment_button),
    ): List<DraftAttachment>? {
        val attachments =
            drafts.persist(expectedSessionId, text, fallbackTitle) { draft ->
                val row = draft.session
                storage.withTransaction {
                    val directory = workspaceRecovery.availableDirectory(row.directoryRef)
                    storage.sessions.create(row.id, row.title, row.providerId, row.modelId, row.createdAt, directory)
                    if (row.directoryRef != null && directory == null) workspaceRecovery.record(row.id, row.createdAt)
                    sessionRunControls.set(row.id, draft.control, row.createdAt)
                }
            } ?: return null
        expectedSessionId?.let { id ->
            storage.sessions.find(id)?.takeIf { it.archivedAt == null }?.let {
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
            val row = storage.sessions.resolve(id)
            storage.sessions.updateDetails(id, title, row.directoryRef)
            refreshSessionsNow()
            refreshScreen()
        }
    }

    internal suspend fun gitWorkspaceReader(reference: String): com.helix.app.git.GitWorkspaceReader =
        withContext(workScope.coroutineContext) {
            com.helix.app.git.GitWorkspaceReader(
                attachmentStaging.resolveWorkspacePath(FileScopePath.fromModelReference(reference)).toFile(),
            )
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
                    drafts.directory(draft.session.id, bound)
                } else {
                    val row = storage.sessions.find(targetSessionId) ?: return@async false
                    storage.withTransaction {
                        storage.sessions.updateDetails(row.id, row.title, bound)
                        val binding = storage.workspaces.binding(row.id)
                        storage.auditEvents.append(
                            idGenerator(),
                            row.id,
                            "workspace.bound",
                            "user",
                            buildJsonObject {
                                put("workspaceId", binding?.workspaceId.orEmpty())
                                put("revision", binding?.revision ?: 0L)
                            }.toString(),
                            clock.now().toEpochMilli(),
                        )
                    }
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
            workspaceRecovery.recover(sessionId, clock.now().toEpochMilli())
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
            require(providerService.chatSelectable(providerId)) {
                "the provider must pass a connection test before a session can use it"
            }
            val id = idGenerator()
            val now = clock.now().toEpochMilli()
            storage.withTransaction {
                storage.sessions.create(id, title, providerId, modelId, now)
                sessionRunControls.set(id, sessionRunControls.defaultSnapshot().copy(mode = AgentMode.ACT), now)
            }
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
            val context = kotlin.coroutines.coroutineContext
            val id = idGenerator()
            val title =
                storage.sessions
                    .resolve(sessionId)
                    .title
                    .take(160)
            val branchTitle = str(R.string.session_fork_title, title)
            val now = clock.now().toEpochMilli()
            storage.withTransaction {
                SessionFork(storage, bindSessionDirectory).create(sessionId, messageId, id, branchTitle, now) {
                    context.ensureActive()
                }
                sessionRunControls.set(id, sessionRunControls.defaultSnapshot().copy(mode = AgentMode.ACT), now)
            }
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
                storage.sessions.archive(id, clock.now().toEpochMilli())
                refreshSessionsNow()
            } catch (e: IllegalArgumentException) {
                // Already archived or gone (race with the UI); the next
                // refresh shows the persisted state — nothing to show.
            }
        }
    }

    fun restoreSession(id: String) {
        workScope.launch {
            storage.sessions.restore(id)
            refreshSessionsNow()
            refreshScreen()
        }
    }

    /** Opens a session: loads its persisted messages and the provider badge. */
    fun openSession(id: String) {
        dismissGoalReminder()
        if (preparingDraft) return
        cancelPendingSend()
        drafts.clear()
        openSessionId = id
        conversationLaunchStore.selectSession(id)
        clearStagedAttachments()
        clearSessionSearch()
        shareDraftText = null // a draft pre-fill belongs to the session it opened for (HXA-056)
        workScope.launch {
            val control =
                if (storage.sessions.find(id) != null) {
                    ensureSessionWorkspace(id)
                    sessionRunControls.ensure(id, clock.now().toEpochMilli())
                } else {
                    sessionRunControls.defaultSnapshot()
                }
            if (openSessionId == id && sessionDraft == null) _runControl.value = control
            refreshScreen()
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
        shareDraftText = null
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
            shareDraftText = null
            _runControl.value = sessionRunControls.defaultSnapshot()
        }
    }

    /**
     * The pending list belongs to the session that staged it (ADR-0014 §5: attachments stay
     * local to that session until an explicit send) — switching or closing the session drops
     * it. The imported files themselves remain durable in the workspace `input/` region.
     */
    private fun clearStagedAttachments() {
        synchronized(stagedLock) {
            stagedAttachments = emptyList()
        }
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
                shareDraftText = null
            }
            val id = idGenerator()
            val now = clock.now().toEpochMilli()
            storage.withTransaction {
                storage.sessions.create(id, str(R.string.session_shared_draft), null, null, now)
                sessionRunControls.set(id, sessionRunControls.defaultSnapshot().copy(mode = AgentMode.ACT), now)
            }
            refreshSessionsNow()
            openSession(id)
            (imageUris + fileUris).forEach { uri -> stageAttachmentNow(uri) }
            shareDraftText = text
            refreshScreen()
        }
    }

    /** One-shot consume: the UI applied [ChatScreenState.shareDraftText] to the composer. */
    fun consumeShareDraftText() {
        shareDraftText = null
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
            if (providerService.chatSelectable(providerId).not()) {
                setBlocked(str(R.string.chat_blocked_provider_untested))
                return@launch
            }
            sessionDraft?.takeIf { it.session.id == sessionId }?.let {
                drafts.model(it.session.id, providerId, modelId)
                refreshScreen()
                return@launch
            }
            if (turnGateHolds(sessionId)) return@launch // a turn in flight owns the session's target
            try {
                storage.sessions.bindProvider(sessionId, providerId, modelId)
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

    /** User-selected target for the next turn. Selection itself never sends history. */
    fun selectSessionModel(
        providerId: String,
        modelId: String,
    ) {
        val requestedSession = openSessionId ?: return
        workScope.launch {
            synchronized(turnGate) {
                if (openSessionId != requestedSession || preparingDraft) return@synchronized
                if (pendingSend != null || turnEngine.liveExecution.hasActive(requestedSession)) {
                    return@synchronized
                }
                val row =
                    providerService.rows.value.firstOrNull { it.id == providerId && it.chatSelectable }
                        ?: return@synchronized
                if (modelId !in (row.backendModels.orEmpty() + row.model)) return@synchronized
                val draft = sessionDraft?.takeIf { it.session.id == requestedSession }
                if (draft != null) {
                    drafts.model(draft.session.id, providerId, modelId)
                } else if (!selectPersistedSessionModel(requestedSession, providerId, modelId)) {
                    return@synchronized
                }
                val currentControl =
                    draft?.control ?: sessionRunControls.ensure(requestedSession, clock.now().toEpochMilli())
                val nextControl = currentControl.copy(reasoning = ReasoningEffort.OFF)
                if (draft != null) {
                    drafts.control(requestedSession, nextControl)
                } else {
                    sessionRunControls.set(requestedSession, nextControl, clock.now().toEpochMilli())
                }
                if (openSessionId == requestedSession) _runControl.value = nextControl
                refreshScreen()
            }
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
    internal suspend fun applyToolSettings(call: com.helix.tools.framework.ExecutableToolCall): Boolean =
        applySettings(
            requireNotNull(call.sessionId),
            call.args["mode"]
                ?.jsonPrimitive
                ?.content
                ?.let(AgentMode::valueOf),
            call.args["providerId"]?.jsonPrimitive?.content,
            call.args["model"]?.jsonPrimitive?.content,
            call.args["reasoning"]?.jsonPrimitive?.content?.let {
                ReasoningEffort.valueOf(it.uppercase(java.util.Locale.ROOT))
            },
            call,
        )

    @Suppress("CyclomaticComplexMethod", "LongParameterList") // Revalidate UI or trusted executor ownership atomically.
    private suspend fun applySettings(
        sessionId: String,
        mode: AgentMode?,
        providerId: String?,
        modelId: String?,
        reasoning: ReasoningEffort?,
        call: com.helix.tools.framework.ExecutableToolCall?,
    ): Boolean =
        workScope
            .async {
                val applied =
                    synchronized(turnGate) {
                        if (!settingsChangeAdmitted(sessionId, call)) return@synchronized false
                        val session = storage.sessions.find(sessionId) ?: return@synchronized false
                        if (session.archivedAt != null) return@synchronized false
                        val targetProvider = providerId ?: session.providerId ?: return@synchronized false
                        val targetModel = modelId ?: session.modelId ?: return@synchronized false
                        val row =
                            providerService.rows.value.firstOrNull {
                                it.id == targetProvider && it.chatSelectable
                            } ?: return@synchronized false
                        if (targetModel !in (row.backendModels.orEmpty() + row.model)) return@synchronized false
                        val efforts = providerService.reasoningOptions(targetProvider, targetModel)
                        if (reasoning != null && reasoning != ReasoningEffort.OFF && reasoning !in efforts) {
                            return@synchronized false
                        }
                        val current = sessionRunControls.ensure(sessionId, clock.now().toEpochMilli())
                        val modelChanged = targetProvider != session.providerId || targetModel != session.modelId
                        val next =
                            current.copy(
                                mode = mode ?: current.mode,
                                reasoning = reasoning ?: if (modelChanged) ReasoningEffort.OFF else current.reasoning,
                            )
                        if (call != null && (call.cancel.isCancelled() || !clock.now().isBefore(call.deadline))) {
                            return@synchronized false
                        }
                        storage.withTransaction {
                            storage.sessions.selectModel(sessionId, targetProvider, targetModel)
                            sessionRunControls.set(sessionId, next, clock.now().toEpochMilli())
                        }
                        if (openSessionId == sessionId) _runControl.value = next
                        true
                    }
                if (applied) {
                    refreshSessionsNow()
                    refreshScreen()
                }
                applied
            }.await()

    private fun settingsChangeAdmitted(
        sessionId: String,
        call: com.helix.tools.framework.ExecutableToolCall?,
    ): Boolean {
        if (pendingSend != null || preparingDraft) return false
        val active = turnEngine.liveExecution.active(sessionId)
        return if (call == null) {
            openSessionId == sessionId && active == null && !turnGateHolds(sessionId)
        } else {
            val turnId = call.turnId
            call.toolName == "helix.settings.apply" && turnId != null && active?.turnId == turnId &&
                storage.sessionInputs.listPending(sessionId).isEmpty() && !call.cancel.isCancelled() &&
                clock.now().isBefore(call.deadline) && storage.turns.resolve(turnId).state != TurnState.CANCELLING.name
        }
    }

    private fun selectPersistedSessionModel(
        sessionId: String,
        providerId: String,
        modelId: String,
    ): Boolean {
        if (turnGateHolds(sessionId) || storage.sessions.resolve(sessionId).archivedAt != null) return false
        return try {
            storage.sessions.selectModel(sessionId, providerId, modelId)
            refreshSessionsNow()
            true
        } catch (_: IllegalArgumentException) {
            setBlocked(str(R.string.chat_blocked_provider_state_changed))
            false
        }
    }

    /** True when the session's newest turn has not terminalized (the target must be stable). */
    private fun turnGateHolds(sessionId: String): Boolean =
        storage.turns
            .listBySession(sessionId)
            .lastOrNull()
            ?.let {
                val state = TurnState.valueOf(it.state)
                !state.isTerminal && state != TurnState.NEEDS_REVIEW
            }
            ?: false

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

    @Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught") // one fail-closed return per stage
    private suspend fun stageAttachmentNow(uri: String, expectedSessionId: String? = openSessionId) {
        val sessionId = expectedSessionId
        if (openSessionId != sessionId) return
        if (sessionId == null) {
            setBlocked(str(R.string.chat_blocked_no_open_session))
            return
        }
        // Fast-fail UX only: the AUTHORITATIVE cap check runs inside [stagedLock] in
        // [stageImportedAttachment] — two concurrent stages can both pass this one.
        val attachmentCount = sessionDraft?.attachments?.size ?: stagedAttachments.size
        if (attachmentCount >= AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE) {
            setBlocked(
                str(R.string.chat_blocked_max_attachments, AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE),
            )
            return
        }
        val reported =
            try {
                attachmentStaging.sourceMetadata(uri)
            } catch (e: Exception) {
                // The production reader degrades internally; this guard keeps any adapter
                // fail-closed. The uri is a content:// reference (never a real path), but
                // the exception is not logged — only a fixed user-visible reason is shown.
                setBlocked(str(R.string.chat_blocked_cannot_read_file))
                return
            }
        val draft = sessionDraft
        if (draft != null && draft.session.id == sessionId) {
            drafts.addAttachment(
                sessionId,
                DraftAttachment(
                    idGenerator(),
                    uri,
                    reported.displayName.orEmpty(),
                    reported.sizeBytes,
                ),
            )
            refreshScreen()
            return
        }
        // The one-time private copy through the existing pipeline: the file is pinned under
        // input/attachments/<attachment-id>/ and hash-snapshotted; a refused import leaves
        // nothing on disk. This is import ONLY — it never reaches the model.
        val result =
            attachmentStaging.importer.importAttachment(
                workspaceScopeId = attachmentStaging.workspaceScopeId,
                sourceUri = uri,
                reported = reported,
                cancel = SafCancelToken { false },
                sink = null,
                sessionId = sessionId,
            )
        if (result.status == ImportStatus.CANCELLED) {
            setBlocked(str(R.string.chat_blocked_import_cancelled))
            return
        }
        if (result.status == ImportStatus.REFUSED) {
            setBlocked(importRefusalReason(result.refusal))
            return
        }
        stageImportedAttachment(result, sessionId)
    }

    private fun stageImportedAttachment(
        result: AttachmentImportResult,
        sessionId: String,
    ) {
        when (val prepared = stagingProcessor.prepare(result, sessionId)) {
            is StagedAttachmentProcessor.Preparation.Rejected -> {
                setBlocked(prepared.reason)
            }

            is StagedAttachmentProcessor.Preparation.Ready -> {
                if (admitStagedEntry(prepared.entry)) refreshScreen()
            }
        }
    }

    /**
     * The authoritative, in-lock append of one staged attachment: it appends only when the
     * session has NOT switched in flight and the per-message cap has not been exceeded — either
     * failure sets the blocked state and appends NOTHING (the durable artifact row stays, inert).
     * Returns true when the entry was appended.
     */
    private fun admitStagedEntry(entry: StagedAttachmentEntry): Boolean {
        var admitted = false
        synchronized(stagedLock) {
            if (openSessionId == entry.sessionId) {
                if (stagedAttachments.size >= AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE) {
                    setBlocked(
                        str(R.string.chat_blocked_max_attachments, AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE),
                    )
                } else {
                    stagedAttachments = stagedAttachments + entry
                    admitted = true
                }
            }
        }
        return admitted
    }

    /** The fixed, user-visible (Chinese) reason for a refused attachment import — never the raw detail. */
    private fun importRefusalReason(refusal: ImportRefusal?): String =
        when (refusal) {
            ImportRefusal.REPORTED_SIZE_EXCEEDS_LIMIT -> str(R.string.chat_import_size_exceeded)
            ImportRefusal.STREAM_LIMIT_EXCEEDED -> str(R.string.chat_import_size_exceeded)
            ImportRefusal.QUOTA_EXCEEDED -> str(R.string.chat_import_quota_exceeded)
            ImportRefusal.SOURCE_UNOPENABLE -> str(R.string.chat_import_source_unopenable)
            ImportRefusal.STREAM_SIZE_MISMATCH -> str(R.string.chat_import_size_mismatch)
            ImportRefusal.DESTINATION_EXISTS -> str(R.string.chat_import_destination_exists)
            ImportRefusal.SCOPE_UNAVAILABLE -> str(R.string.chat_import_scope_unavailable)
            ImportRefusal.INVALID_TARGET -> str(R.string.chat_import_scope_unavailable)
            ImportRefusal.IO_FAILURE -> str(R.string.chat_import_io_failure)
            null -> str(R.string.chat_import_io_failure)
        }

    /** Removes one staged attachment addressed by its [PendingAttachmentUi.id] (the artifact id). */
    fun removePendingAttachment(id: String) {
        workScope.launch {
            drafts.removeAttachment(id)
            synchronized(stagedLock) {
                stagedAttachments = stagedAttachments.filterNot { it.artifactId == id }
            }
            refreshScreen()
        }
    }

    // --------------------------------------------------------------------------------
    // Send path
    // --------------------------------------------------------------------------------

    internal suspend fun createGoal(
        objective: String,
        criteria: List<String>,
        budgets: GoalBudgets,
    ): String {
        val goal = goals.createGoal(objective, criteria, budgets)
        refreshTaskDashboards()
        return goal
    }

    internal suspend fun goalSummaries() = goals.goalSummaries()

    /** Cross-session goal list for the Tasks dashboard (doc section 13). */
    internal suspend fun goalSummariesAll() = goals.goalSummariesAll()

    internal suspend fun setGoalReminder(
        goalId: String,
        delayMillis: Long?,
    ): Boolean {
        val changed = goals.setGoalReminder(goalId, delayMillis)
        refreshTaskDashboards()
        return changed
    }

    internal suspend fun recheckGoalBlocker(goalId: String): Boolean {
        val resolved = goals.recheckGoalBlocker(goalId)
        refreshTaskDashboards()
        return resolved
    }

    internal suspend fun updateGoalBudgets(
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

    internal suspend fun reviewPlan(planId: String): PlanReview {
        val review = withContext(Dispatchers.IO) { planReview.review(planId) }
        refreshTaskDashboards()
        return review
    }

    /** Cross-session plan rows for the Tasks dashboard review queue (doc section 12/13). */
    internal suspend fun planRows(): List<PlanRowUi> = withContext(Dispatchers.IO) { PlanRowQuery(storage).read() }

    /** The ONLY path to a [PlanExecutionBinding] (doc 4.3); requires the plan to be READY. */
    internal suspend fun approvePlan(planId: String): PlanExecutionBinding {
        val binding = withContext(Dispatchers.IO) { planReview.approve(planId) }
        refreshTaskDashboards()
        return binding
    }

    internal suspend fun revisePlan(planId: String) {
        withContext(Dispatchers.IO) { planReview.revise(planId) }
        refreshTaskDashboards()
    }

    internal suspend fun cancelPlan(planId: String) {
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
    internal suspend fun executeApprovedPlan(
        binding: PlanExecutionBinding,
        budgets: com.helix.core.model.GoalBudgets,
    ): String? = withContext(Dispatchers.IO) { executeApprovedPlanOnIo(binding, budgets) }

    // The plan-review dialog calls [executeApprovedPlan] from a Compose coroutine scope (the main
    // dispatcher), but the whole body is Room I/O — the session and provider-gate reads, the
    // plan->EXECUTING transaction, the first-turn start — and HelixStorage refuses database work on
    // the main thread. Hopping to IO is the same self-dispatch reviewPlan / approvePlan /
    // revisePlan apply to their Room calls, and it matches the chat-send path, which runs this same
    // submitTurn -> launchTurn on workScope (IO).
    @Suppress("ReturnCount", "SwallowedException")
    private suspend fun executeApprovedPlanOnIo(
        binding: PlanExecutionBinding,
        budgets: com.helix.core.model.GoalBudgets,
    ): String? {
        val providerId = currentSession()?.providerId
        if (providerId == null) {
            setBlocked(str(R.string.chat_blocked_no_provider_bound))
            return null
        }
        if (!providerService.chatSelectable(providerId)) {
            setBlocked(str(R.string.chat_blocked_provider_untested))
            return null
        }
        if (!providerService.isCleartextPermitted(providerId)) {
            setBlocked(str(R.string.chat_blocked_cleartext_http))
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
        // does not start (e.g. the session is busy, which launchTurn drops silently) the state is
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
    internal fun continueGoal(
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
     * 3. the cleartext host:port gate (doc 10 section 2.5); 4. the attachment send
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
    fun compactContext() {
        if (_screen.value.isDraft || _screen.value.isSending || stagedAttachments.isNotEmpty()) return
        val sessionId = openSessionId ?: return
        sendSubmission(ChatSubmission(sessionId, 0, idGenerator(), ContextCompaction.COMMAND))
    }

    /** Optional per-session file cache. Its success is never required for submission. */
    suspend fun saveComposerDraft(request: ChatSubmission): Boolean =
        withContext(Dispatchers.IO) {
            val cached = storage.composerDrafts.save(request.toInputSnapshot())
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
                storage.composerDrafts.get(sessionId)?.toSubmission()
            } catch (_: IllegalArgumentException) {
                null
            }
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
    suspend fun materializeDraftSession(expectedSessionId: String): String? =
        withContext(workScope.coroutineContext) {
            if (storage.sessions.find(expectedSessionId) != null) return@withContext expectedSessionId
            if (openSessionId != expectedSessionId) return@withContext null
            val draft = sessionDraft ?: return@withContext expectedSessionId
            val id = draft.session.id
            if (id != expectedSessionId || !drafts.beginPreparation(id)) return@withContext null
            try {
                val attachments =
                    saveSessionDraft("", id, str(R.string.chat_new_session)) ?: return@withContext null
                for (attachment in attachments) {
                    if (openSessionId != id) return@withContext null
                    stageAttachmentNow(attachment.uri, id)
                }
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
            if (openSessionId != sessionId) return@withContext attachmentIds
            val missing = mutableListOf<String>()
            val restored = mutableListOf<StagedAttachmentEntry>()
            for (id in attachmentIds) {
                val entry = stagingProcessor.restoreEntry(id, sessionId)
                if (entry != null) {
                    restored.add(entry)
                } else {
                    missing.add(id)
                }
            }
            synchronized(stagedLock) {
                if (openSessionId == sessionId) {
                    stagedAttachments = restored
                }
            }
            refreshScreen()
            missing
        }

    fun currentStagedAttachmentIds(expectedSessionId: String? = null): List<String> =
        synchronized(stagedLock) {
            stagedAttachments
                .filter {
                    expectedSessionId == null || it.sessionId == expectedSessionId
                }.map { it.artifactId }
        }

    /** CAS acknowledgement: an old receipt cannot clear an edited draft or another session. */
    suspend fun acknowledgeSubmission(receipt: ChatSubmissionReceipt): Boolean =
        withContext(Dispatchers.IO) {
            val request = receipt.submission
            val input = storage.sessionInputs.get(request.clientRequestId)
            val valid =
                when (val accepted = receipt.outcome) {
                    is ChatSubmissionOutcome.Enqueued -> {
                        input?.inputId == accepted.inputId && SessionInputBinding.matches(request, input)
                    }

                    is ChatSubmissionOutcome.Accepted -> {
                        if (input != null) {
                            input.consumedTurnId == accepted.turnId && SessionInputBinding.matches(request, input)
                        } else {
                            storage.turns.resolveByClientRequestId(request.clientRequestId)?.let {
                                it.id == accepted.turnId && it.sessionId == request.sessionId
                            } == true
                        }
                    }

                    else -> {
                        false
                    }
                }
            if (!valid) return@withContext false
            storage.composerDrafts.clear(request.sessionId, request.revision, request.clientRequestId)
        }

    /** Opens or restores the latest-user edit draft without changing history or stopping work. */
    fun prepareLatestRevision(
        sessionId: String,
        messageId: String,
    ): kotlinx.coroutines.Deferred<ChatSubmission?> =
        workScope.async {
            submissionGate.withLock {
                try {
                    require(openSessionId == sessionId && pendingSubmission == null && pendingInputResume == null)
                    require(storage.messages.latestUser(sessionId)?.id == messageId)
                    var saved = loadComposerDraft(sessionId)
                    if (saved?.revisedMessageId == null && saved?.text?.isEmpty() == true &&
                        saved.attachmentIds.isEmpty()
                    ) {
                        storage.composerDrafts.clear(sessionId, saved.revision, saved.clientRequestId)
                        saved = loadComposerDraft(sessionId)
                    }
                    require(saved == null || saved.revisedMessageId == messageId)
                    val source = MessageRevisionSource(storage, attachmentStaging)
                    val restored = source.attachments(sessionId, messageId)
                    require(
                        stagedAttachments.isEmpty() ||
                            stagedAttachments.map { it.artifactId } == restored.map { it.artifactId },
                    )
                    val draft =
                        saved ?: ChatSubmission(
                            sessionId,
                            ComposerEditClock.next(0),
                            idGenerator(),
                            source.text(messageId, restored.size),
                            restored.map { it.artifactId },
                            messageId,
                        )
                    if (saved == null) saveComposerDraft(draft)
                    require(openSessionId == sessionId)
                    synchronized(stagedLock) { stagedAttachments = restored }
                    refreshScreen()
                    draft
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    setBlocked(str(R.string.message_revision_unavailable))
                    null
                }
            }
        }

    /** Captures a user's revision without touching disk or changing historical messages. */
    fun revisionSubmission(
        request: ChatSubmission,
        text: String,
    ): ChatSubmission =
        if (request.text == text) {
            request
        } else {
            request.copy(
                revision = ComposerEditClock.next(request.revision),
                clientRequestId = idGenerator(),
                text = text,
            )
        }

    fun saveRevisionText(
        request: ChatSubmission,
        text: String,
    ): kotlinx.coroutines.Deferred<ChatSubmission?> {
        val snapshot = revisionSubmission(request, text)
        return workScope.async {
            if (snapshot.revisedMessageId == null) return@async null
            saveComposerDraft(snapshot)
            snapshot
        }
    }

    /** Read-only receipt recovery after a disclosure or recreation; never sends another request. */
    suspend fun acceptedRevision(request: ChatSubmission): Boolean =
        withContext(Dispatchers.IO) {
            submissionGate.withLock {
                val outcome = completedSubmission(request) ?: return@withLock false
                if (outcome !is ChatSubmissionOutcome.Accepted) return@withLock false
                acknowledgeSubmission(ChatSubmissionReceipt(request, outcome))
                true
            }
        }

    /** Discards only this edit snapshot, leaving all historical messages and newer drafts intact. */
    fun discardRevision(request: ChatSubmission): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            submissionGate.withLock {
                if (request.revisedMessageId == null) return@withLock false
                val cleared = storage.composerDrafts.clear(request.sessionId, request.revision, request.clientRequestId)
                if (cleared && openSessionId == request.sessionId) {
                    if (pendingSubmission == request) cancelPendingSend()
                    synchronized(stagedLock) {
                        stagedAttachments = stagedAttachments.filterNot { it.artifactId in request.attachmentIds }
                    }
                    refreshScreen()
                }
                cleared
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
                val outcome = submissionAttempt { admitSubmission(snapshot) }
                val receipt = ChatSubmissionReceipt(snapshot, outcome)
                if (outcome is ChatSubmissionOutcome.Accepted || outcome is ChatSubmissionOutcome.Enqueued) {
                    storage.composerDrafts.clear(snapshot.sessionId, snapshot.revision, snapshot.clientRequestId)
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
        } catch (_: Exception) {
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
                    saveSessionDraft(request.text)
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
            if (storage.messages.latestUser(request.sessionId)?.id != request.revisedMessageId) {
                return ChatSubmissionOutcome.Rejected("REVISION_TARGET_CHANGED")
            }
            if (storage.turns.listBySession(request.sessionId).any { !TurnState.valueOf(it.state).isTerminal }) {
                return ChatSubmissionOutcome.Rejected("REVISION_SESSION_BUSY")
            }
            return sendNow(request.text, submission = request)
        }
        if (openSessionId != request.sessionId) return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        return sendNow(request.text, submission = request)
    }

    private fun completedSubmission(request: ChatSubmission): ChatSubmissionOutcome? {
        storage.sessionInputs.get(request.clientRequestId)?.let {
            return if (SessionInputBinding.matches(request, it)) {
                it.consumedTurnId?.let { turnId -> ChatSubmissionOutcome.Accepted(turnId) }
                    ?: ChatSubmissionOutcome.Enqueued(it.inputId)
            } else {
                ChatSubmissionOutcome.Rejected("REQUEST_ID_ALREADY_USED")
            }
        }
        return storage.turns.resolveByClientRequestId(request.clientRequestId)?.let {
            completedLegacySubmission(request, it)
        }
    }

    private fun completedLegacySubmission(
        request: ChatSubmission,
        turn: com.helix.core.storage.entity.TurnEntity,
    ): ChatSubmissionOutcome {
        val matches = matchesPersistedSubmission(request, turn)
        return if (matches) {
            ChatSubmissionOutcome.Accepted(turn.id)
        } else {
            ChatSubmissionOutcome.Rejected("REQUEST_ID_ALREADY_USED")
        }
    }

    /** Receipt recovery uses accepted history, never an optional input cache. */
    @Suppress("ReturnCount") // Each mismatch refuses reuse of an accepted request identity.
    private fun matchesPersistedSubmission(
        request: ChatSubmission,
        turn: com.helix.core.storage.entity.TurnEntity,
    ): Boolean {
        if (turn.sessionId != request.sessionId) return false
        val message =
            storage.messages
                .allRevisions(request.sessionId)
                .firstOrNull { it.turnId == turn.id && it.role == "USER" } ?: return false
        val bindings = storage.messageAttachments.listByMessage(message.id)
        if (bindings.map { it.artifactId } != request.attachmentIds) return false
        val references = storage.messageReferenceSnapshots.forMessage(message.id)
        if (references.map { it.sourceSessionId to it.selectionKind } !=
            listOfNotNull(request.referenceSourceSessionId?.let { it to requireNotNull(request.referenceKind) })
        ) {
            return false
        }
        val body = storage.messages.readContentBounded(message, 8 * 1024 * 1024).orEmpty()
        if (AttachmentContext.authoredPrefix(body, bindings.size) != request.text) return false
        val expected =
            TurnInputFingerprint.of(
                body,
                bindings.map { MessageAttachmentRepository.Binding(it.artifactId, it.purpose, it.boundSha256) },
                references.map {
                    ConversationReferenceSnapshotInput(
                        it.sourceSessionId,
                        it.sourceSessionTitle,
                        it.selectionKind,
                        it.sourceMessageIds,
                        it.content,
                    )
                },
                revisedMessageId = request.revisedMessageId,
                recoveryFromTurnId = turn.recoveryFromTurnId,
            )
        return turn.inputFingerprint == expected
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
                !sessionDraft?.attachments.isNullOrEmpty() || request.referenceSourceSessionId != null
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
                !turnEngine.liveExecution.hasActive(sessionId) && !goalContinuation.hasActivation(sessionId)
            }
        }

    @Suppress(
        "ReturnCount",
        "CyclomaticComplexMethod",
        "LongMethod",
    ) // Linear fail-closed send gate stays auditable as one choke point.
    private suspend fun sendNow(
        text: String,
        goalId: String? = null,
        submission: ChatSubmission? = null,
        textOnly: Boolean = false,
    ): ChatSubmissionOutcome {
        if (text.length > MAX_MODEL_TEXT_CHARS || text.indexOf('\u0000') >= 0) {
            return submissionBlocked(str(R.string.chat_blocked_message_invalid, MAX_MODEL_TEXT_CHARS))
        }
        val staged = if (textOnly) emptyList() else stagedAttachments
        val hasReference = submission?.referenceSourceSessionId != null
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
        if (storage.sessions.find(session.id) != null && !ensureSessionWorkspace(session.id)) {
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
                    return submissionBlocked(str(R.string.chat_blocked_reference_unavailable))
                }
            }
        val providerId =
            session.providerId ?: run {
                return submissionBlocked(str(R.string.chat_blocked_no_provider_bound))
            }
        if (!providerService.chatSelectable(providerId)) {
            return submissionBlocked(str(R.string.chat_blocked_provider_untested))
        }
        if (!providerService.isCleartextPermitted(providerId)) {
            return submissionBlocked(str(R.string.chat_blocked_cleartext_http))
        }
        val target = providerService.egressTargetFor(providerId)
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
                    providerService.capabilitiesFor(
                        providerId,
                        openSessionId?.let { storage.sessions.resolve(it).modelId },
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
        val gate = AttachmentSendGate.evaluate(staged.map { it.toStagedAttachment() }, credentialScan)
        val outcome = AttachmentSendAdmission.admit(gate, text, target, strings, reference)
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
        staged: List<StagedAttachmentEntry>,
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
                if (staged.isNotEmpty() || reference != null) {
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
                    storage.composerDrafts.clear(request.sessionId, request.revision, request.clientRequestId)
                }
                ChatSubmissionReceipt(request, outcome)
            }
        }

    @Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught") // fail-closed gate checks
    private suspend fun confirmSendNow(request: ChatSubmission): ChatSubmissionOutcome {
        val text = pendingSend ?: return ChatSubmissionOutcome.Rejected("CONFIRMATION_CHANGED")
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
        if (!providerService.chatSelectable(providerId)) {
            return submissionBlocked(str(R.string.chat_blocked_provider_untested))
        }
        if (!providerService.isCleartextPermitted(providerId)) {
            return submissionBlocked(str(R.string.chat_blocked_cleartext_http))
        }
        // ADR-0014 §5: the approval bound a SPECIFIC egress target (provider + origin).
        // A provider edit/re-test that moved the endpoint between the dialog and this
        // tap voids the old confirmation — block and make the user re-send; the
        // disclosure is NOT re-shown, and nothing may leave through a drifted origin.
        val liveTarget =
            try {
                providerService.egressTargetFor(providerId)
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
     * AFTER [confirmSendNow]'s provider/cleartext/target drift re-checks. The current staged set
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
        val staged = if (questionAnswer) emptyList() else stagedAttachments
        // ADR-0014 §5: the user approved a SPECIFIC enumerated set of attachments — the dialog
        // listed exactly [approvedAttachmentIds]. If the staged set has since changed, a file
        // staged while the dialog was up or one removed since — the approval no longer covers
        // what is on the wire; block and make the user re-send. A file never shown in the dialog
        // never leaves the device, and a removed one is not silently turned into a pure-text
        // send. A pure-text pending has both empty, so this passes and the path below is
        // byte-identical to pre-attachment (no regression). The staged attachments STAY pending.
        if (staged.map { it.artifactId } != approvedAttachmentIds) {
            return submissionBlocked(str(R.string.chat_blocked_attachments_changed))
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
        if (materialized.any { it is AttachmentMaterialization.Image }) {
            // capabilitiesFor is fail-closed by contract (null on any stored-snapshot failure).
            val visionConfirmed =
                runCatching {
                    providerService.capabilitiesFor(
                        providerId,
                        openSessionId?.let { storage.sessions.resolve(it).modelId },
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
        // Ready: the gate's attachments are in staged order — pair each with its staged entry
        // and build (a) the expanded model-visible user message (bounded UNTRUSTED blocks;
        // image blocks describe the pixels that travel as the message's image parts) and
        // (b) the message_attachments bindings [TurnCoordinator.start] persists IN the turn's
        // transaction. An image BINDS THE NORMALIZED ARTIFACT (the bytes that leave) — the raw
        // artifact stays registered but unbound (local save/preview source).
        val blocks = attachmentContextBlocks(materialized, staged)
        val bindings =
            staged.map { entry ->
                MessageAttachmentRepository.Binding(
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
            synchronized(stagedLock) {
                stagedAttachments = stagedAttachments.filterNot { it.artifactId in approvedAttachmentIds }
            }
        }
        refreshScreen()
        return outcome
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
        staged: List<StagedAttachmentEntry>,
    ): List<AttachmentContextBlock> =
        materialized.mapIndexed { index, m ->
            when (m) {
                is AttachmentMaterialization.Text -> {
                    AttachmentContextBlock.Text(m, staged[index].relativePath)
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

    /** A staged entry as the gate's input — the real paths cross into hashing/probing only. */
    private fun StagedAttachmentEntry.toStagedAttachment() =
        StagedAttachment(
            fileName = fileName,
            boundSha256 = boundSha256,
            file = file,
            normalizedFile = normalizedFile,
            normalizedSha256 = normalizedSha256,
            mediaType = normalizedMediaType,
            normalizedWidth = normalizedWidth,
            normalizedHeight = normalizedHeight,
        )

    /**
     * The fail-closed re-verification that runs right before a user-approved egress goes out
     * (ADR-0014 §5): every staged file is re-hashed against its bound snapshot and its FULL
     * content is re-scanned for credential shapes ([credentialScan]). Any changed, vanished or
     * credentialed file sets the blocked state and returns null — the staged attachments STAY
     * pending (a block is never a silent drop) and the disclosure is NOT re-shown. The
     * materialized attachments (in staged order) are returned when the gate is Ready.
     */
    private fun reVerifyStagedForEgress(
        staged: List<StagedAttachmentEntry>,
        text: String,
        target: EgressDisclosure.EgressTarget,
    ): List<AttachmentMaterialization>? {
        val gate = AttachmentSendGate.evaluate(staged.map { it.toStagedAttachment() }, credentialScan)
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
                setBlocked(str(R.string.chat_blocked_snapshot_verify_failed))
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
     * The stop button: cancels the OPEN session's in-flight turn (its service-owned Job). A turn
     * waiting on the approval card is cancelled through the broker (the pending record stays
     * PENDING — the user never decided — and expires with its window); a tool executing sees the
     * turn's cancel flag at the dispatcher's stage checks (CANCELLED_AFTER_START / BEFORE_START).
     * Other sessions' in-flight turns keep running (the per-session model) and are stopped from
     * their own screen.
     */

    fun collectTaskResult(turnId: String) {
        workScope.launch {
            storage.turns.collectResult(turnId, clock.now().toEpochMilli())
            refreshBackgroundTasks()
        }
    }

    internal suspend fun taskResult(turnId: String): List<MessageUi> =
        withContext(Dispatchers.IO) {
            val turn = storage.turns.resolve(turnId)
            check(TurnState.valueOf(turn.state).isTerminal || turn.state == "INTERRUPTED")
            projection.messagesFor(turn.sessionId, EMPTY_SCREEN).filter { it.turnId == turnId }
        }

    /**
     * The turn's artifact file rows by REAL ownership (HXA-202 slice 2): the rows the turn's
     * tools actually wrote. A pure read — the Tasks page must find the task's files without
     * the artifact center's truncated global window, and without starting or continuing
     * anything.
     */
    internal suspend fun taskArtifactsForTurn(turnId: String): List<ArtifactRowUi> =
        withContext(Dispatchers.IO) { ArtifactQuery(storage).forTurn(turnId) }

    internal suspend fun conversationArtifacts(sessionId: String): List<ArtifactRowUi> =
        withContext(Dispatchers.IO) { ArtifactQuery(storage).forSession(sessionId) }

    /** The goal's artifact file rows: the union of every turn bound to the goal (HXA-202). */
    internal suspend fun taskArtifactsForGoal(goalId: String): List<ArtifactRowUi> =
        withContext(Dispatchers.IO) { ArtifactQuery(storage).forGoal(goalId) }

    /**
     * HXA-194: one command call's details, projected from persisted facts ONLY — a pure
     * read. Opening, re-opening or rotating the details page never starts, replays,
     * submits or acknowledges anything; the explicit reconciliation stays the existing
     * session entry (查看结果).
     */
    internal suspend fun commandResult(
        turnId: String,
        callId: String,
    ): com.helix.app.proot.CommandResultView? =
        withContext(Dispatchers.IO) {
            com.helix.app.proot.CommandResultBrowser
                .browse(storage, turnId, callId)
        }

    /**
     * HXA-194: the turn's command calls (the Linux command tools) for the task page's
     * command list entry. A pure read — never starts or continues anything.
     */
    internal suspend fun turnCommands(turnId: String): List<com.helix.app.proot.CommandEntry> =
        withContext(Dispatchers.IO) {
            val turn = storage.turns.resolve(turnId)
            storage.toolCalls
                .listByTurn(turnId)
                .filter { it.name in com.helix.app.proot.COMMAND_TOOL_NAMES }
                .map { call ->
                    com.helix.app.proot.CommandEntry(
                        call.callId,
                        call.name,
                        com.helix.app.proot
                            .CommandResultProjection
                            .commandTextFromArgs(call.argsJson),
                        call.state,
                        turn.state,
                    )
                }
        }

    /**
     * A stale card cannot stop a newer Turn in the same session. Pause is Goal-only.
     * On the stable-ID match it durably persists CANCELLING before signalling the
     * cancellation (HXA-202 slice 3), so the Tasks dashboard shows "cancelling, awaiting
     * settlement" until the single settlement transaction commits the terminal state.
     */
    fun stopTask(
        turnId: String,
        pause: Boolean = false,
        systemReason: String? = null,
    ) {
        workScope.launch {
            if (systemReason != null) {
                require(systemReason in setOf("FGS_START_REJECTED", "FGS_TIMEOUT", "FGS_SERVICE_LOST"))
            }
            if (pause) {
                if (storage.goalTurnBindings.byTurn(turnId) == null) return@launch
                if (!storage.turns.requestPause(turnId, clock.now().toEpochMilli())) return@launch
            }
            if (systemReason != null) turnEngine.requestSystemStop(turnId, systemReason)
            stopTurn(turnId)
        }
    }

    /** Stable-ID entry shared by chat, Tasks and the runtime; never resolves a newer live turn. */
    suspend fun stopTurn(turnId: String): com.helix.core.agent.CancelResult =
        withContext(Dispatchers.IO) { agentRuntime.cancel(TurnId(turnId)) }

    /**
     * The still-running (non-terminal, not interrupted) turns in [sessionId] — the "previously
     * started tasks" left in flight when a session's permission rules tighten (HXA-209 D5). Like
     * [refreshBackgroundTasks]'s source, this is a synchronous Room read: callers must run it off
     * the main thread (the settings section wraps it in Dispatchers.IO).
     */
    fun runningTurnIdsForSession(sessionId: String): List<String> =
        BackgroundTaskQuery(storage)
            .read()
            .filter { it.sessionId == sessionId && it.running }
            .map { it.id }

    private fun refreshBackgroundTasks() {
        synchronized(turnGate) {
            val tasks = BackgroundTaskQuery(storage).read()
            _backgroundTasks.value = tasks
            backgroundJobsState.value =
                com.helix.app.proot.ProotToolModule
                    .backgroundJobs(storage)
            transportState.value = tasks
                .firstOrNull {
                    !it.awaitingApproval &&
                        it.state in com.helix.app.foreground.DataSyncForegroundController.TRANSPORT_ACTIVE
                }?.state ?: if (goalContinuation.hasHandoff) TurnState.BUILDING_CONTEXT else null
        }
    }

    /**
     * Re-read the background-task list from storage onto the shared [backgroundTasks] flow.
     * Runs on the service work scope (a Room read, never the main thread); the task dashboards
     * call it on entry and on an explicit refresh so a task finished while the app was closed —
     * or written directly — is visible on open, after which the shared StateFlow stays live.
     */
    fun refreshBackgroundTasksNow() {
        workScope.launch { refreshBackgroundTasks() }
    }

    /**
     * The dashboard's persistent facts — every goal, the plan review queue, and the artifact
     * center's real file rows (doc 02 §8) — onto their shared flows ([goalDashboard] /
     * [planDashboard] / [artifactFiles]), the same re-read pattern as [refreshBackgroundTasks].
     * Called after every goal/plan mutation and on each turn-state change ([publishTurn]) —
     * files are written DURING turns, so a turn-state change is when a new artifact row can
     * appear — so an open Tasks or Artifacts screen observes the live state instead of a
     * screen-entry snapshot; screen entry and an explicit refresh go through
     * [refreshTaskDashboardsNow]. The reads hop to [Dispatchers.IO] rather than trusting the
     * caller: the goal/plan mutations that re-read after their write run on the CALLER's
     * dispatcher — the UI's main one — and Room refuses database work on the main thread.
     */
    private suspend fun refreshTaskDashboards() =
        withContext(Dispatchers.IO) {
            goalDashboardState.value = GoalSummaryQuery(storage).forAll()
            planDashboardState.value = PlanRowQuery(storage).read()
            artifactFilesState.value = ArtifactQuery(storage).recent(ARTIFACT_FILES_LIMIT)
        }

    fun refreshTaskDashboardsNow() {
        workScope.launch { refreshTaskDashboards() }
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

    fun approveApproval(approvalId: String) = toolCalls.approveApproval(approvalId)

    fun denyApproval(approvalId: String) = toolCalls.denyApproval(approvalId)

    fun onApprovalCard(
        approvalId: String,
        request: ApprovalRequest,
    ) = toolCalls.onApprovalCard(approvalId, request)

    fun dispatchToolCall(
        toolCallId: String,
        turnId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        mode: AgentMode = AgentMode.ACT,
        chatToolsEnabled: Boolean = false,
    ): ToolDispatchOutcome =
        toolCalls.dispatchToolCall(
            toolCallId,
            turnId,
            toolNameRaw,
            rawArgsJson,
            mode,
            chatToolsEnabled,
        )

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
            val stopped = storage.turns.resolve(targetTurnId)
            if (stopped.sessionId != session.id) return@launch
            val continueResults = BudgetContinuation.eligible(storage, stopped)
            if (BudgetContinuation.blocksRetry(storage, stopped, continueResults)) {
                return@launch
            }
            val turnId =
                RetryMessageSource.resolve(
                    targetTurnId,
                    storage.turns.listBySession(session.id).map { it.id },
                    storage.messages
                        .listBySession(
                            session.id,
                        ).filter { it.role == "USER" }
                        .mapNotNull { it.turnId }
                        .toSet(),
                ) ?: return@launch
            val providerId = session.providerId ?: return@launch
            if (!providerService.chatSelectable(providerId)) {
                setBlocked(str(R.string.chat_blocked_provider_untested))
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
        when (val stagedCheck = attachmentRetry.retryStagedFor(sessionId, turnId)) {
            RetryStagedCheck.None -> {
                return true
            }

            RetryStagedCheck.Unavailable -> {
                setBlocked(str(R.string.chat_blocked_snapshot_recheck_failed))
                return false
            }

            is RetryStagedCheck.Staged -> {
                val gate = AttachmentSendGate.evaluate(stagedCheck.attachments, credentialScan)
                if (gate !is AttachmentSendDecision.Ready) {
                    val reason =
                        if (gate is AttachmentSendDecision.CredentialDetected) {
                            egressRejectedLabel(gate.reason)
                        } else {
                            str(R.string.chat_blocked_snapshot_recheck_failed)
                        }
                    setBlocked(reason)
                    return false
                }
                return true
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
            val goalId = storage.goalTurnBindings.byTurn(turnId)?.let { storage.goalRuns.resolve(it.runId).goalId }
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
            val session = storage.sessions.resolve(clickedSessionId)
            val assistant = runCatching { storage.messages.resolve(assistantMessageId) }.getOrNull() ?: return@launch
            if (assistant.sessionId != session.id || assistant.role != "ASSISTANT") return@launch
            if (assistant.supersededBy != null) return@launch
            val targetTurnId = assistant.turnId ?: return@launch

            val turnId =
                RetryMessageSource.resolve(
                    targetTurnId,
                    storage.turns.listBySession(session.id).map { it.id },
                    storage.messages
                        .listBySession(session.id)
                        .filter { it.role == "USER" }
                        .mapNotNull { it.turnId }
                        .toSet(),
                ) ?: return@launch

            val providerId = session.providerId ?: return@launch
            if (!providerService.chatSelectable(providerId)) {
                setBlocked(str(R.string.chat_blocked_provider_untested))
                return@launch
            }

            if (!verifyRetryAttachments(session.id, turnId)) return@launch

            val requestId = idGenerator()
            val goalId = storage.goalTurnBindings.byTurn(turnId)?.let { storage.goalRuns.resolve(it.runId).goalId }
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

    private suspend fun startRuntimeTurn(
        command: SubmitTurnCommand,
        control: RunControlConfig,
    ): String? =
        launchTurn(
            text = command.text,
            providerId = command.providerId.value,
            retryTurnId = command.retryTurnId?.value,
            goalId = command.goalId?.value,
            attachmentBindings =
                command.attachments.map {
                    MessageAttachmentRepository.Binding(
                        artifactId = it.artifactId,
                        purpose = AttachmentPurpose.REFERENCE,
                        boundSha256 = it.boundSha256,
                    )
                },
            referenceSnapshots =
                command.references.map { reference ->
                    ConversationReferenceSnapshotInput(
                        sourceSessionId = reference.sourceSessionId,
                        sourceSessionTitle = reference.sourceSessionTitle,
                        selectionKind = ConversationReferenceKind.valueOf(reference.selectionKind),
                        sourceMessageIds = reference.sourceMessageIds,
                        content = reference.content,
                    ).also { snapshot ->
                        require(snapshot.contentSha256 == reference.contentSha256) {
                            "reference snapshot hash mismatch"
                        }
                    }
                },
            requestedSessionId = command.session.value,
            controlOverride = control,
            clientRequestId = command.clientRequestId,
            continuousGoal = command.continuousGoal,
            continuation = command.goalContinuation,
            directUserRequest = command.directUserRequest,
            revisedMessageId = command.revisedMessageId,
            regenerateMessageId = command.regenerateMessageId,
        )

    private suspend fun cancelRuntimeTurn(turnId: String): TurnCancelOutcome =
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            val outcome =
                synchronized(turnGate) {
                    val task = storage.turns.resolve(turnId)
                    val owner = turnEngine.liveExecution.active(task.sessionId)
                    val handoffOwner = goalContinuation.handoffOwner(task.sessionId)
                    val ownsHandoff = owner == null && handoffOwner == turnId
                    val ownsDelivery = owner?.turnId == turnId || ownsHandoff
                    if (ownsDelivery || (owner == null && handoffOwner == null)) {
                        goalContinuation.disarmTurn(task.sessionId, turnId)
                    }
                    goalUserRequests.remove(turnId)
                    val active = owner?.takeIf { it.turnId == turnId }
                    val decision =
                        turnEngine.requestCancel(
                            turnId = turnId,
                            sessionId = task.sessionId,
                            hasLiveDriver = active != null,
                            parkTerminalDelivery = ownsDelivery,
                            reason = "USER_STOP",
                        )
                    val result =
                        when (decision) {
                            is EngineCancelDecision.AlreadyTerminal -> {
                                if (ownsDelivery) goalContinuation.disarm(task.sessionId)
                                TurnCancelOutcome.AlreadyTerminal(decision.phase)
                            }

                            EngineCancelDecision.SignalLive -> {
                                goalContinuation.disarm(task.sessionId)
                                TurnCancelOutcome.StoppedLive
                            }

                            EngineCancelDecision.ReviewRequired -> {
                                TurnCancelOutcome.ReviewRequired
                            }
                        }
                    if (result == TurnCancelOutcome.StoppedLive) {
                        publishTurn(TurnUi(turnId, TurnState.CANCELLING, null, null, false))
                        active?.signalCancel()
                        toolCalls.cancelPendingApproval(turnId)
                        active?.job?.cancel()
                    }
                    result
                }
            refreshScreen()
            refreshBackgroundTasks()
            syncGoalReminderForTurn(turnId)
            outcome
        }

    // --------------------------------------------------------------------------------
    // Turn admission + application projections. Engine owns the live driver.
    // --------------------------------------------------------------------------------

    /**
     * The in-app turn entry (HX2-01): send, confirmed egress, retry and goal start all drive the
     * turn through the unified [agentRuntime] — the command carries the current run-control
     * snapshot (the per-turn facts) and the producer's approved attachment intents. A start the
     * session refused has ALREADY surfaced its safe blocked state inside [launchTurn], so the
     * adapter's [TurnStartBlocked] is swallowed — it is a second signal, never the first.
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
            true
        } catch (e: TurnStartBlocked) {
            // the refused start already set the session's blocked state (fail-closed, safe label);
            // this return value is the SECOND signal, for a caller (the plan-execute path) that
            // must react to a start that did not happen — it is never the first.
            false
        }
    }

    private fun submissionBlocked(reason: String): ChatSubmissionOutcome.Rejected {
        setBlocked(reason)
        return ChatSubmissionOutcome.Rejected(reason)
    }

    private suspend fun submitMessageTurn(
        submission: ChatSubmission,
        text: String,
        providerId: String,
        goalId: String?,
        attachments: List<AttachmentBindingIntent> = emptyList(),
        reference: ConversationReferenceSnapshotInput? = null,
    ): ChatSubmissionOutcome {
        if (submission.revisedMessageId == null && goalId == null) {
            return acceptSessionInput(submission, providerId, attachments, reference)
        }
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
            val turn = requireNotNull(storage.turns.resolveByClientRequestId(submission.clientRequestId))
            ChatSubmissionOutcome.Accepted(turn.id)
        }
    }

    fun sessionInputQueue(sessionId: String): kotlinx.coroutines.Deferred<List<SessionInputRecord>> =
        workScope.async { storage.sessionInputs.listPending(sessionId) }

    fun sessionInputDeliveryStatus(sessionId: String): kotlinx.coroutines.Deferred<List<SessionInputRecord>> =
        workScope.async {
            storage.sessionInputs.listPending(sessionId) + storage.sessionInputs.recentAppended(sessionId)
        }

    fun readSessionInput(inputId: String): kotlinx.coroutines.Deferred<String?> =
        workScope.async { storage.sessionInputs.get(inputId)?.let(storage.sessionInputs::readText) }

    fun withdrawSessionInput(
        inputId: String,
        expectedRevision: Long,
    ): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            submissionGate.withLock {
                val input = storage.sessionInputs.get(inputId) ?: return@withLock false
                val withdrawn =
                    synchronized(turnGate) {
                        storage.sessionInputs.withdrawPending(inputId, expectedRevision, clock.now().toEpochMilli())
                    }
                if (withdrawn) requestSessionDrain(input.sessionId)
                withdrawn
            }
        }

    fun editSessionInput(
        inputId: String,
        expectedRevision: Long,
        text: String,
    ): kotlinx.coroutines.Deferred<Boolean> =
        workScope.async {
            submissionGate.withLock {
                val input = storage.sessionInputs.get(inputId) ?: return@withLock false
                val invalidShape = text.length > MAX_MODEL_TEXT_CHARS || '\u0000' in text
                val invalidText = invalidShape || credentialScan(text) != null
                if (invalidText) return@withLock false
                if (text.isBlank() && input.attachments.isEmpty() && input.reference == null) return@withLock false
                synchronized(turnGate) {
                    var edited = false
                    storage.withTransaction {
                        edited =
                            storage.sessionInputs.editPending(
                                inputId,
                                expectedRevision,
                                SessionInputSpec(
                                    inputId,
                                    input.sessionId,
                                    input.delivery,
                                    input.expectedTurnId,
                                    expectedRevision + 1,
                                    text,
                                    input.attachments,
                                    input.configuration,
                                    clock.now().toEpochMilli(),
                                    reference = storage.sessionInputs.readReference(input),
                                ),
                            )
                        if (edited) {
                            storage.sessionInputs.markNeedsAttention(
                                inputId,
                                expectedRevision + 1,
                                "INPUT_EDITED",
                                clock.now().toEpochMilli(),
                            )
                        }
                    }
                    edited
                }
            }
        }

    fun resumeSessionInput(
        inputId: String,
        expectedRevision: Long,
    ): kotlinx.coroutines.Deferred<ChatSubmissionOutcome> =
        workScope.async {
            submissionGate.withLock {
                submissionAttempt {
                    val input =
                        storage.sessionInputs.get(inputId)
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
                        storage.sessionInputs.get(inputId)
                            ?: return@submissionAttempt ChatSubmissionOutcome.Rejected("INPUT_NOT_FOUND")
                    if (input != approval.input || validatedInput(input) == null ||
                        providerService.egressTargetFor(input.configuration.providerId) != approval.target
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

    private suspend fun prepareInputResumeConfirmation(input: SessionInputRecord): ChatSubmissionOutcome {
        val target = providerService.egressTargetFor(input.configuration.providerId)
        val admission =
            SessionInputAttachments(storage, attachmentStaging, credentialScan)
                .disclosure(input, target, strings)
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

    private fun resumeValidatedInput(input: SessionInputRecord): ChatSubmissionOutcome {
        val resumed =
            synchronized(turnGate) {
                storage.sessionInputs.resumePending(input.inputId, input.revision, clock.now().toEpochMilli())
            }
        if (!resumed) return ChatSubmissionOutcome.Rejected("INPUT_CHANGED")
        requestSessionDrain(input.sessionId)
        return ChatSubmissionOutcome.Enqueued(input.inputId)
    }

    /** Egress has already been approved for these exact bytes and provider before this admission. */
    private suspend fun acceptSessionInput(
        request: ChatSubmission,
        providerId: String,
        attachments: List<AttachmentBindingIntent>,
        reference: ConversationReferenceSnapshotInput?,
    ): ChatSubmissionOutcome {
        val accepted = persistSessionInput(request, providerId, attachments, reference)
        if (accepted is SessionInputAcceptResult.Rejected) return ChatSubmissionOutcome.Rejected(accepted.reason)
        val input = (accepted as SessionInputAcceptResult.Accepted).record
        synchronized(turnGate) {
            val interruptedOwner =
                !turnEngine.liveExecution.hasActive(input.sessionId) &&
                    turnEngine.sessionBlocker(input.sessionId) != null
            if (interruptedOwner) {
                storage.sessionInputs.markNeedsAttention(
                    input.inputId,
                    input.revision,
                    "SESSION_NEEDS_ATTENTION",
                    clock.now().toEpochMilli(),
                )
            }
        }
        // A fresh user action can start after a Stop without releasing older parked inputs.
        val bypassParked =
            synchronized(turnGate) {
                val noActiveTurn = !turnEngine.liveExecution.hasActive(input.sessionId)
                val queueHead = storage.sessionInputs.headQueue(input.sessionId)
                noActiveTurn && queueHead?.state == SessionInputState.NEEDS_ATTENTION
            }
        if (input.delivery == SessionInputDelivery.QUEUE) consumeAcceptedInput(input, !bypassParked)
        requestSessionDrain(input.sessionId)
        val current = requireNotNull(storage.sessionInputs.get(input.inputId))
        return current.consumedTurnId?.let { ChatSubmissionOutcome.Accepted(it) }
            ?: ChatSubmissionOutcome.Enqueued(current.inputId)
    }

    private suspend fun persistSessionInput(
        request: ChatSubmission,
        providerId: String,
        attachments: List<AttachmentBindingIntent>,
        reference: ConversationReferenceSnapshotInput?,
    ): SessionInputAcceptResult {
        val session = storage.sessions.resolve(request.sessionId)
        val facts = providerSnapshot(providerId, session.modelId)
        val modelId = session.modelId ?: providerService.storedConfig(providerId).model
        val selected = sessionRunControls.ensure(request.sessionId, clock.now().toEpochMilli())
        return synchronized(turnGate) {
            val active = turnEngine.liveExecution.active(request.sessionId)
            val stopped = active?.let { storage.turns.resolve(it.turnId).state == TurnState.CANCELLING.name } == true
            if (stopped) return@synchronized SessionInputAcceptResult.Rejected("TURN_CANCELLING")
            val control =
                inputControlForAcceptance(request, active, selected)
                    ?: return@synchronized SessionInputAcceptResult.Rejected("STEER_TARGET_NOT_LIVE")
            if (request.delivery == SessionInputDelivery.STEER) {
                val originalCall = storage.modelCalls.listByTurn(requireNotNull(request.expectedTurnId)).firstOrNull()
                if (active?.turnId != request.expectedTurnId || originalCall?.providerSnapshot != facts) {
                    return@synchronized SessionInputAcceptResult.Rejected("INPUT_CONFIGURATION_CHANGED")
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
            storage.sessionInputs.accept(
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
                ),
            )
        }
    }

    private fun inputControlForAcceptance(
        request: ChatSubmission,
        active: com.helix.app.engine.LiveTurnHandle?,
        selected: RunControlConfig,
    ): RunControlConfig? =
        when {
            request.delivery == SessionInputDelivery.STEER -> sessionInputDelivery.steerControl(request.expectedTurnId)

            selected.mode == AgentMode.GOAL &&
                (
                    active != null ||
                        goalContinuation.hasActivation(request.sessionId) ||
                        UnresolvedEffectPolicy.hasUnresolvedEffects(storage, request.sessionId)
                ) -> selected.copy(mode = AgentMode.ACT)

            else -> selected
        }

    private fun queueStillConsumable(
        input: SessionInputRecord,
        requireHead: Boolean,
    ): Boolean = sessionInputDelivery.queueStillConsumable(input, requireHead)

    @Suppress("TooGenericExceptionCaught") // Admission already committed: retain its receipt and park failed delivery.
    private suspend fun consumeAcceptedInput(input: SessionInputRecord, requireHead: Boolean) {
        try {
            consumeQueueInput(input, requireHead)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            synchronized(turnGate) {
                storage.sessionInputs.markNeedsAttention(
                    input.inputId,
                    input.revision,
                    "INPUT_DELIVERY_FAILED",
                    clock.now().toEpochMilli(),
                )
            }
            Log.e(TAG, "Accepted input delivery failed", error)
        }
    }

    private suspend fun consumeQueueInput(
        input: SessionInputRecord,
        requireHead: Boolean = true,
    ): String? = sessionInputDelivery.consumeQueue(input, requireHead)

    private suspend fun validatedInput(
        input: SessionInputRecord,
    ): Pair<String, List<MessageAttachmentRepository.Binding>>? = sessionInputDelivery.revalidate(input)

    private fun bindInputAuthority(
        sessionId: String,
        turnId: String,
        modelCallId: String,
        messageIds: Set<String>,
    ) {
        val inputs =
            storage.sessionInputs.appendedForMessages(turnId, messageIds).filter {
                it.sessionId == sessionId && it.requestModelCallId == modelCallId
            }
        val control = turnEngine.liveExecution.byTurn(turnId)?.control ?: return
        val modelCall = storage.modelCalls.resolve(modelCallId)
        val retained =
            goalUserRequests[turnId]
                .orEmpty()
                .filter { it.sourceMessageId == null || it.sourceMessageId in messageIds }
        val current =
            inputs.map { input ->
                GoalUserRequest(
                    sessionId,
                    storage.sessionInputs.readText(input),
                    input.configuration.providerId,
                    control,
                    modelCall.providerSnapshot,
                    input.messageId,
                    input.inputId,
                )
            }
        goalUserRequests[turnId] = (current + retained).distinctBy { it.sourceMessageId }
    }

    /** Every wake queues on the same admission mutex; a wake arriving during drain is never lost. */
    @Suppress("TooGenericExceptionCaught")
    // Background admission failure parks inputs rather than silently dropping them.
    private fun requestSessionDrain(sessionId: String, handoffTurnId: String? = null) {
        sessionWorkScheduler.requestDrain(sessionId, handoffTurnId)
    }

    // one fail-closed return per guard (session, snapshot, turn gate); one branch per guard plus
    // the idempotency dedup check (HX2-01 §2e)
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // One atomic admission/activation transaction.
    private suspend fun launchTurn(
        text: String?,
        providerId: String,
        retryTurnId: String? = null,
        attachmentBindings: List<MessageAttachmentRepository.Binding> = emptyList(),
        referenceSnapshots: List<ConversationReferenceSnapshotInput> = emptyList(),
        goalId: String? = null,
        requestedSessionId: String? = null,
        controlOverride: RunControlConfig? = null,
        clientRequestId: String,
        continuousGoal: Boolean = false,
        continuation: com.helix.core.agent.GoalContinuationRequest? = null,
        directUserRequest: Boolean = false,
        revisedMessageId: String? = null,
        queuedInput: SessionInputRecord? = null,
        requireQueueHead: Boolean = true,
        regenerateMessageId: String? = null,
        recoveryParent: String? = null,
    ): String? {
        // The unified AgentRuntime (HX2-01) starts turns for an explicit session with an explicit
        // per-turn control; the in-session send path passes neither and falls back to the open
        // session + the current run-control (behavior unchanged).
        val session = resolveTurnSession(requestedSessionId) ?: return null
        val sessionId = session.id
        // Snapshot before creating the durable Turn: later UI/profile changes cannot alter this
        // Turn's mode, tool table, dispatcher mode, or limits.
        val selectedControl = sessionRunControls.ensure(sessionId, clock.now().toEpochMilli())
        val control = controlOverride ?: selectedControl
        val sourceText =
            text ?: retryTurnId?.let { sourceTurn ->
                storage.messages
                    .listBySession(sessionId)
                    .lastOrNull { it.turnId == sourceTurn && it.role == "USER" }
                    ?.let {
                        com.helix.app.agent.ContextHistory
                            .read(storage, it)
                    }
            }
        val manualCompaction = sourceText == ContextCompaction.COMMAND
        // The Room read runs OUTSIDE the gate: a suspend point must never be reached while holding the monitor.
        val snapshot =
            try {
                providerSnapshot(providerId, session.modelId)
            } catch (e: IllegalArgumentException) {
                // The provider row was deleted or is corrupt between the gate
                // and the snapshot (storedConfig throws IAE for both): no turn
                // row has been written yet, so surface a blocked state — the
                // send must never vanish.
                Log.e(TAG, "could not snapshot provider $providerId", e)
                setBlocked(str(R.string.chat_blocked_provider_state_changed))
                return null
            }
        val admittedModelId = session.modelId ?: providerService.storedConfig(providerId).model
        synchronized(turnGate) {
            if (queuedInput != null &&
                !SessionInputBinding.matchesConfiguration(
                    queuedInput,
                    snapshot,
                    control,
                    selectedControl.mode.name,
                )
            ) {
                storage.sessionInputs.markNeedsAttention(
                    queuedInput.inputId,
                    queuedInput.revision,
                    "INPUT_CONFIGURATION_CHANGED",
                    clock.now().toEpochMilli(),
                )
                return null
            }
            if (continuation != null && storage.sessionInputs.headQueue(sessionId) != null) return null
            if (continuation != null && !goalContinuation.admits(sessionId, goalId, continuation, snapshot)) return null
            val canRecover =
                revisedMessageId == null &&
                    regenerateMessageId == null &&
                    retryTurnId == null &&
                    continuation == null &&
                    (queuedInput != null || directUserRequest)
            val recoveryFromTurnId =
                if (recoveryParent != null) {
                    recoveryParent
                } else if (canRecover) {
                    turnEngine.recoveryPredecessor(sessionId, clientRequestId)
                } else {
                    null
                }
            val inputFingerprint =
                TurnInputFingerprint.of(
                    text,
                    attachmentBindings,
                    references = referenceSnapshots,
                    revisedMessageId = revisedMessageId,
                    regenerateMessageId = regenerateMessageId,
                    recoveryFromTurnId = recoveryFromTurnId,
                )
            // Persistent submit-dedup (research doc section 34): a re-drive with the same session +
            // input returns the started turn; a diverged session or input is a conflict (refused).
            when (val receipt = turnEngine.submissionReceipt(clientRequestId, sessionId, inputFingerprint)) {
                is com.helix.app.engine.SubmitReceiptDecision.Deduplicated -> {
                    return receipt.turnId
                }

                com.helix.app.engine.SubmitReceiptDecision.Conflict -> {
                    setBlocked(str(R.string.turn_submit_conflict))
                    return null
                }

                com.helix.app.engine.SubmitReceiptDecision.Fresh -> {
                    Unit
                }
            }
            // Durable Room truth blocks only genuinely live non-terminal Turns. NEEDS_REVIEW is
            // execution-stopped history; successor admission is allowed and effects are gated later.
            turnEngine.sessionBlocker(sessionId)?.let {
                setBlocked(str(R.string.chat_blocked_session_busy))
                return null
            }
            // The process-local registry remains a same-process race guard until E1-B2 moves Jobs.
            if (turnEngine.liveExecution.hasActive(sessionId)) {
                setBlocked(str(R.string.chat_blocked_session_busy))
                return null
            }
            if (queuedInput != null && !queueStillConsumable(queuedInput, requireQueueHead)) return null
            val isGoalOrRetry =
                goalId != null || continuousGoal || control.mode == AgentMode.GOAL || retryTurnId != null
            if (revisedMessageId != null && isGoalOrRetry) {
                return null
            }
            if (regenerateMessageId != null && (revisedMessageId != null || text != null)) {
                return null
            }
            val liveSession = storage.sessions.resolve(sessionId)
            if (liveSession.providerId != providerId || liveSession.modelId != session.modelId) {
                setBlocked(str(R.string.chat_blocked_provider_state_changed))
                return null
            }
            val turnId = idGenerator()
            val callId = idGenerator()
            val spec =
                turnStartSpec(
                    sessionId = sessionId,
                    turnId = turnId,
                    callId = callId,
                    snapshot = snapshot,
                    text = text,
                    attachmentBindings = attachmentBindings,
                    referenceSnapshots = referenceSnapshots,
                    clientRequestId = clientRequestId,
                    inputFingerprint = inputFingerprint,
                    revisedMessageId = revisedMessageId,
                    regenerateMessageId = regenerateMessageId,
                    recoveryFromTurnId = recoveryFromTurnId,
                ).copy(inputRevision = queuedInput?.revision)
            val wakeReason =
                if (continuation == null) {
                    com.helix.core.agent.GoalWakeReason.USER_OPEN
                } else {
                    com.helix.core.agent.GoalWakeReason.FOREGROUND_CONTINUATION
                }
            val admission =
                turnEngine.admit(
                    spec = spec,
                    // Maintenance consumes a model call, but never Goal authority or activation.
                    control =
                        if (manualCompaction) {
                            control.copy(
                                mode = AgentMode.CHAT,
                                chatToolsEnabled = false,
                            )
                        } else {
                            control
                        },
                    wakeReason = wakeReason,
                    providerId = providerId,
                    modelId = admittedModelId,
                    recoveryInspection = recoveryParent != null,
                    freshGuard = {
                        val queuedStillValid =
                            queuedInput == null || queueStillConsumable(queuedInput, requireQueueHead)
                        val live = storage.sessions.resolve(sessionId)
                        val recoveryStillValid =
                            recoveryParent == null ||
                                com.helix.app.engine.AutomaticRecoveryPolicy
                                    .eligible(storage.turns.resolve(recoveryParent))
                        queuedStillValid && recoveryStillValid && live.providerId == providerId &&
                            live.modelId == session.modelId
                    },
                    resolveGoal = {
                        val effectiveGoalId =
                            if (manualCompaction) {
                                null
                            } else {
                                goalId
                                    ?: if (control.mode == AgentMode.GOAL && !text.isNullOrBlank()) {
                                        goalLifecycle
                                            .current(sessionId)
                                            ?.takeIf {
                                                it.state !in setOf("COMPLETED", "FAILED", "CANCELLED")
                                            }?.id ?: GoalSummaryQuery(storage)
                                            .forSession(sessionId)
                                            .firstOrNull {
                                                it.status.state !in setOf("COMPLETED", "FAILED", "CANCELLED") &&
                                                    storage.goalTurnBindings.sessionForGoal(it.id) == sessionId
                                            }?.id ?: GoalRunCoordinator(storage, clock, idGenerator)
                                            .create(text, emptyList(), control.goalBudgets)
                                    } else {
                                        null
                                    }
                            }
                        if (effectiveGoalId != null) goalLifecycle.bind(effectiveGoalId, sessionId)
                        effectiveGoalId
                    },
                )
            val started =
                when (admission) {
                    is com.helix.app.engine.TurnAdmissionResult.Started -> {
                        admission.turn
                    }

                    is com.helix.app.engine.TurnAdmissionResult.Deduplicated -> {
                        return admission.turnId
                    }

                    is com.helix.app.engine.TurnAdmissionResult.Blocked -> {
                        setBlocked(str(R.string.chat_blocked_session_busy))
                        return null
                    }

                    com.helix.app.engine.TurnAdmissionResult.Conflict -> {
                        setBlocked(str(R.string.turn_submit_conflict))
                        return null
                    }

                    com.helix.app.engine.TurnAdmissionResult.FreshRejected -> {
                        return null
                    }

                    com.helix.app.engine.TurnAdmissionResult.GoalRefused -> {
                        setBlocked(str(R.string.goal_continue_unavailable))
                        return null
                    }
                }
            if (manualCompaction || revisedMessageId != null) revokeGoalIntent(sessionId)
            val coordinator = started.coordinator
            val effectiveControl = started.control
            val effectiveGoalId = started.goalId
            val canAuthorizeGoal = !manualCompaction && directUserRequest && queuedInput == null
            if (canAuthorizeGoal && !text.isNullOrBlank()) {
                goalUserRequests[turnId] = listOf(GoalUserRequest(sessionId, text, providerId, control, snapshot))
            }
            if (continuousGoal && effectiveGoalId != null) {
                goalContinuation.started(
                    sessionId,
                    effectiveGoalId,
                    providerId,
                    control,
                    turnId,
                    continuation,
                    snapshot,
                )
            }
            return launchAndPublishTurn(sessionId, coordinator, providerId, retryTurnId, effectiveControl)
        }
    }

    /** The Engine owns the worker/start gate/AgentLoop lifecycle; Chat supplies only app projections. */
    private fun launchAndPublishTurn(
        sessionId: String,
        coordinator: TurnCoordinator,
        providerId: String,
        retryTurnId: String?,
        effectiveControl: RunControlConfig,
        entry: AgentLoop.Entry = AgentLoop.Entry.INITIAL,
    ): String =
        turnEngine.launchExecution(
            scope = workScope,
            loop = agentLoop,
            request =
                TurnExecutionRequest(
                    sessionId = sessionId,
                    coordinator = coordinator,
                    providerId = providerId,
                    retryTurnId = retryTurnId,
                    control = effectiveControl,
                    entry = entry,
                ),
            hooks = executionHooks(sessionId),
        )

    /** Builds the [TurnStartSpec] for a turn start, carrying the persistent submit-dedup receipt (section 34). */
    private fun turnStartSpec(
        sessionId: String,
        turnId: String,
        callId: String,
        snapshot: String,
        text: String?,
        attachmentBindings: List<MessageAttachmentRepository.Binding>,
        referenceSnapshots: List<ConversationReferenceSnapshotInput>,
        clientRequestId: String,
        inputFingerprint: String,
        revisedMessageId: String?,
        regenerateMessageId: String? = null,
        recoveryFromTurnId: String? = null,
    ): TurnStartSpec =
        TurnStartSpec(
            sessionId = sessionId,
            turnId = turnId,
            firstModelCallId = callId,
            providerSnapshot = snapshot,
            userText = text,
            attachments = attachmentBindings,
            references = referenceSnapshots,
            clientRequestId = clientRequestId,
            inputFingerprint = inputFingerprint,
            revisedMessageId = revisedMessageId,
            regenerateMessageId = regenerateMessageId,
            recoveryFromTurnId = recoveryFromTurnId,
        )

    /** E1-A execution-driver seam: durable review resolution already committed before this call. */
    private fun resolveTurnSession(requestedSessionId: String?): SessionEntity? =
        TurnSessionResolver.resolve(
            explicitSessionId = requestedSessionId,
            openSession = currentSession(),
            resolveSession = { id -> runCatching { storage.sessions.resolve(id) }.getOrNull() },
        )

    private fun applyEvent(
        event: ModelEvent,
        acc: ModelStreamState,
        turnId: String,
    ) {
        turnEngine.liveHandles.goalTime(turnId)?.checkActive()
        val update = acc.apply(event)
        if (!update.textChanged) return
        publishTurn(TurnUi(turnId, TurnState.RECEIVING_MODEL, acc.text, null, false))
    }

    private fun executionHooks(sessionId: String): TurnExecutionHooks =
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
            ): TurnTerminalProjection =
                synchronized(turnGate) {
                    var completed = false
                    try {
                        endTurnSettlement(turnId)
                        goalLifecycle.settle(turnId)
                        goalUserRequests.remove(turnId)
                        val continueDelivery =
                            sessionWorkScheduler.prepareAfterTerminal(
                                sessionId,
                                turnId,
                                outcome.state,
                            )
                        completed = true
                        TurnTerminalProjection(
                            continueDelivery = continueDelivery,
                            errorLabel = terminalLabel(outcome.state, outcome.errorCode),
                        )
                    } finally {
                        if (!completed) {
                            goalUserRequests.remove(turnId)
                            sessionWorkScheduler.disarm(sessionId)
                        }
                    }
                }

            override fun afterTerminalRelease(
                turnId: String,
                outcome: ModelStreamTerminal,
                continueDelivery: Boolean,
            ) {
                val settled = storage.turns.resolve(turnId)
                if (com.helix.app.engine.AutomaticRecoveryPolicy
                        .isInspection(settled)
                ) {
                    turnEngine.finishAutomaticRecovery(
                        requireNotNull(settled.recoveryFromTurnId),
                        "RECOVERY_INSPECTION_FINISHED",
                        if (outcome.state ==
                            TurnState.COMPLETED
                        ) {
                            null
                        } else {
                            str(R.string.automatic_recovery_unavailable)
                        },
                    )
                }
                publishTurn(
                    TurnUi(
                        turnId,
                        outcome.state,
                        null,
                        terminalLabel(outcome.state, outcome.errorCode),
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
        toolPipeline.endTurn(turnId)
        toolCalls.finishTurn(turnId)
    }

    private fun syncGoalReminderForTurn(turnId: String) {
        // Goal deletion cascades bindings and runs together. Read both in one snapshot so
        // user deletion after terminal publication cannot leave a stale binding between reads.
        var boundGoalId: String? = null
        storage.withTransaction {
            val binding = storage.goalTurnBindings.byTurn(turnId) ?: return@withTransaction
            boundGoalId = storage.goalRuns.resolve(binding.runId).goalId
        }
        val goalId = boundGoalId ?: return
        try {
            goalReminderSync(goalId)
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
        val id = openSessionId ?: return null
        // A persisted refresh can start before the user opens an ephemeral draft.
        // It must not classify that legitimate in-memory identity as an orphan.
        if (sessionDraft?.session?.id == id) return null
        return if (runCatching { storage.sessions.resolve(id) }.isSuccess) {
            id
        } else {
            // A stale lookup must not close a newer user-selected session.
            openSession.compareAndSet(id, null)
            null
        }
    }

    private fun refreshScreen() {
        refreshBackgroundTasks()
        sessionDraft?.takeIf { it.session.id == openSessionId }?.let { draft ->
            val refreshed =
                EMPTY_SCREEN.copy(
                    sessions = _sessions.value,
                    openSessionId = draft.session.id,
                    sessionTitle = draft.session.title,
                    badge = projection.badgeForProvider(draft.session.providerId, draft.session.modelId),
                    isDraft = true,
                    preparingDraft = preparingDraft,
                    directoryRef = draft.session.directoryRef,
                    pendingAttachments = draft.attachments.map { PendingAttachmentUi(it.id, it.name, it.size, true) },
                )
            _screen.update { if (openSessionId == draft.session.id) refreshed else it }
            return
        }
        refreshPersistedScreen()
        recovery.collectAutomatically()
    }

    private fun refreshPersistedScreen() {
        // Session deletion can race terminal publication. Keep existence and all
        // dependent projections in one Room snapshot, not separate check/read calls.
        storage.withTransaction {
            val sessionId = resolvableOpenSessionId()
            val turns =
                sessionId
                    ?.let { id ->
                        val superseded = storage.messages.supersededTurns(id)
                        storage.turns.listBySession(id).filterNot { it.id in superseded }
                    }.orEmpty()
            val lastTurn = turns.lastOrNull()
            // Refreshes race with targeted UI publications (for example an attachment refusal).
            // Build from the value observed by StateFlow's atomic update so a refresh can never
            // restore an older blocked/disclosure/streaming snapshot over a newer publication.
            _screen.update { current ->
                val retryTarget = projection.retryTargetFor(sessionId)
                val refreshed =
                    ChatScreenState(
                        sessions = _sessions.value,
                        openSessionId = sessionId,
                        preparingDraft = preparingDraft,
                        sessionTitle = sessionId?.let { storage.sessions.resolve(it).title }.orEmpty(),
                        directoryRef = sessionId?.let { storage.sessions.resolve(it).directoryRef },
                        workspaceRecovered = sessionId?.let(workspaceRecovery::hasNotice) == true,
                        // A null badge is authoritative for an unbound session, not a missing refresh.
                        badge = sessionId?.let { projection.badgeFor(it) },
                        messages = projection.messagesFor(sessionId, current),
                        contextUsage = ChatContextProjection.read(storage, sessionId, providerService),
                        toolTimeline = projection.toolTimelineFor(sessionId, current.toolTimeline),
                        subscriptionRecoveries =
                            subscriptionRecoveriesFor(
                                storage,
                                sessionId,
                                current.subscriptionRecoveries,
                            ),
                        activeTurn = lastTurn?.let { projection.turnUiFor(it, current.activeTurn?.streamingText) },
                        turns = turns.map { projection.turnUiFor(it, null) },
                        pendingDisclosure = current.pendingDisclosure,
                        blockedReason = current.blockedReason,
                        retryTargetTurnId = retryTarget,
                        recoveryPanels =
                            sessionId
                                ?.let { id ->
                                    recoveryPanelsFor(
                                        loadTurnRecoverySources(storage, id, includeTurnId = retryTarget),
                                        retryTarget,
                                    )
                                }.orEmpty(),
                        recoveryBusy = current.recoveryBusy,
                        pendingAttachments = stagedAttachmentsUi(),
                        shareDraftText = shareDraftText,
                        taskLedger = sessionId?.let { TaskLedgerProjection.forSession(storage, it) }.orEmpty(),
                        isFork =
                            sessionId?.let {
                                storage.messages.latestOfKind(it, SessionForkPlan.KIND, includeSuperseded = true) !=
                                    null
                            } == true,
                    )
                if (openSessionId == sessionId) refreshed else current
            }
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
                isText = true,
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
        if (_backgroundTasks.value.none { it.id == turn.id && it.state == turn.state }) {
            // A turn-state change settles goal runs and can move plans — re-read the dashboard
            // facts too, so an open Tasks screen tracks the same live state as the task list.
            // publishTurn is not suspend (it runs from the stream event path), so go through
            // the work-scope variant for the hop-off-thread read.
            refreshBackgroundTasks()
            refreshTaskDashboardsNow()
        }
        val session = storage.turns.resolve(turn.id).sessionId
        _screen.update { if (it.openSessionId == session) it.copy(activeTurn = turn) else it }
    }

    private fun setBlocked(reason: String) {
        _screen.update { it.copy(blockedReason = reason) }
    }

    private fun currentSession() = openSessionId?.let { storage.sessions.resolve(it) }

    private suspend fun providerSnapshot(
        providerId: String,
        modelId: String?,
    ): String =
        com.helix.app.provider.ProviderTurnSnapshot
            .capture(providerService, providerId, modelId)

    private companion object {
        const val TAG = "HelixChat"
        const val SUMMARY_CAP = 500
        const val KIND_TEXT = ChatHistoryBuilder.KIND_TEXT

        val EMPTY_SCREEN =
            ChatScreenState(
                sessions = emptyList(),
                openSessionId = null,
                badge = null,
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

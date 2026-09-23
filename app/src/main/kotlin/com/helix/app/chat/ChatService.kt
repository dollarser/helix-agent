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
import com.helix.app.agent.GoalTimeLimitException
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
import com.helix.app.approval.ApprovalCancelledException
import com.helix.app.chat.ChatAttachmentRetry.RetryStagedCheck
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
import com.helix.app.todo.TaskLedgerProjection
import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.AgentRuntime
import com.helix.core.agent.AttachmentBindingIntent
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val toolPipeline: ToolPipeline,
    private val clock: Clock = SystemClock(),
    private val idGenerator: () -> String,
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
    private val projectInstructionsReader: (String) -> String = { "" },
) : AgentTurnHost {
    // The unified AgentRuntime (HX2-01): every in-app turn entry drives the turn through this —
    // none reaches launchTurn directly. The container re-exposes the SAME instance as the
    // production entry point (AppContainer.agentRuntime).
    internal val agentRuntime: AgentRuntime = AppAgentRuntime(this)

    // The Plan closed loop (research doc section 4.2/4.3; HX2-05): the review state machine the
    // plan surface drives — approve is the ONLY source of a PlanExecutionBinding, and execute
    // creates the plan-bound Goal. The production port is backed by [storage] (the same
    // repository the `plan.submit` tool persists through).
    internal val planReview: PlanReviewService = PlanReviewService(StoragePlanReviewPort(storage, clock, idGenerator))

    // Observers started in init may refresh immediately on another thread.
    private val drafts = ChatDraftStore()
    private val requestAssembler =
        ChatRequestAssembler(
            storage,
            providerService,
            toolPipeline,
            attachmentStaging,
            visionSessionBinder,
            projectInstructionsReader,
        )
    private val attachmentRetry = ChatAttachmentRetry(storage, attachmentStaging)
    private val labels = ChatStatusLabels(strings)
    private val projection = ChatScreenProjection(storage, providerService, strings, labels::modelTerminalCodeRes)
    private val stagingProcessor = StagedAttachmentProcessor(storage, attachmentStaging, idGenerator, strings)
    private val workScope = scope
    private val toolCalls by lazy {
        ChatToolCalls(
            storage,
            toolPipeline,
            clock,
            idGenerator,
            profile,
            workScope,
            _screen,
            turnCancels,
            goalTimes,
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
            goalTimes,
            turnCancels,
            strings,
            ::refreshScreen,
            ::applyEvent,
            inputDelivery =
                object : TurnInputDelivery {
                    override suspend fun prepareSteering(
                        sessionId: String,
                        turnId: String,
                    ): TurnSteeringDraft? = prepareSteeringInput(sessionId, turnId)

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
            runControlStore,
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

    /**
     * Where the panel's RECONNECT / GRANT_PERMISSION buttons navigate: the provider and
     * permission repair screen. Wired by the UI layer; the service holds no NavController.
     */
    internal var recoverySettingsNavigation: (() -> Unit)? = null

    /** HXA-204 slice 2: the panel operations — each keeps its own identity and admission. */
    private val turnRecovery =
        TurnRecoveryActions(
            storage,
            workScope,
            _screen,
            { sessionId -> projection.retryTargetFor(sessionId) },
            { recoverySettingsNavigation?.invoke() },
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

    /** Explainable user-selected mode/budgets. A Turn snapshots this value before persistence. */
    val runControl: StateFlow<RunControlConfig> = runControlStore.flow

    fun setMode(mode: AgentMode) {
        require(sessionTurnAdmission.activeTurn(openSessionId.orEmpty()) == null) { "cannot switch mode during a turn" }
        runControlStore.setMode(mode)
    }

    fun setReasoning(reasoning: ReasoningEffort) {
        require(
            sessionTurnAdmission.activeTurn(openSessionId.orEmpty()) == null,
        ) { "cannot change reasoning during a turn" }
        runControlStore.setReasoning(reasoning)
    }

    fun setChatToolsEnabled(enabled: Boolean) {
        require(
            sessionTurnAdmission.activeTurn(openSessionId.orEmpty()) == null,
        ) { "cannot change tools during a turn" }
        runControlStore.setChatToolsEnabled(enabled)
    }

    fun setTurnBudgets(budgets: TurnBudgets) {
        require(
            sessionTurnAdmission.activeTurn(openSessionId.orEmpty()) == null,
        ) { "cannot change budgets during a turn" }
        runControlStore.setBudgets(budgets)
    }

    /** Serializes per-session turn admission (one active turn per session). */
    private val turnGate = Any()
    private val goalContinuation = GoalContinuationDriver(storage)
    private val inputTurnControls = java.util.concurrent.ConcurrentHashMap<String, RunControlConfig>()

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
                sessionTurnAdmission.activeTurns().map { it.turnId }
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

    private val sessionTurnAdmission = SessionTurnAdmission()

    /**
     * The per-turn live-frame channel behind [AgentTurnHost.observeTurnFrames] (HX2-01 §2c): a
     * turn's frames stream to observers regardless of the open session, so the app-layer runtime's
     * `observe` can stream a turn in a NON-open (background) session to its terminal. Its lifetime
     * equals the turn's — [TurnLiveFrames.open] at start, untracked at terminal — so the map holds
     * only live turns and cannot grow without bound.
     */
    private val turnLiveFrames = TurnLiveFrames()
    private val systemStops = java.util.concurrent.ConcurrentHashMap<String, String>()

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

    // --- HXA-036: the tool pipeline state (cards, dispatch facts, turn cancels) ---

    /** Per-turn cancel signals handed to the dispatcher (the stop button sets them). */
    private val goalTimes = java.util.concurrent.ConcurrentHashMap<String, GoalTimeBudget>()

    internal fun prepareDetachedJobBudget(
        sessionId: String,
        turnId: String,
        executionId: String,
        millis: Long,
    ): Long =
        com.helix.app.agent
            .GoalDetachedBudget(storage, clock, goalTimes::get, idGenerator)
            .prepare(sessionId, turnId, executionId, millis)

    internal fun rejectDetachedJobBudget(
        sessionId: String,
        turnId: String,
        executionId: String,
    ) = com.helix.app.agent
        .GoalDetachedBudget(storage, clock, goalTimes::get, idGenerator)
        .reject(sessionId, turnId, executionId)

    internal fun settleDetachedJobBudget(
        sessionId: String,
        turnId: String,
        executionId: String,
        terminalElapsedMs: Long?,
    ) = com.helix.app.agent
        .GoalDetachedBudget(storage, clock, goalTimes::get, idGenerator)
        .settle(sessionId, turnId, executionId, terminalElapsedMs)

    private val turnCancels = java.util.concurrent.ConcurrentHashMap<String, TurnCancelSignal>()

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
            refreshSessionsNow()
            refreshScreen()
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
        if (!drafts.open(entity)) return
        openSessionId = entity.id
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
    ): List<DraftAttachment>? {
        val attachments =
            drafts.persist(expectedSessionId, text, str(R.string.chat_attachment_button)) { row ->
                storage.withTransaction {
                    storage.sessions.create(row.id, row.title, row.providerId, row.modelId, row.createdAt)
                    storage.sessions.updateDetails(row.id, row.title, row.directoryRef)
                }
            } ?: return null
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

    fun setSessionDirectory(reference: String?) {
        reference?.let { FileScopePath.fromModelReference(it) }
        workScope.launch {
            val draft = sessionDraft
            if (draft != null) {
                drafts.directory(draft.session.id, reference)
            } else {
                val row = currentSession() ?: return@launch
                storage.sessions.updateDetails(row.id, row.title, reference)
                refreshSessionsNow()
            }
            refreshScreen()
        }
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
            storage.sessions.create(id, title, providerId, modelId, clock.now().toEpochMilli())
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
            SessionFork(storage).create(sessionId, messageId, id, branchTitle, clock.now().toEpochMilli()) {
                context.ensureActive()
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
        clearStagedAttachments()
        clearSessionSearch()
        shareDraftText = null // a draft pre-fill belongs to the session it opened for (HXA-056)
        workScope.launch { refreshScreen() }
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
        clearSessionSearch()
        shareDraftText = null
        workScope.launch { refreshScreen() }
    }

    /** Fail closed before an irreversible privacy erase; active work must be stopped first. */
    fun preparePermanentDeletion(sessionId: String) {
        synchronized(turnGate) {
            check(sessionTurnAdmission.activeTurn(sessionId) == null) { "SESSION_ACTIVE_STOP_REQUIRED" }
            revokeGoalIntent(sessionId)
        }
        if (openSessionId == sessionId) {
            openSessionId = null
            clearStagedAttachments()
            shareDraftText = null
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
            storage.sessions.create(id, str(R.string.session_shared_draft), null, null, clock.now().toEpochMilli())
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
            sessionDraft?.let {
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
                if (pendingSend != null || sessionTurnAdmission.hasActive(requestedSession)) {
                    return@synchronized
                }
                val row =
                    providerService.rows.value.firstOrNull { it.id == providerId && it.chatSelectable }
                        ?: return@synchronized
                if (modelId !in (row.backendModels.orEmpty() + row.model)) return@synchronized
                val draft = sessionDraft
                if (draft != null) {
                    drafts.model(draft.session.id, providerId, modelId)
                } else {
                    if (turnGateHolds(requestedSession)) return@synchronized
                    if (storage.sessions.resolve(requestedSession).archivedAt != null) return@synchronized
                    try {
                        storage.sessions.selectModel(requestedSession, providerId, modelId)
                    } catch (_: IllegalArgumentException) {
                        setBlocked(str(R.string.chat_blocked_provider_state_changed))
                        return@synchronized
                    }
                }
                runControlStore.setReasoning(ReasoningEffort.OFF)
                refreshScreen()
            }
        }
    }

    /** True when the session's newest turn has not terminalized (the target must be stable). */
    private fun turnGateHolds(sessionId: String): Boolean =
        storage.turns
            .listBySession(sessionId)
            .lastOrNull()
            ?.let { !TurnState.valueOf(it.state).isTerminal }
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
    fun stageAttachment(uri: String) {
        workScope.launch { stageAttachmentNow(uri) }
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
        send(ContextCompaction.COMMAND)
    }

    /** Durable composer API for persisted sessions; never writes messages or starts model work. */
    suspend fun saveComposerDraft(
        request: ChatSubmission,
        expectedRevision: Long?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            var saved = false
            storage.withTransaction {
                val previous = storage.turns.resolveByClientRequestId(request.clientRequestId)
                val existing = storage.composerDrafts.get(request.sessionId)
                // A draft cannot retroactively claim an already-used request ID with new content.
                val input = storage.sessionInputs.get(request.clientRequestId)
                val unusedOrIdentical = (previous == null && input == null) || existing?.toSubmission() == request
                val sameDraftKind = existing == null || existing.revisedMessageId == request.revisedMessageId
                if (unusedOrIdentical && sameDraftKind) {
                    saved = storage.composerDrafts.save(request.toDraftEntity(), expectedRevision)
                }
            }
            saved
        }

    /** A dispatched save survives cancellation of the screen awaiting its result. */
    fun saveComposerDraftAsync(
        request: ChatSubmission,
        expectedRevision: Long?,
    ): kotlinx.coroutines.Deferred<Boolean> = workScope.async { saveComposerDraft(request, expectedRevision) }

    suspend fun loadComposerDraft(sessionId: String): ChatSubmission? =
        withContext(Dispatchers.IO) {
            storage.composerDrafts.get(sessionId)?.toSubmission()
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

    /** Materializes a transient draft session so it exists in storage before saving composer drafts. */
    suspend fun materializeDraftSession(expectedSessionId: String): String? =
        withContext(workScope.coroutineContext) {
            if (storage.sessions.find(expectedSessionId) != null) return@withContext expectedSessionId
            if (openSessionId != expectedSessionId) return@withContext null
            val draft = sessionDraft ?: return@withContext expectedSessionId
            val id = draft.session.id
            if (id != expectedSessionId || !drafts.beginPreparation(id)) return@withContext null
            try {
                val attachments = saveSessionDraft("", id) ?: return@withContext null
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
                            0,
                            idGenerator(),
                            source.text(messageId, restored.size),
                            restored.map { it.artifactId },
                            messageId,
                        )
                    if (saved == null) require(saveComposerDraft(draft, null))
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

    fun saveRevisionText(
        request: ChatSubmission,
        text: String,
    ): kotlinx.coroutines.Deferred<ChatSubmission?> =
        workScope.async {
            submissionGate.withLock {
                val current = loadComposerDraft(request.sessionId) ?: return@withLock null
                if (current.revisedMessageId != request.revisedMessageId ||
                    current.revisedMessageId == null
                ) {
                    return@withLock null
                }
                if (current.text == text) return@withLock current
                if (text.length > MAX_MODEL_TEXT_CHARS || '\u0000' in text) return@withLock null
                val next = current.copy(revision = current.revision + 1, clientRequestId = idGenerator(), text = text)
                if (saveComposerDraft(next, current.revision)) next else null
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

    /** Legacy producer; UI integration should retain the identity and await [sendSubmission]. */
    fun send(text: String) {
        val sessionId = openSessionId ?: return
        sendSubmission(ChatSubmission(sessionId, 0, idGenerator(), text, stagedAttachments.map { it.artifactId }))
    }

    /** Service-owned admission survives caller cancellation; never reads an outcome from UI state. */
    fun sendSubmission(request: ChatSubmission): kotlinx.coroutines.Deferred<ChatSubmissionReceipt> {
        val snapshot = request.copy(attachmentIds = request.attachmentIds.toList())
        return workScope.async {
            submissionGate.withLock {
                val outcome = submissionAttempt { admitSubmission(snapshot) }
                ChatSubmissionReceipt(snapshot, outcome)
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
        val storedDraft = storage.composerDrafts.get(request.sessionId)
        if (storedDraft != null && storedDraft.toSubmission() != request) {
            return ChatSubmissionOutcome.Rejected("DRAFT_CHANGED")
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
        val saved = storage.composerDrafts.get(request.sessionId)?.toSubmission()
        val samePlainInput =
            request.attachmentIds.isEmpty() &&
                turn.inputFingerprint == TurnInputFingerprint.of(request.text, emptyList(), request.revisedMessageId)
        return if (turn.sessionId == request.sessionId && (samePlainInput || saved == request)) {
            ChatSubmissionOutcome.Accepted(turn.id)
        } else {
            ChatSubmissionOutcome.Rejected("REQUEST_ID_ALREADY_USED")
        }
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
                !sessionDraft?.attachments.isNullOrEmpty()
        return validText && hasContent
    }

    private fun requiresGoalObjective(
        sessionId: String,
        delivery: SessionInputDelivery,
    ): Boolean =
        synchronized(turnGate) {
            if (runControlStore.current.mode != AgentMode.GOAL || delivery == SessionInputDelivery.STEER) {
                false
            } else {
                !sessionTurnAdmission.hasActive(sessionId) && !goalContinuation.hasActivation(sessionId)
            }
        }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one fail-closed early return per gate condition
    private suspend fun sendNow(
        text: String,
        goalId: String? = null,
        submission: ChatSubmission? = null,
    ): ChatSubmissionOutcome {
        if (text.length > MAX_MODEL_TEXT_CHARS || text.indexOf('\u0000') >= 0) {
            return submissionBlocked(str(R.string.chat_blocked_message_invalid, MAX_MODEL_TEXT_CHARS))
        }
        val staged = stagedAttachments
        // An attachment-only send is valid (ADR-0014 §5): blank text is admitted while
        // staged attachments ride the send; blank text with nothing staged is still the
        // empty-send block of today.
        val startingGoal =
            requiresGoalObjective(
                submission?.sessionId ?: openSessionId.orEmpty(),
                submission?.delivery ?: SessionInputDelivery.QUEUE,
            )
        if (text.isBlank() && (staged.isEmpty() || startingGoal)) {
            return submissionBlocked(str(R.string.chat_blocked_message_invalid, MAX_MODEL_TEXT_CHARS))
        }
        val session = currentSession() ?: return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        val request = submission ?: ChatSubmission(session.id, 0, idGenerator(), text, staged.map { it.artifactId })
        if (session.id != request.sessionId) return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
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
        val outcome = AttachmentSendAdmission.admit(gate, text, target, strings)
        return when (outcome) {
            is AttachmentSendAdmission.Outcome.Blocked -> {
                // The staged attachments STAY pending: the user removes the problem file
                // and re-sends — a gate block is never a silent drop.
                submissionBlocked(outcome.reason)
            }

            is AttachmentSendAdmission.Outcome.Egress -> {
                applyEgressDecision(outcome.decision, staged, text, providerId, target, goalId, request)
            }
        }
    }

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
    ): ChatSubmissionOutcome {
        if (openSessionId != submission.sessionId) return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        return when (decision) {
            EgressDisclosure.Decision.Proceed -> {
                if (staged.isNotEmpty()) {
                    // Unreachable by construction; fail closed so a staged file is never
                    // silently dropped from the outgoing request.
                    return submissionBlocked(str(R.string.chat_blocked_egress_unconfirmed))
                }
                // A pure-text Proceed carries NO attachments, so it clears none: the staged
                // list is already empty (the snapshot above), and a file picked in the microsecond
                // since that snapshot is the user's for the NEXT send — a send is never a silent
                // drop. (The confirm path clears exactly the approved set, not the live list.)
                submitMessageTurn(submission, text, providerId, goalId)
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
        val session = currentSession() ?: return ChatSubmissionOutcome.Rejected("SESSION_CHANGED")
        val providerId = session.providerId ?: return ChatSubmissionOutcome.Rejected("NO_PROVIDER")
        pendingSubmission = null
        pendingGoalId = null
        pendingSend = null
        pendingEgress = null
        pendingAttachmentIds = emptyList()
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
        return confirmStagedSend(text, providerId, approvedAttachmentIds, liveTarget, goalId, request)
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
    ): ChatSubmissionOutcome {
        val staged = stagedAttachments
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
            return submitMessageTurn(submission, text, providerId, goalId)
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
        // approved attachments are consumed only on an admitted start; a refused start restores
        // the text as a draft (a send is never a silent drop).
        val outcome =
            submitMessageTurn(
                submission = submission,
                text = AttachmentContext.buildUserMessageContent(text, blocks),
                providerId = providerId,
                attachments = bindings.map { AttachmentBindingIntent(it.artifactId, it.boundSha256) },
                goalId = goalId,
            )
        if (isAcceptedInput(outcome)) {
            // Only consume attachments after the user message and its bindings are durable.
            synchronized(stagedLock) {
                stagedAttachments = stagedAttachments.filterNot { it.artifactId in approvedAttachmentIds }
            }
        } else if (openSessionId == submission.sessionId && storage.composerDrafts.get(submission.sessionId) == null) {
            // Compatibility for the old composer until it consumes typed receipts.
            shareDraftText = text
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
            if (systemReason != null) systemStops[turnId] = systemReason
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
            when (val stagedCheck = attachmentRetry.retryStagedFor(session.id, turnId)) {
                RetryStagedCheck.None -> {
                    // No bound attachments: EXACTLY today's retry path (no regression).
                }

                RetryStagedCheck.Unavailable -> {
                    setBlocked(str(R.string.chat_blocked_snapshot_recheck_failed))
                    return@launch
                }

                is RetryStagedCheck.Staged -> {
                    val gate = AttachmentSendGate.evaluate(stagedCheck.attachments, credentialScan)
                    if (gate !is AttachmentSendDecision.Ready) {
                        // Fail-closed, user-visible, NO new turn. A retry cannot re-pick
                        // files, so the block is a fixed re-verification reason (or the
                        // credential guard's reason — the matched content is never echoed).
                        val reason =
                            if (gate is AttachmentSendDecision.CredentialDetected) {
                                egressRejectedLabel(gate.reason)
                            } else {
                                str(R.string.chat_blocked_snapshot_recheck_failed)
                            }
                        setBlocked(reason)
                        return@launch
                    }
                }
            }
            submitCheckedRetry(providerId, turnId, continueResults)
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

    // --------------------------------------------------------------------------------
    // AgentTurnHost (HX2-01): the production turn path behind the core AgentRuntime contract
    // --------------------------------------------------------------------------------

    /**
     * [com.helix.core.agent.AgentRuntime.submit] starts a turn through the SAME path as every
     * in-app entry (send / confirmed egress / retry / goal start, which all [submitTurn] into
     * this) — but for an explicit session and an explicit per-turn run control: the research
     * doc's unified entry point. Idempotent by [clientRequestId]: a re-driven start carrying an id
     * it already started returns the existing turn's id, never a second turn (see [launchTurn]).
     * Returns the started turn's id, or null when the session refused it (fail-closed; the
     * adapter surfaces that as a start-blocked signal).
     */
    override suspend fun startTurn(
        sessionId: String,
        clientRequestId: String,
        text: String?,
        providerId: String,
        retryTurnId: String?,
        goalId: String?,
        attachments: List<AttachmentBindingIntent>,
        control: RunControlConfig,
        continuousGoal: Boolean,
        goalContinuation: com.helix.core.agent.GoalContinuationRequest?,
        directUserRequest: Boolean,
        revisedMessageId: String?,
    ): String? =
        launchTurn(
            text = text,
            providerId = providerId,
            retryTurnId = retryTurnId,
            goalId = goalId,
            attachmentBindings =
                attachments.map {
                    MessageAttachmentRepository.Binding(
                        artifactId = it.artifactId,
                        purpose = AttachmentPurpose.REFERENCE,
                        boundSha256 = it.boundSha256,
                    )
                },
            requestedSessionId = sessionId,
            controlOverride = control,
            clientRequestId = clientRequestId,
            continuousGoal = continuousGoal,
            continuation = goalContinuation,
            directUserRequest = directUserRequest,
            revisedMessageId = revisedMessageId,
        )

    @Suppress("LongMethod") // Durable state and cancellation signals share one turn gate.
    override suspend fun cancelTurn(turnId: String): TurnCancelOutcome =
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            val outcome =
                synchronized(turnGate) {
                    val task = storage.turns.resolve(turnId)
                    val owner = sessionTurnAdmission.activeTurn(task.sessionId)
                    val handoffOwner = goalContinuation.handoffOwner(task.sessionId)
                    val ownsHandoff = owner == null && handoffOwner == turnId
                    val ownsDelivery = owner?.turnId == turnId || ownsHandoff
                    if (ownsDelivery || (owner == null && handoffOwner == null)) {
                        goalContinuation.disarmTurn(task.sessionId, turnId)
                    }
                    goalUserRequests.remove(turnId)
                    val active = sessionTurnAdmission.activeTurn(task.sessionId)?.takeIf { it.turnId == turnId }
                    lateinit var result: TurnCancelOutcome
                    storage.withTransaction {
                        val current = storage.turns.resolve(turnId)
                        val phase = TurnState.valueOf(current.state)
                        result =
                            when {
                                phase.isTerminal -> {
                                    if (ownsDelivery) {
                                        storage.sessionInputs.parkSessionInputs(
                                            task.sessionId,
                                            "USER_STOP",
                                            clock.now().toEpochMilli(),
                                        )
                                        goalContinuation.disarm(task.sessionId)
                                    }
                                    TurnCancelOutcome.AlreadyTerminal(phase)
                                }

                                active != null -> {
                                    storage.sessionInputs.parkSessionInputs(
                                        task.sessionId,
                                        "USER_STOP",
                                        clock.now().toEpochMilli(),
                                    )
                                    goalContinuation.disarm(task.sessionId)
                                    if (phase != TurnState.CANCELLING) {
                                        storage.turns.updateState(
                                            current,
                                            TurnState.CANCELLING,
                                            current.stepCount,
                                            null,
                                            null,
                                        )
                                    }
                                    TurnCancelOutcome.StoppedLive
                                }

                                else -> {
                                    check(phase == TurnState.INTERRUPTED) {
                                        "Only an interrupted Turn may be discarded"
                                    }
                                    storage.sessionInputs.parkSessionInputs(
                                        task.sessionId,
                                        "USER_STOP",
                                        clock.now().toEpochMilli(),
                                    )
                                    goalContinuation.disarm(task.sessionId)
                                    // Only a recovered INTERRUPTED turn admits direct discard.
                                    storage.turns.updateState(
                                        current,
                                        TurnState.CANCELLED,
                                        current.stepCount,
                                        clock.now().toEpochMilli(),
                                        null,
                                    )
                                    GoalRunSettlement(storage, clock, idGenerator).settle(turnId)
                                    TurnCancelOutcome.DiscardedParked
                                }
                            }
                    }
                    if (result == TurnCancelOutcome.StoppedLive) {
                        // The cancellation intent is committed before signalling any in-flight work.
                        publishTurn(TurnUi(turnId, TurnState.CANCELLING, null, null, false))
                        turnCancels[turnId]?.cancel()
                        toolCalls.cancelPendingApproval(turnId)
                        active?.job?.cancel()
                    }
                    result
                }
            if (outcome == TurnCancelOutcome.DiscardedParked) {
                endTurnSettlement(turnId)
                turnLiveFrames.emit(
                    turnId,
                    TurnUi(turnId, TurnState.CANCELLED, null, terminalLabel(TurnState.CANCELLED, null), false),
                )
            }
            // Read current facts rather than publishing a stale CANCELLING over a fast terminal.
            refreshScreen()
            refreshBackgroundTasks()
            syncGoalReminderForTurn(turnId)
            outcome
        }

    /**
     * The turn's LIVE frame stream (HX2-01 §2c), independent of the open session: [TurnLiveFrames]
     * holds the frame channel for every live turn. A turn that is not live (not yet started, or
     * already ended) yields an empty flow, and the adapter projects the turn's persisted state.
     */
    override fun observeTurnFrames(turnId: String): Flow<TurnUi> = turnLiveFrames.forTurn(turnId)

    /** The turn's persisted phase; null when the id addresses no turn row. */
    override fun persistedPhase(turnId: String): TurnState? =
        runCatching { storage.turns.resolve(turnId) }.getOrNull()?.let { TurnState.valueOf(it.state) }

    /**
     * The turn's terminal assistant text (its last assistant row) or null — the adapter uses it
     * so the terminal frame carries the turn's content.
     */
    override fun persistedAssistantText(turnId: String): String? {
        val turn = runCatching { storage.turns.resolve(turnId) }.getOrNull() ?: return null
        val assistant =
            storage.messages
                .listBySession(turn.sessionId)
                .lastOrNull { it.turnId == turnId && it.role == com.helix.core.model.ModelRole.ASSISTANT.name }
        return assistant?.let { storage.messages.readContent(it) }?.takeIf { it.isNotBlank() }
    }

    // --------------------------------------------------------------------------------
    // Turn execution (service-owned; the UI only observes)
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
        goalId: String? = null,
        clientRequestId: String? = null,
        isBudgetContinuation: Boolean = false,
        expectedSessionId: String? = null,
        revisedMessageId: String? = null,
    ): Boolean {
        val session = currentSession() ?: return false
        if (expectedSessionId != null && session.id != expectedSessionId) return false
        val currentControl = runControlStore.current
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
                    // A caller that supplies a stable [clientRequestId] (the confirmed-egress
                    // re-drive) dedups to the turn it already started; every other entry point is a
                    // fresh intent and gets a fresh id.
                    clientRequestId = clientRequestId ?: idGenerator(),
                    revisedMessageId = revisedMessageId,
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
    ): ChatSubmissionOutcome {
        if (submission.revisedMessageId == null && goalId == null) {
            return acceptSessionInput(submission, providerId, attachments)
        }
        val started =
            submitTurn(
                text,
                providerId,
                attachments = attachments,
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
                if (text.isBlank() && input.attachments.isEmpty()) return@withLock false
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
    ): ChatSubmissionOutcome {
        val accepted = persistSessionInput(request, providerId, attachments)
        if (accepted is SessionInputAcceptResult.Rejected) return ChatSubmissionOutcome.Rejected(accepted.reason)
        val input = (accepted as SessionInputAcceptResult.Accepted).record
        synchronized(turnGate) {
            val interruptedOwner =
                !sessionTurnAdmission.hasActive(input.sessionId) &&
                    storage.turns.listBySession(input.sessionId).any { !TurnState.valueOf(it.state).isTerminal }
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
                val noActiveTurn = !sessionTurnAdmission.hasActive(input.sessionId)
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
    ): SessionInputAcceptResult {
        val session = storage.sessions.resolve(request.sessionId)
        val facts = providerSnapshot(providerId, session.modelId)
        val modelId = session.modelId ?: providerService.storedConfig(providerId).model
        val selected = runControlStore.current
        return synchronized(turnGate) {
            val active = sessionTurnAdmission.activeTurn(request.sessionId)
            val stopped = active?.let { storage.turns.resolve(it.turnId).state == TurnState.CANCELLING.name } == true
            if (stopped) return@synchronized SessionInputAcceptResult.Rejected("TURN_CANCELLING")
            val control =
                when {
                    request.delivery == SessionInputDelivery.STEER -> {
                        request.expectedTurnId?.let(inputTurnControls::get)
                            ?: return@synchronized SessionInputAcceptResult.Rejected("STEER_TARGET_NOT_LIVE")
                    }

                    selected.mode == AgentMode.GOAL &&
                        (active != null || goalContinuation.hasActivation(request.sessionId)) -> {
                        selected.copy(mode = AgentMode.ACT)
                    }

                    else -> {
                        selected
                    }
                }
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
                ),
            )
        }
    }

    private fun queueStillConsumable(
        input: SessionInputRecord,
        requireHead: Boolean,
    ): Boolean {
        val current = storage.sessionInputs.get(input.inputId) ?: return false
        val headMatches = !requireHead || storage.sessionInputs.headQueue(input.sessionId)?.inputId == input.inputId
        val unsettled = storage.turns.listBySession(input.sessionId).any { !TurnState.valueOf(it.state).isTerminal }
        val unchangedPending = current.state == SessionInputState.PENDING && current.revision == input.revision
        return unchangedPending && headMatches && !unsettled
    }

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
    ): String? {
        val eligible =
            synchronized(turnGate) {
                !sessionTurnAdmission.hasActive(input.sessionId) && queueStillConsumable(input, requireHead)
            }
        if (!eligible) return null
        return validatedInput(input)?.let { startValidatedInput(input, it, requireHead) }
    }

    private suspend fun startValidatedInput(
        input: SessionInputRecord,
        prepared: Pair<String, List<MessageAttachmentRepository.Binding>>,
        requireHead: Boolean,
    ): String? {
        val turnId =
            launchTurn(
                prepared.first,
                input.configuration.providerId,
                attachmentBindings = prepared.second,
                requestedSessionId = input.sessionId,
                controlOverride = inputControl(input),
                clientRequestId = input.inputId,
                continuousGoal = input.configuration.mode == AgentMode.GOAL.name,
                queuedInput = input,
                requireQueueHead = requireHead,
            )
        if (turnId == null) {
            synchronized(turnGate) {
                val current = storage.sessionInputs.get(input.inputId)
                val idle =
                    !sessionTurnAdmission.hasActive(input.sessionId) &&
                        storage.turns.listBySession(input.sessionId).all { TurnState.valueOf(it.state).isTerminal }
                val pending = current?.state == SessionInputState.PENDING
                val unchanged = current?.revision == input.revision
                if (pending && unchanged && idle) {
                    storage.sessionInputs.markNeedsAttention(
                        input.inputId,
                        input.revision,
                        "INPUT_ADMISSION_FAILED",
                        clock.now().toEpochMilli(),
                    )
                }
            }
        }
        return turnId
    }

    private fun inputControl(input: SessionInputRecord): RunControlConfig =
        if (input.delivery == SessionInputDelivery.STEER) {
            input.expectedTurnId?.let(inputTurnControls::get)
                ?: runControlStore.current.copy(mode = AgentMode.valueOf(input.configuration.mode))
        } else {
            runControlStore.current.copy(mode = AgentMode.valueOf(input.configuration.mode))
        }

    @Suppress("TooGenericExceptionCaught") // Preserve the durable input and surface its failed revalidation.
    private suspend fun validatedInput(
        input: SessionInputRecord,
    ): Pair<String, List<MessageAttachmentRepository.Binding>>? =
        try {
            val session = storage.sessions.resolve(input.sessionId)
            val provider = input.configuration.providerId
            check(providerService.chatSelectable(provider) && providerService.isCleartextPermitted(provider))
            val facts = providerSnapshot(provider, session.modelId)
            val control = inputControl(input)
            if (input.delivery == SessionInputDelivery.STEER) {
                val originalCall = storage.modelCalls.listByTurn(requireNotNull(input.expectedTurnId)).firstOrNull()
                check(originalCall?.providerSnapshot == facts) {
                    "INPUT_CONFIGURATION_CHANGED"
                }
            }
            val valid =
                session.providerId == provider &&
                    (session.modelId ?: providerService.storedConfig(provider).model) == input.configuration.modelId &&
                    SessionInputBinding.matchesConfiguration(
                        input,
                        facts,
                        control,
                        if (input.delivery == SessionInputDelivery.STEER) {
                            control.mode.name
                        } else {
                            runControlStore.current.mode.name
                        },
                    )
            check(valid) { "INPUT_CONFIGURATION_CHANGED" }
            val hasImages =
                input.attachments.any {
                    storage.artifacts.resolve(it.artifactId).mediaType in
                        com.helix.core.model.VisionLimits.NORMALIZED_MEDIA_TYPES
                }
            check(!hasImages || providerService.capabilitiesFor(provider, session.modelId)?.vision == true)
            SessionInputAttachments(storage, attachmentStaging, credentialScan).materialize(input)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            synchronized(turnGate) {
                storage.sessionInputs.markNeedsAttention(
                    input.inputId,
                    input.revision,
                    "INPUT_REVALIDATION_FAILED",
                    clock.now().toEpochMilli(),
                )
            }
            Log.w(TAG, "Queued input revalidation failed", error)
            null
        }

    private suspend fun prepareSteeringInput(
        sessionId: String,
        turnId: String,
    ): TurnSteeringDraft? {
        val input =
            storage.sessionInputs.headSteer(sessionId, turnId)?.takeIf { it.state == SessionInputState.PENDING }
                ?: return null
        return validatedInput(input)?.let { (content, bindings) -> TurnSteeringDraft(input, content, bindings) }
    }

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
        val control = inputTurnControls[turnId] ?: return
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
        workScope.launch {
            submissionGate.withLock {
                var releaseHandoff = handoffTurnId
                try {
                    val input =
                        synchronized(turnGate) {
                            if (sessionTurnAdmission.hasActive(sessionId)) return@withLock
                            releaseHandoff = goalContinuation.handoffOwner(sessionId) ?: releaseHandoff
                            storage.sessionInputs.headQueue(sessionId)
                        }
                    if (input != null) {
                        if (input.state == SessionInputState.PENDING) consumeQueueInput(input)
                    } else {
                        val next =
                            synchronized(turnGate) {
                                if (storage.sessionInputs.listPending(sessionId).isEmpty()) {
                                    goalContinuation.resumeEligible(sessionId)
                                } else {
                                    null
                                }
                            }
                        if (next != null) {
                            if (releaseHandoff == null) releaseHandoff = next.goalContinuation?.previousTurnId
                            agentRuntime.submit(next)
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    synchronized(turnGate) {
                        storage.sessionInputs.parkSessionInputs(
                            sessionId,
                            "INPUT_DELIVERY_FAILED",
                            clock.now().toEpochMilli(),
                        )
                        goalContinuation.disarm(sessionId)
                    }
                    Log.e(TAG, "Session input drain failed", error)
                } finally {
                    releaseHandoff?.let { synchronized(turnGate) { goalContinuation.finishHandoff(sessionId, it) } }
                    refreshScreen()
                    refreshBackgroundTasks()
                }
            }
        }
    }

    // one fail-closed return per guard (session, snapshot, turn gate); one branch per guard plus
    // the idempotency dedup check (HX2-01 §2e)
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // One atomic admission/activation transaction.
    private suspend fun launchTurn(
        text: String?,
        providerId: String,
        retryTurnId: String? = null,
        attachmentBindings: List<MessageAttachmentRepository.Binding> = emptyList(),
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
    ): String? {
        // The unified AgentRuntime (HX2-01) starts turns for an explicit session with an explicit
        // per-turn control; the in-session send path passes neither and falls back to the open
        // session + the current run-control (behavior unchanged).
        val session = resolveTurnSession(requestedSessionId) ?: return null
        val sessionId = session.id
        // Snapshot before creating the durable Turn: later UI/profile changes cannot alter this
        // Turn's mode, tool table, dispatcher mode, or limits.
        val control = controlOverride ?: runControlStore.current
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
        val inputFingerprint = TurnInputFingerprint.of(text, attachmentBindings, revisedMessageId)
        synchronized(turnGate) {
            if (queuedInput != null &&
                !SessionInputBinding.matchesConfiguration(
                    queuedInput,
                    snapshot,
                    control,
                    runControlStore.current.mode.name,
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
            // Persistent submit-dedup (research doc section 34): a re-drive with the same session +
            // input returns the started turn; a diverged session or input is a conflict (refused).
            when (val dedup = resolveSubmitDedup(clientRequestId, sessionId, inputFingerprint)) {
                is TurnDedupDecision.Dedup -> return dedup.turnId
                TurnDedupDecision.Conflict -> return null
                TurnDedupDecision.Fresh -> Unit
            }
            // Per-session admission: refuse only when THIS session already has an in-flight turn —
            // a turn in another session must never make this send vanish.
            if (sessionTurnAdmission.hasActive(sessionId)) {
                setBlocked(str(R.string.chat_blocked_session_busy))
                return null
            }
            if (queuedInput != null && !queueStillConsumable(queuedInput, requireQueueHead)) return null
            val isGoalOrRetry =
                goalId != null || continuousGoal || control.mode == AgentMode.GOAL || retryTurnId != null
            if (revisedMessageId != null && isGoalOrRetry) {
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
                    sessionId,
                    turnId,
                    callId,
                    snapshot,
                    text,
                    attachmentBindings,
                    clientRequestId,
                    inputFingerprint,
                    revisedMessageId,
                ).copy(inputRevision = queuedInput?.revision)
            var preparedGoalId: String? = null
            var preparedTurn: Pair<TurnCoordinator, RunControlConfig>? = null
            storage.withTransaction {
                if (queuedInput != null && !queueStillConsumable(queuedInput, requireQueueHead)) return@withTransaction
                val effectiveGoalId =
                    goalId ?: if (control.mode == AgentMode.GOAL && !text.isNullOrBlank()) {
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
                if (effectiveGoalId != null) goalLifecycle.bind(effectiveGoalId, sessionId)
                val wakeReason =
                    if (continuation == null) {
                        com.helix.core.agent.GoalWakeReason.USER_OPEN
                    } else {
                        com.helix.core.agent.GoalWakeReason.FOREGROUND_CONTINUATION
                    }
                preparedGoalId = effectiveGoalId
                preparedTurn = startCoordinatorForTurn(spec, effectiveGoalId, control, wakeReason)
            }
            val effectiveGoalId = preparedGoalId
            val started = preparedTurn
            if (started == null) {
                setBlocked(str(R.string.goal_continue_unavailable))
                return null
            }
            if (revisedMessageId != null) revokeGoalIntent(sessionId)
            val (coordinator, effectiveControl) = started
            if (directUserRequest && queuedInput == null && !text.isNullOrBlank()) {
                goalUserRequests[turnId] = listOf(GoalUserRequest(sessionId, text, providerId, control, snapshot))
            }
            inputTurnControls[turnId] = effectiveControl
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
            return launchAndPublishTurn(sessionId, turnId, coordinator, providerId, retryTurnId, effectiveControl)
        }
    }

    /**
     * Launches the turn's worker behind a start gate, registers its admission, opens its live-frame
     * channel and publishes the initial UI — cancelling the job if publication fails before the gate
     * completes. Split from [launchTurn] (which keeps the turn-start owner/sequence); this unit owns
     * the worker / register / publish / cancel handshake.
     */
    private fun launchAndPublishTurn(
        sessionId: String,
        turnId: String,
        coordinator: TurnCoordinator,
        providerId: String,
        retryTurnId: String?,
        effectiveControl: RunControlConfig,
    ): String {
        // The worker waits behind this gate until its active-turn entry and initial UI are
        // published. Without the gate, a fast scheduler can begin streaming before register;
        // stop() in that window cannot find the job and silently fails to cancel the turn.
        val startGate = CompletableDeferred<Unit>()
        val job =
            workScope.launch(start = CoroutineStart.UNDISPATCHED) {
                runTurn(sessionId, coordinator, providerId, retryTurnId, startGate, effectiveControl)
            }
        var published = false
        try {
            sessionTurnAdmission.register(sessionId, job, turnId)
            // The turn is live now — open its per-turn live-frame channel so its frames stream to
            // [AgentTurnHost.observeTurnFrames] observers regardless of the open session (HX2-01 §2c).
            turnLiveFrames.open(turnId)
            if (openSessionId == sessionId) {
                refreshScreen() // publish the committed user message before the model may emit or wait
                publishTurn(TurnUi(turnId, TurnState.WAITING_MODEL, null, null, false))
            }
            published = true
        } finally {
            if (!published) job.cancel()
            startGate.complete(Unit)
        }
        return turnId
    }

    /**
     * Resolves the persistent submit-dedup receipt for [clientRequestId] (research doc section 34;
     * HX2-01 §2e): the receipt is the turn row itself (clientRequestId + inputFingerprint),
     * committed WITH the turn, so a restart can no longer let the same id re-start a second turn.
     * A re-drive with the SAME session + input is a [TurnDedupDecision.Dedup] to the started turn;
     * a DIFFERENT session or input is a [TurnDedupDecision.Conflict] — which surfaces the safe
     * blocked state (fail-closed) so a second turn is never started. The synchronous Room read is
     * NOT a suspend point, so the caller invokes this under the turn gate.
     */
    private fun resolveSubmitDedup(
        clientRequestId: String,
        sessionId: String,
        inputFingerprint: String,
    ): TurnDedupDecision {
        val decision =
            TurnDedup.decide(
                storage.turns.resolveByClientRequestId(clientRequestId),
                sessionId,
                inputFingerprint,
            )
        if (decision is TurnDedupDecision.Conflict) {
            Log.w(TAG, "submit-dedup conflict for clientRequestId $clientRequestId (session/input diverged)")
            setBlocked(str(R.string.turn_submit_conflict))
        }
        return decision
    }

    /** Builds the [TurnStartSpec] for a turn start, carrying the persistent submit-dedup receipt (section 34). */
    private fun turnStartSpec(
        sessionId: String,
        turnId: String,
        callId: String,
        snapshot: String,
        text: String?,
        attachmentBindings: List<MessageAttachmentRepository.Binding>,
        clientRequestId: String,
        inputFingerprint: String,
        revisedMessageId: String?,
    ): TurnStartSpec =
        TurnStartSpec(
            sessionId,
            turnId,
            callId,
            snapshot,
            text,
            attachmentBindings,
            clientRequestId = clientRequestId,
            inputFingerprint = inputFingerprint,
            revisedMessageId = revisedMessageId,
        )

    /**
     * Starts the turn's coordinator under the caller's gate: a goal start goes through the
     * [GoalRunCoordinator] (its budgets applied to the control); a plain start goes through
     * [TurnCoordinator]. Returns null when a goal wake was REFUSED (the goal reducer declined) —
     * the caller then surfaces the blocked state and does not start a turn.
     */
    private fun startCoordinatorForTurn(
        spec: TurnStartSpec,
        goalId: String?,
        control: RunControlConfig,
        wakeReason: com.helix.core.agent.GoalWakeReason,
    ): Pair<TurnCoordinator, RunControlConfig>? {
        val goalStart =
            goalId?.let {
                GoalRunCoordinator(storage, clock, idGenerator).start(
                    GoalTurnStart(it, wakeReason, spec, control.budgets),
                )
            }
        if (goalId != null && goalStart == null) return null
        val coordinator = goalStart?.coordinator ?: TurnCoordinator.start(storage, clock, idGenerator, spec)
        val effectiveControl =
            goalStart?.let { control.copy(mode = AgentMode.GOAL, budgets = it.budgets) } ?: control
        return coordinator to effectiveControl
    }

    /**
     * Resolves the session for a turn start (HX2-01): an explicit [requestedSessionId] from the
     * unified AgentRuntime must run in exactly that session — when it cannot be resolved the start
     * is refused (fail-closed), never substituted with the open session (which would run the
     * request in a different session than its caller addressed). Only a start with no explicit
     * session falls back to the open session. Null = refuse (no turn is started).
     */
    private fun resolveTurnSession(requestedSessionId: String?): SessionEntity? =
        TurnSessionResolver.resolve(
            explicitSessionId = requestedSessionId,
            openSession = currentSession(),
            resolveSession = { id -> runCatching { storage.sessions.resolve(id) }.getOrNull() },
        )

    // The boundary catch is deliberately broad: ANY unexpected failure at the
    // model boundary (guard rejects, corrupt rows, a vanished provider) must
    // still terminalize the turn with a safe label — a narrow catch would
    // leave the UI stuck on "sending" (doc 02 section 13).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runTurn(
        sessionId: String,
        coordinator: TurnCoordinator,
        providerId: String,
        retryTurnId: String?,
        startGate: CompletableDeferred<Unit>,
        control: RunControlConfig,
    ) {
        val turnId = coordinator.id
        try {
            startGate.await()
            val decision =
                agentLoop.runWithGoalTime(coordinator.id) {
                    agentLoop.runToolLoop(sessionId, coordinator, providerId, retryTurnId, control)
                }
            terminalize(sessionId, coordinator, decision)
        } catch (e: com.helix.app.agent.ContextCapacityException) {
            terminalize(sessionId, coordinator, ModelStreamTerminal(TurnState.FAILED, e.code))
        } catch (e: GoalTimeLimitException) {
            turnCancels[turnId]?.cancel()
            terminalize(sessionId, coordinator, ModelStreamTerminal(TurnState.FAILED, e.code))
        } catch (e: CancellationException) {
            terminalize(sessionId, coordinator, ModelStreamTerminal(TurnState.CANCELLED, null))
            throw e
        } catch (e: ApprovalCancelledException) {
            // The user stopped the turn while the approval card was pending: the turn
            // terminalizes as CANCELLED; the approval record stays PENDING (no decision
            // was made) and expires with its window. The job completes normally — the
            // cancellation was the user's own action, not a failure. (The exception
            // carries only the approval id — safe to log as metadata.)
            Log.i(TAG, "turn $turnId stopped while awaiting approval: ${e.message}")
            terminalize(sessionId, coordinator, ModelStreamTerminal(TurnState.CANCELLED, null))
        } catch (e: Exception) {
            // An unexpected boundary failure (a guard reject, corrupt rows, a
            // provider row deleted mid-turn): the turn STILL reaches a
            // terminal state with a safe label — never a stuck "sending" UI
            // (doc 02 section 13: raw messages are never shown).
            Log.e(TAG, "turn $turnId failed at the model boundary", e)
            terminalize(
                sessionId,
                coordinator,
                ModelStreamTerminal(TurnState.FAILED, ErrorCode.INTERNAL.name),
            )
        }
    }

    private fun applyEvent(
        event: ModelEvent,
        acc: ModelStreamState,
        turnId: String,
    ) {
        goalTimes[turnId]?.checkActive()
        val update = acc.apply(event)
        if (!update.textChanged) return
        publishTurn(TurnUi(turnId, TurnState.RECEIVING_MODEL, acc.text, null, false))
    }

    /**
     * Persists the turn terminal + the assistant content row (when any) + the
     * model-call terminal, then refreshes the UI state. Runs from the stream
     * completion, the stop path or the error path — always exactly once.
     */
    private fun terminalize(
        sessionId: String,
        coordinator: TurnCoordinator,
        outcome: ModelStreamTerminal,
    ) {
        val turnId = coordinator.id
        var settledOutcome = systemStops.remove(turnId)?.let { outcome.copy(errorCode = it) } ?: outcome
        // Release the old owner and reserve the next delivery in the same gate as Stop/admission.
        val continueDelivery =
            synchronized(turnGate) {
                coordinator.terminalize(settledOutcome)
                // The loop may have committed its final boundary before GoalTimeBudget released
                // its time reservation. Reconcile again after that lease has settled.
                GoalRunSettlement(storage, clock, idGenerator).settle(turnId)
                val durable = storage.turns.resolve(turnId)
                settledOutcome = ModelStreamTerminal(TurnState.valueOf(durable.state), durable.errorCode)
                endTurnSettlement(turnId)
                goalLifecycle.settle(turnId)
                goalUserRequests.remove(turnId)
                inputTurnControls.remove(turnId)
                sessionTurnAdmission.complete(sessionId, turnId)
                if (settledOutcome.state != TurnState.COMPLETED) {
                    storage.sessionInputs.parkSessionInputs(sessionId, "TURN_NOT_COMPLETED", clock.now().toEpochMilli())
                    goalContinuation.disarm(sessionId)
                    false
                } else {
                    storage.sessionInputs
                        .listPending(sessionId)
                        .filter {
                            it.delivery == SessionInputDelivery.STEER && it.expectedTurnId == turnId &&
                                it.state == SessionInputState.PENDING
                        }.forEach {
                            storage.sessionInputs.markNeedsAttention(
                                it.inputId,
                                it.revision,
                                "STEER_TARGET_FINISHED",
                                clock.now().toEpochMilli(),
                            )
                        }
                    val queue = storage.sessionInputs.headQueue(sessionId)
                    if (queue?.state == SessionInputState.PENDING) {
                        goalContinuation.reserveUserHandoff(sessionId, turnId)
                    } else if (storage.sessionInputs.listPending(sessionId).isEmpty()) {
                        goalContinuation.reserveEligibleHandoff(sessionId, turnId)
                    }
                    true
                }
            }
        dispatchTerminalNotifications(sessionId, turnId, settledOutcome, continueDelivery)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dispatchTerminalNotifications(
        sessionId: String,
        turnId: String,
        settledOutcome: ModelStreamTerminal,
        continueDelivery: Boolean,
    ) {
        try {
            val label = terminalLabel(settledOutcome.state, settledOutcome.errorCode)
            label?.let {
                publishTurn(
                    TurnUi(turnId, settledOutcome.state, null, it, settledOutcome.state == TurnState.FAILED),
                )
            }
            if (label == null) {
                turnLiveFrames.emit(
                    turnId,
                    TurnUi(turnId, settledOutcome.state, null, null, settledOutcome.state == TurnState.FAILED),
                )
            }
            refreshScreen()
            syncGoalReminderForTurn(turnId)
            if (continueDelivery) requestSessionDrain(sessionId, turnId)
        } catch (e: Exception) {
            // R8: post-terminal UI projection, reminder sync or queue drain failure must never
            // escape to runTurn's outer catch to overwrite the already durable terminal state.
            Log.e(TAG, "post-terminal notification error for turn $turnId", e)
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
        turnCancels.remove(turnId)
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
    private fun resolvableOpenSessionId(): String? {
        val id = openSessionId ?: return null
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
        // Feed this turn's live-frame channel first (HX2-01 §2c): observers of a NON-open session's
        // turn get frames here, since the screen below only reflects the one session on screen.
        turnLiveFrames.emit(turn.id, turn)
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
    ): String {
        val c = providerService.storedConfig(providerId)
        // The snapshot is an informational, model-call-bound JSON column. The
        // three values are user-supplied (displayName especially may hold a
        // quote), so escape before interpolation — never build JSON by raw
        // string concatenation of untrusted text.
        // HXA-055: the TURN's capability facts travel with the snapshot — the exact
        // capability state (probe or manual declaration, incl. vision) that this model
        // call ran under; an unparseable stored snapshot contributes nothing (fail
        // closed: the column is informational, the send gate is the authority).
        val capabilitiesJson =
            runCatching {
                ProviderCapabilities.parse(c.capabilitySnapshot).let { caps ->
                    ",\"capabilities\":${ProviderCapabilities.toJsonString(caps)}"
                }
            }.getOrDefault("")
        return buildString {
            append("{\"displayName\":\"")
            append(jsonEscape(c.displayName))
            append("\",\"endpoint\":\"")
            append(jsonEscape(c.endpoint.full))
            append("\",\"model\":\"")
            append(jsonEscape(modelId ?: c.model))
            append("\"")
            append(capabilitiesJson)
            append("}")
        }
    }

    private fun jsonEscape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

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

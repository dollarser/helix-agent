package com.helix.app.chat

import android.util.Log
import com.helix.app.R
import com.helix.app.agent.AgentLoop
import com.helix.app.agent.BufferedModelToolCall
import com.helix.app.agent.ChatContextProjection
import com.helix.app.agent.ChatContextRequest
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
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
import com.helix.app.agent.TurnMessageDraft
import com.helix.app.agent.TurnStartSpec
import com.helix.app.agent.TurnToolExecutor
import com.helix.app.approval.ApprovalCancelledException
import com.helix.app.chat.ChatAttachmentRetry.RetryStagedCheck
import com.helix.app.internal.InMemoryLineStore
import com.helix.app.plan.PlanReviewService
import com.helix.app.plan.StoragePlanReviewPort
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.PersistedRunControlStore
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.runcontrol.RunControlStore
import com.helix.app.todo.TaskLedgerProjection
import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.AgentRuntime
import com.helix.core.agent.AttachmentBindingIntent
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.AttachmentPurpose
import com.helix.core.model.Clock
import com.helix.core.model.ErrorCode
import com.helix.core.model.GoalId
import com.helix.core.model.PlanExecutionBinding
import com.helix.core.model.ProviderId
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionId
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnId
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.repository.MessageAttachmentRepository
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
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.ToolDispatchOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.jvm.Volatile

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
    private val lanScopes: () -> Set<com.helix.core.policy.NetworkOriginScope> = { emptySet() },
    private val subscriptionResultRecovery: (
        String,
        String,
        Boolean,
    ) -> com.helix.app.provider.SubscriptionRecoveredOutput? =
        { _, _, _ -> null },
    private val subscriptionRecovery: (String, String, Boolean) -> com.helix.app.provider.SubscriptionRecoveryStatus =
        { _, _, _ -> com.helix.app.provider.SubscriptionRecoveryStatus.UNKNOWN },
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
    private val _backgroundTasks = MutableStateFlow<List<BackgroundTaskUi>>(emptyList())
    val backgroundTasks: StateFlow<List<BackgroundTaskUi>> = _backgroundTasks
    private val _screen = MutableStateFlow(EMPTY_SCREEN)

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

    fun setReasoning(reasoning: com.helix.core.model.ReasoningEffort) {
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

    fun setTurnBudgets(budgets: com.helix.core.model.TurnBudgets) {
        require(
            sessionTurnAdmission.activeTurn(openSessionId.orEmpty()) == null,
        ) { "cannot change budgets during a turn" }
        runControlStore.setBudgets(budgets)
    }

    /** Serializes per-session turn admission (one active turn per session). */
    private val turnGate = Any()
    private val sessionTurnAdmission = SessionTurnAdmission()

    // Written on the main thread (open/close/cancel), read from the work-scope IO pool
    // (sendNow): @Volatile so a fresh open is never invisible to a racing send (a lost
    // write would silently drop the user's message with no UI feedback).
    @Volatile
    private var openSessionId: String? = null

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
            com.helix.core.storage.entity.SessionEntity(
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

    private fun saveSessionDraft(text: String): List<DraftAttachment>? {
        val attachments =
            drafts.persist(openSessionId, text, str(R.string.chat_attachment_button)) { row ->
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
        drafts.clear()
        openSessionId = id
        clearStagedAttachments()
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
        drafts.clear()
        openSessionId = null
        clearStagedAttachments()
        shareDraftText = null
        workScope.launch { refreshScreen() }
    }

    /** Fail closed before an irreversible privacy erase; active work must be stopped first. */
    fun preparePermanentDeletion(sessionId: String) {
        check(sessionTurnAdmission.activeTurn(sessionId) == null) { "SESSION_ACTIVE_STOP_REQUIRED" }
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
                runControlStore.setReasoning(com.helix.core.model.ReasoningEffort.OFF)
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
    private suspend fun stageAttachmentNow(uri: String) {
        val sessionId = openSessionId
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
        budgets: com.helix.core.model.GoalBudgets,
    ) = goals.createGoal(objective, criteria, budgets)

    internal suspend fun goalSummaries() = goals.goalSummaries()

    /** Cross-session goal list for the Tasks dashboard (doc section 13). */
    internal suspend fun goalSummariesAll() = goals.goalSummariesAll()

    internal suspend fun setGoalReminder(
        goalId: String,
        delayMillis: Long?,
    ) = goals.setGoalReminder(goalId, delayMillis)

    internal suspend fun recheckGoalBlocker(goalId: String) = goals.recheckGoalBlocker(goalId)

    internal suspend fun updateGoalBudgets(
        goalId: String,
        budgets: com.helix.core.model.GoalBudgets,
    ) = goals.updateGoalBudgets(goalId, budgets)

    // --------------------------------------------------------------------------------
    // Plan review (research doc section 4.2/4.3; HX2-05): the user's decisions on a plan
    // submitted through `plan.submit` — approve / revise / cancel / execute.
    // --------------------------------------------------------------------------------

    internal suspend fun reviewPlan(planId: String) = withContext(Dispatchers.IO) { planReview.review(planId) }

    /** Cross-session plan rows for the Tasks dashboard review queue (doc section 12/13). */
    internal suspend fun planRows(): List<PlanRowUi> = withContext(Dispatchers.IO) { PlanRowQuery(storage).read() }

    /** The ONLY path to a [PlanExecutionBinding] (doc 4.3); requires the plan to be READY. */
    internal suspend fun approvePlan(planId: String): PlanExecutionBinding =
        withContext(Dispatchers.IO) { planReview.approve(planId) }

    internal suspend fun revisePlan(planId: String) = withContext(Dispatchers.IO) { planReview.revise(planId) }

    internal suspend fun cancelPlan(planId: String) = withContext(Dispatchers.IO) { planReview.cancel(planId) }

    /**
     * Executes an APPROVED plan (doc 4.3: only after the user's approval): creates the Goal
     * bound to the approved version (planId + hash) and drives its first turn through the SAME
     * unified [agentRuntime] every in-app entry uses (HX2-01). The provider is resolved from
     * the currently open session under the same fail-closed gates as the chat send path; a
     * failed gate returns null BEFORE anything is written, with the block reason surfaced.
     */
    @Suppress("ReturnCount", "SwallowedException") // one early return per gate; the IAE becomes a localized UI block
    internal suspend fun executeApprovedPlan(
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
        submitTurn(text = null, providerId = providerId, goalId = goalId)
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

    @Suppress("ReturnCount") // explicit draft admission guards
    fun send(text: String) {
        if (preparingDraft) return
        val draft = sessionDraft
        if (draft == null) {
            workScope.launch { sendNow(text) }
            return
        }
        if (text.length > MAX_MODEL_TEXT_CHARS || '\u0000' in text) return
        if (text.isBlank() && draft.attachments.isEmpty()) return
        if (!drafts.beginPreparation(draft.session.id)) return
        _screen.update { it.copy(preparingDraft = true) }
        workScope.launch {
            try {
                val attachments = saveSessionDraft(text) ?: return@launch
                attachments.forEach { stageAttachmentNow(it.uri) }
                if (stagedAttachments.size != attachments.size) return@launch
                sendNow(text)
            } finally {
                drafts.finishPreparation()
                refreshScreen()
            }
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one fail-closed early return per gate condition
    private suspend fun sendNow(text: String, goalId: String? = null) {
        if (text.length > MAX_MODEL_TEXT_CHARS || text.indexOf('\u0000') >= 0) {
            setBlocked(str(R.string.chat_blocked_message_invalid, MAX_MODEL_TEXT_CHARS))
            return
        }
        val staged = stagedAttachments
        // An attachment-only send is valid (ADR-0014 §5): blank text is admitted while
        // staged attachments ride the send; blank text with nothing staged is still the
        // empty-send block of today.
        if (text.isBlank() && staged.isEmpty()) {
            setBlocked(str(R.string.chat_blocked_message_invalid, MAX_MODEL_TEXT_CHARS))
            return
        }
        val session = currentSession() ?: return
        val providerId =
            session.providerId ?: run {
                setBlocked(str(R.string.chat_blocked_no_provider_bound))
                return
            }
        if (!providerService.chatSelectable(providerId)) {
            setBlocked(str(R.string.chat_blocked_provider_untested))
            return
        }
        if (!providerService.isCleartextPermitted(providerId)) {
            setBlocked(str(R.string.chat_blocked_cleartext_http))
            return
        }
        val target = providerService.egressTargetFor(providerId)
        // HXA-055, before the gate: a staged image whose on-device normalization failed at
        // staging is local-only (save/preview) — block with the actionable reason, and no
        // raw bytes may ever reach the wire as a fallback.
        staged.firstNotNullOfOrNull { it.imageSendError }?.let { reason ->
            setBlocked(reason)
            return
        }
        // HXA-055 (ADR-0014 §4): an image leaves ONLY when the target provider's vision
        // capability is CONFIRMED — a real probe (connection test phase 5) or a user-visible
        // manual declaration. Unconfirmed vision blocks BEFORE the disclosure is shown.
        if (staged.any { it.normalizedArtifactId != null }) {
            // capabilitiesFor is fail-closed by contract (null on any stored-snapshot failure).
            val visionConfirmed =
                runCatching { providerService.capabilitiesFor(providerId) }
                    .getOrNull()
                    ?.vision
                    ?: false
            if (!visionConfirmed) {
                setBlocked(
                    str(R.string.chat_blocked_vision_unconfirmed),
                )
                return
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
        when (outcome) {
            is AttachmentSendAdmission.Outcome.Blocked -> {
                // The staged attachments STAY pending: the user removes the problem file
                // and re-sends — a gate block is never a silent drop.
                setBlocked(outcome.reason)
            }

            is AttachmentSendAdmission.Outcome.Egress -> {
                applyEgressDecision(outcome.decision, staged, text, providerId, target, goalId)
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
    @Suppress("ReturnCount") // one fail-closed early return per decision
    private suspend fun applyEgressDecision(
        decision: EgressDisclosure.Decision,
        staged: List<StagedAttachmentEntry>,
        text: String,
        providerId: String,
        target: EgressDisclosure.EgressTarget,
        goalId: String?,
    ) {
        when (decision) {
            EgressDisclosure.Decision.Proceed -> {
                if (staged.isNotEmpty()) {
                    // Unreachable by construction; fail closed so a staged file is never
                    // silently dropped from the outgoing request.
                    setBlocked(str(R.string.chat_blocked_egress_unconfirmed))
                    return
                }
                // A pure-text Proceed carries NO attachments, so it clears none: the staged
                // list is already empty (the snapshot above), and a file picked in the microsecond
                // since that snapshot is the user's for the NEXT send — a send is never a silent
                // drop. (The confirm path clears exactly the approved set, not the live list.)
                submitTurn(text = text, providerId = providerId, goalId = goalId)
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
                _screen.update { it.copy(pendingDisclosure = decision.summary, blockedReason = null) }
            }

            is EgressDisclosure.Decision.Rejected -> {
                setBlocked(egressRejectedLabel(decision.reason))
            }
        }
    }

    /** The user confirmed the high-sensitivity disclosure for [pendingSend]. */
    fun confirmSend() {
        workScope.launch { confirmSendNow() }
    }

    @Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught") // fail-closed gate checks
    private suspend fun confirmSendNow() {
        val text = pendingSend ?: return
        val goalId = pendingGoalId
        // Capture the approved target AND attachment set BEFORE clearing the pending state: the
        // binding checks below compare the LIVE target and the CURRENT staged set against exactly
        // what the dialog showed.
        val approvedTarget = pendingEgress
        val approvedAttachmentIds = pendingAttachmentIds
        val session = currentSession() ?: return
        val providerId = session.providerId ?: return
        pendingGoalId = null
        pendingSend = null
        pendingEgress = null
        pendingAttachmentIds = emptyList()
        _screen.update { it.copy(pendingDisclosure = null) }
        // Fail-closed re-check (the gate already ran when the disclosure was
        // shown): a provider re-test/revocation between the dialog and this
        // confirmation must not open a wire path the user has not approved.
        if (!providerService.chatSelectable(providerId)) {
            setBlocked(str(R.string.chat_blocked_provider_untested))
            return
        }
        if (!providerService.isCleartextPermitted(providerId)) {
            setBlocked(str(R.string.chat_blocked_cleartext_http))
            return
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
                setBlocked(str(R.string.chat_blocked_egress_target_changed))
                return
            }
        if (
            approvedTarget == null ||
            approvedTarget.providerId != liveTarget.providerId ||
            approvedTarget.origin != liveTarget.origin
        ) {
            setBlocked(str(R.string.chat_blocked_egress_target_changed))
            return
        }
        // Delegate the staged-attachment handling (enumeration drift check + re-verify + launch);
        // a pure-text pending (no staged) takes the exact pre-attachment path (no regression).
        confirmStagedSend(text, providerId, approvedAttachmentIds, liveTarget, goalId)
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
    ) {
        val staged = stagedAttachments
        // ADR-0014 §5: the user approved a SPECIFIC enumerated set of attachments — the dialog
        // listed exactly [approvedAttachmentIds]. If the staged set has since changed, a file
        // staged while the dialog was up or one removed since — the approval no longer covers
        // what is on the wire; block and make the user re-send. A file never shown in the dialog
        // never leaves the device, and a removed one is not silently turned into a pure-text
        // send. A pure-text pending has both empty, so this passes and the path below is
        // byte-identical to pre-attachment (no regression). The staged attachments STAY pending.
        if (staged.map { it.artifactId } != approvedAttachmentIds) {
            setBlocked(str(R.string.chat_blocked_attachments_changed))
            return
        }
        if (staged.isEmpty()) {
            submitTurn(text = text, providerId = providerId, goalId = goalId)
            return
        }
        // HXA-055: a staged image whose on-device normalization failed at staging time is
        // local-only (save/preview) — the send is blocked with the actionable reason, and no
        // raw bytes may ever reach the wire as a fallback.
        staged.firstNotNullOfOrNull { it.imageSendError }?.let { reason ->
            setBlocked(reason)
            return
        }
        // RE-VERIFY before the user-approved egress goes out: re-hash every staged file
        // against its bound snapshot (images: the NORMALIZED artifact, the bytes that leave)
        // AND re-scan the FULL content for credential shapes — fail closed if any file
        // changed, vanished or carries a credential in the meantime.
        val materialized = reVerifyStagedForEgress(staged, text, liveTarget) ?: return
        // HXA-055 (ADR-0014 §4): an image leaves ONLY when the target provider's vision
        // capability is CONFIRMED — a real probe (the connection test's phase 5) or a
        // user-visible manual declaration. Unconfirmed vision blocks with an actionable
        // error (re-run the test or declare it manually); the text parts stay sendable in a
        // retry of the same message only if the user removes the image.
        if (materialized.any { it is AttachmentMaterialization.Image }) {
            // capabilitiesFor is fail-closed by contract (null on any stored-snapshot failure).
            val visionConfirmed =
                runCatching { providerService.capabilitiesFor(providerId) }
                    .getOrNull()
                    ?.vision
                    ?: false
            if (!visionConfirmed) {
                setBlocked(
                    str(R.string.chat_blocked_vision_unconfirmed),
                )
                return
            }
        }
        // Ready: the gate's attachments are in staged order — pair each with its staged entry
        // and build (a) the expanded model-visible user message (bounded UNTRUSTED blocks;
        // image blocks describe the pixels that travel as the message's image parts) and
        // (b) the message_attachments bindings [TurnCoordinator.start] persists IN the turn's
        // transaction. An image BINDS THE NORMALIZED ARTIFACT (the bytes that leave) — the raw
        // artifact stays registered but unbound (local save/preview source).
        val blocks =
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
                        // Unreachable (the gate returns only Text/Image in Ready) — fail closed.
                        error("non-materializable attachment reached the send path")
                    }
                }
            }
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
        // Clear EXACTLY the approved set (not the whole live list): a file staged in the
        // microsecond between the drift check above and this lock survives for the user's next
        // send — a send is never a silent drop (the KDoc contract of the lock).
        synchronized(stagedLock) {
            stagedAttachments = stagedAttachments.filterNot { it.artifactId in approvedAttachmentIds }
        }
        // The sent chips clear from the screen now, not left dangling until the turn terminalizes.
        refreshScreen()
        submitTurn(
            text = AttachmentContext.buildUserMessageContent(text, blocks),
            providerId = providerId,
            attachments = bindings.map { AttachmentBindingIntent(it.artifactId, it.boundSha256) },
            goalId = goalId,
        )
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

    fun cancelPendingSend() {
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

    /** A stale card cannot stop a newer Turn in the same session. Pause is Goal-only. */
    fun stopTask(
        turnId: String,
        pause: Boolean = false,
    ) {
        workScope.launch {
            val task = storage.turns.resolve(turnId)
            val active = sessionTurnAdmission.activeTurn(task.sessionId) ?: return@launch
            if (active.turnId != turnId) return@launch
            if (pause) {
                if (storage.goalTurnBindings.byTurn(turnId) == null) return@launch
                if (!storage.turns.requestPause(turnId, clock.now().toEpochMilli())) return@launch
            }
            turnCancels[turnId]?.cancel()
            active.job.cancel()
        }
    }

    private fun refreshBackgroundTasks() {
        _backgroundTasks.value = BackgroundTaskQuery(storage).read()
    }

    fun stop() {
        toolCalls.cancelPendingApproval()
        val sessionId = openSessionId ?: return
        val active = sessionTurnAdmission.activeTurn(sessionId) ?: return
        turnCancels[active.turnId]?.cancel()
        active.job.cancel()
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
            val turnId = _screen.value.retryTargetTurnId ?: return@launch
            val session = currentSession() ?: return@launch
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
     * doc's unified entry point. Returns the started turn's id, or null when the session refused
     * it (fail-closed; the adapter surfaces that as a start-blocked signal).
     */
    override suspend fun startTurn(
        sessionId: String,
        text: String?,
        providerId: String,
        retryTurnId: String?,
        goalId: String?,
        attachments: List<AttachmentBindingIntent>,
        control: RunControlConfig,
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
        )

    /**
     * [com.helix.core.agent.AgentRuntime.cancel] cancels the turn's live work — the stop path,
     * which guards against a stale id (a turn that is no longer its session's active one) and
     * cancels both the turn flag and the service-owned job.
     */
    override fun cancelTurn(turnId: String) {
        stopTask(turnId)
    }

    /**
     * Live-frame source for [com.helix.core.agent.AgentRuntime.observe]: the open session's
     * active turn. A turn is observed live while it is the open session's active turn.
     */
    override val activeTurn: Flow<TurnUi?>
        get() = screen.map { it.activeTurn }

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
    @Suppress("SwallowedException") // the refused start already surfaced its own safe blocked state
    private suspend fun submitTurn(
        text: String?,
        providerId: String,
        retryTurnId: String? = null,
        attachments: List<AttachmentBindingIntent> = emptyList(),
        goalId: String? = null,
    ) {
        val session = currentSession() ?: return
        val control = runControlStore.current
        try {
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
                ),
            )
        } catch (e: TurnStartBlocked) {
            // the refused start already set the session's blocked state (fail-closed, safe label)
        }
    }

    @Suppress("ReturnCount") // one fail-closed return per guard (session, snapshot, turn gate)
    private suspend fun launchTurn(
        text: String?,
        providerId: String,
        retryTurnId: String? = null,
        attachmentBindings: List<MessageAttachmentRepository.Binding> = emptyList(),
        goalId: String? = null,
        requestedSessionId: String? = null,
        controlOverride: RunControlConfig? = null,
    ): String? {
        // The unified AgentRuntime (HX2-01) starts turns for an explicit session with an explicit
        // per-turn control; the in-session send path passes neither and falls back to the open
        // session + the current run-control (behavior unchanged).
        val session = resolveTurnSession(requestedSessionId) ?: return null
        val sessionId = session.id
        // Snapshot before creating the durable Turn: later UI/profile changes cannot alter this
        // Turn's mode, tool table, dispatcher mode, or limits.
        val control = controlOverride ?: runControlStore.current
        // The Room read runs OUTSIDE the gate: a suspend point must never be
        // reached while holding the monitor (the gate only serializes the
        // turn-start writes below).
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
        synchronized(turnGate) {
            // Per-session admission: refuse only when THIS session already has an in-flight turn —
            // a turn in another session must never make this send vanish.
            if (sessionTurnAdmission.hasActive(sessionId)) return null
            val liveSession = storage.sessions.resolve(sessionId)
            if (liveSession.providerId != providerId || liveSession.modelId != session.modelId) {
                setBlocked(str(R.string.chat_blocked_provider_state_changed))
                return null
            }
            val turnId = idGenerator()
            val callId = idGenerator()
            val spec = TurnStartSpec(sessionId, turnId, callId, snapshot, text, attachmentBindings)
            val goalStart =
                goalId?.let {
                    GoalRunCoordinator(storage, clock, idGenerator).start(
                        GoalTurnStart(it, com.helix.core.agent.GoalWakeReason.USER_OPEN, spec, control.budgets),
                    )
                }
            if (goalId != null && goalStart == null) {
                setBlocked(str(R.string.goal_continue_unavailable))
                return null
            }
            val coordinator = goalStart?.coordinator ?: TurnCoordinator.start(storage, clock, idGenerator, spec)
            val effectiveControl =
                goalStart?.let { control.copy(mode = AgentMode.GOAL, budgets = it.budgets) } ?: control
            // The worker waits behind this gate until its active-turn entry and initial UI are
            // published. Without the gate, a fast scheduler can begin streaming before register;
            // stop() in that window cannot find the job and silently fails to cancel the turn.
            val startGate = CompletableDeferred<Unit>()
            val job =
                workScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runTurn(sessionId, coordinator, providerId, retryTurnId, startGate, effectiveControl)
                }
            sessionTurnAdmission.register(sessionId, job, turnId)
            if (openSessionId == sessionId) {
                refreshScreen() // publish the committed user message before the model may emit or wait
                publishTurn(TurnUi(turnId, TurnState.WAITING_MODEL, null, null, false))
            }
            startGate.complete(Unit)
            return turnId
        }
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
        event: com.helix.core.model.ModelEvent,
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
        coordinator.terminalize(outcome)
        // HXA-036: the turn is over — clear the dispatcher's same-turn denial set for it
        // (a later turn may re-request a previously denied action and get a fresh card)
        // and drop this turn's pipeline state.
        toolPipeline.endTurn(turnId)
        turnCancels.remove(turnId)
        toolCalls.finishTurn(turnId)
        // The terminal row is now durable. Release admission BEFORE publishing terminal UI so a
        // user reacting immediately cannot hit the still-active coroutine's completion gap.
        sessionTurnAdmission.complete(sessionId, turnId)
        terminalLabel(outcome.state, outcome.errorCode)?.let { label ->
            publishTurn(
                TurnUi(turnId, outcome.state, null, label, outcome.state == TurnState.FAILED),
            )
        }
        refreshScreen()
        syncGoalReminderForTurn(turnId)
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
            openSessionId = null
            null
        }
    }

    private fun refreshScreen() {
        refreshBackgroundTasks()
        sessionDraft?.takeIf { it.session.id == openSessionId }?.let { draft ->
            _screen.value =
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
            return
        }
        val sessionId = resolvableOpenSessionId()
        val turns = sessionId?.let { id -> storage.turns.listBySession(id) }.orEmpty()
        val lastTurn = turns.lastOrNull()
        // Refreshes race with targeted UI publications (for example an attachment refusal).
        // Build from the value observed by StateFlow's atomic update so a refresh can never
        // restore an older blocked/disclosure/streaming snapshot over a newer publication.
        _screen.update { current ->
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
                subscriptionRecoveries = subscriptionRecoveriesFor(storage, sessionId, current.subscriptionRecoveries),
                activeTurn = lastTurn?.let { projection.turnUiFor(it, current.activeTurn?.streamingText) },
                turns = turns.map { projection.turnUiFor(it, null) },
                pendingDisclosure = current.pendingDisclosure,
                blockedReason = current.blockedReason,
                retryTargetTurnId = projection.retryTargetFor(sessionId),
                pendingAttachments = stagedAttachmentsUi(),
                shareDraftText = shareDraftText,
                taskLedger = sessionId?.let { TaskLedgerProjection.forSession(storage, it) }.orEmpty(),
            )
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
        if (_backgroundTasks.value.none { it.id == turn.id && it.state == turn.state }) refreshBackgroundTasks()
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
                com.helix.provider.api.ProviderCapabilities.parse(c.capabilitySnapshot).let { caps ->
                    ",\"capabilities\":${com.helix.provider.api.ProviderCapabilities.toJsonString(caps)}"
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

package com.helix.app.chat

import android.util.Log
import com.helix.app.approval.ApprovalCancelledException
import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalUiMapper
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ProviderBadgeUi
import com.helix.app.provider.ProviderService
import com.helix.app.tool.ToolPipeline
import com.helix.core.model.AgentMode
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.Clock
import com.helix.core.model.ErrorCode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SystemClock
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnState
import com.helix.core.policy.DataOrigin
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.CanonicalArgs
import com.helix.tools.framework.DecisionSource
import com.helix.tools.framework.DispatchAuditEvent
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * One class owns every chat-screen fact (sessions, the open conversation,
 * the in-flight turn, the gates, the tool timeline) by design — splitting it
 * across several services would put the invariants (one active turn per
 * session, the gate→turn hand-off, the live-card overlay) across objects.
 * The class-level suppressions record that single-owner decision.
 */
@Suppress("TooManyFunctions", "LargeClass")
class ChatService(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    profileStore: SafetyProfileStore,
    private val toolPipeline: ToolPipeline,
    private val clock: Clock = SystemClock(),
    private val idGenerator: () -> String,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val workScope = scope

    private val _sessions = MutableStateFlow<List<SessionRowUi>>(emptyList())
    private val _screen = MutableStateFlow(EMPTY_SCREEN)

    val sessions: StateFlow<List<SessionRowUi>> = _sessions.asStateFlow()
    val screen: StateFlow<ChatScreenState> = _screen.asStateFlow()

    /** The runtime safety profile for the chat header (ADR-0005 display). */
    val profile: StateFlow<SafetyProfile> = profileStore.flow

    /** Serializes per-session turn admission (one active turn per session). */
    private val turnGate = Any()
    private var activeTurnJob: Job? = null
    private var openSessionId: String? = null
    private var pendingSend: String? = null

    // --- HXA-036: the tool pipeline state (cards, dispatch facts, turn cancels) ---

    /** The trusted card facts per model call id, set before the dispatch, read by the card sink. */
    private val dispatchFacts = java.util.concurrent.ConcurrentHashMap<String, DispatchFacts>()

    /** Per-turn cancel signals handed to the dispatcher (the stop button sets them). */
    private val turnCancels = java.util.concurrent.ConcurrentHashMap<String, TurnCancelSignal>()

    /** The approval card currently waiting for the user's decision (the stop button cancels it). */
    @Volatile
    private var activePendingApprovalId: String? = null

    init {
        refreshSessions()
    }

    // --------------------------------------------------------------------------------
    // Session intents
    // --------------------------------------------------------------------------------

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

    /** Opens a session: loads its persisted messages and the provider badge. */
    fun openSession(id: String) {
        openSessionId = id
        workScope.launch { refreshScreen() }
    }

    /**
     * Closes the open session: the chat screen goes back to the session list.
     * An in-flight turn keeps running in this service's scope (the UI holds no
     * network Job); its persisted state is shown when the session reopens.
     */
    fun closeSession() {
        openSessionId = null
        workScope.launch { refreshScreen() }
    }

    /** Dismisses the current user-visible [ChatScreenState.blockedReason] banner. */
    fun dismissBlocked() {
        _screen.value = _screen.value.copy(blockedReason = null)
    }

    // --------------------------------------------------------------------------------
    // Send path
    // --------------------------------------------------------------------------------

    /**
     * The send intent. Order (fail-closed, user-visible):
     * 1. session has a provider; 2. the provider passed its connection test;
     * 3. the cleartext host:port gate (doc 10 section 2.5); 4. the egress
     *    disclosure gate (forbidden content rejected; high-sensitivity held
     *    for per-send confirmation — [confirmSend]).
     *
     * The whole gate runs on this service's IO scope: the provider reads are
     * Room reads and must never run on the UI thread. The UI may call from
     * any thread; the visible outcome arrives via [screen].
     */
    fun send(text: String) {
        workScope.launch { sendNow(text) }
    }

    @Suppress("ReturnCount") // one fail-closed early return per gate condition
    private suspend fun sendNow(text: String) {
        val session = currentSession() ?: return
        val providerId =
            session.providerId ?: run {
                setBlocked("该会话没有绑定 Provider")
                return
            }
        if (!providerService.chatSelectable(providerId)) {
            setBlocked("该 Provider 未完成连接测试（设置 → Provider → 连接测试）")
            return
        }
        if (!providerService.isCleartextPermitted(providerId)) {
            setBlocked("该 Provider 使用明文 HTTP：请在 Provider 设置中重新确认该 host:port 授权")
            return
        }
        val target = providerService.egressTargetFor(providerId)
        val decision =
            EgressDisclosure.decide(listOf(EgressDisclosure.OutgoingContent.UserText), text, target)
        when (decision) {
            is EgressDisclosure.Decision.Rejected -> {
                setBlocked(decision.reason)
            }

            is EgressDisclosure.Decision.Confirm -> {
                pendingSend = text
                _screen.value = _screen.value.copy(pendingDisclosure = decision.summary, blockedReason = null)
            }

            EgressDisclosure.Decision.Proceed -> {
                launchTurn(text, providerId)
            }
        }
    }

    /** The user confirmed the high-sensitivity disclosure for [pendingSend]. */
    fun confirmSend() {
        workScope.launch { confirmSendNow() }
    }

    @Suppress("ReturnCount") // one fail-closed early return per gate condition
    private suspend fun confirmSendNow() {
        val text = pendingSend ?: return
        val session = currentSession() ?: return
        val providerId = session.providerId ?: return
        pendingSend = null
        _screen.value = _screen.value.copy(pendingDisclosure = null)
        // Fail-closed re-check (the gate already ran when the disclosure was
        // shown): a provider re-test/revocation between the dialog and this
        // confirmation must not open a wire path the user has not approved.
        if (!providerService.chatSelectable(providerId)) {
            setBlocked("该 Provider 未完成连接测试（设置 → Provider → 连接测试）")
            return
        }
        if (!providerService.isCleartextPermitted(providerId)) {
            setBlocked("该 Provider 使用明文 HTTP：请在 Provider 设置中重新确认该 host:port 授权")
            return
        }
        launchTurn(text, providerId)
    }

    fun cancelPendingSend() {
        pendingSend = null
        _screen.value = _screen.value.copy(pendingDisclosure = null)
    }

    /**
     * The stop button: cancels the in-flight turn (service-owned Job). A turn waiting on the
     * approval card is cancelled through the broker (the pending record stays PENDING — the
     * user never decided — and expires with its window); a tool executing sees the turn's
     * cancel flag at the dispatcher's stage checks (CANCELLED_AFTER_START / BEFORE_START).
     */
    fun stop() {
        activePendingApprovalId?.let { toolPipeline.broker.cancel(it) }
        turnCancels.values.forEach { it.cancel() }
        activeTurnJob?.cancel()
    }

    /** The approval card's "本次批准" action (UI -> service -> broker, on the work scope). */
    fun approveApproval(approvalId: String) {
        decideApproval(approvalId, ApprovalDecision.APPROVED)
    }

    /** The approval card's "拒绝" action (UI -> service -> broker, on the work scope). */
    fun denyApproval(approvalId: String) {
        decideApproval(approvalId, ApprovalDecision.DENIED)
    }

    // The tap path must survive ANY failure on the stale-card / unknown-record path
    // (the repository's one-time guard throws, but a broad catch guarantees the user's
    // tap is always visibly handled — never a crash, never a silent no-op).
    @Suppress("TooGenericExceptionCaught")
    private fun decideApproval(
        approvalId: String,
        decision: ApprovalDecision,
    ) {
        workScope.launch {
            try {
                toolPipeline.broker.decide(approvalId, decision)
                if (decision == ApprovalDecision.APPROVED) {
                    updateCard(approvalId) { it.copy(state = ApprovalCardState.APPROVED) }
                } else {
                    updateCard(
                        approvalId,
                    ) { it.copy(state = ApprovalCardState.DENIED, terminalDetail = "用户已拒绝本次动作") }
                }
            } catch (e: Exception) {
                // A stale card (the record was already decided or the id is unknown): the
                // repository's one-time guard throws — surface a stable card error, never a
                // crash, never a silent no-op (the user's tap must be visible as handled).
                Log.e(TAG, "approval $approvalId could not be decided", e)
                updateCard(
                    approvalId,
                ) { it.copy(state = ApprovalCardState.FAILED, terminalDetail = "审批操作失败：记录已不存在或已决定") }
            }
        }
    }

    /**
     * The broker's card sink (installed by the container): publishes the pending card into
     * the timeline for the model call that requested it. The card is built from the TRUSTED
     * facts captured at request time (descriptor, profile-at-request-time, the canonical
     * arguments) — the display can never drift from what the binding hashes. Fails closed
     * (error) when the facts are missing: a card that cannot be rendered truthfully cannot
     * be approved.
     */
    fun onApprovalCard(
        approvalId: String,
        request: ApprovalRequest,
    ) {
        val callId = request.binding.toolCallId
        val facts = dispatchFacts[callId]
        val descriptor = facts?.descriptor
        if (facts == null || descriptor == null) {
            error("approval card requested without dispatch facts for model call $callId")
        }
        val card =
            ApprovalUiMapper.buildCard(
                approvalId = approvalId,
                binding = request.binding,
                state = ApprovalCardState.PENDING,
                descriptor = descriptor,
                arguments = facts.args,
                dynamicRisk = request.dynamicRisk,
                profile = facts.profile,
                dataOrigin = facts.dataOrigin,
                egressOrigin = facts.egress?.endpoint?.origin,
                egressResidence =
                    facts.egress
                        ?.endpoint
                        ?.residence()
                        ?.name,
                egressCategory = facts.egress?.dataSensitivity,
                boundedRule = ApprovalUiMapper.boundedRuleUi(request.boundedEgressRule),
                confirmationDetail = request.confirmationDetail,
                terminalDetail = null,
            )
        activePendingApprovalId = approvalId
        storage.toolCalls
            .byTurnAndCallId(facts.turnId, callId)
            ?.let { row -> storage.toolCalls.updateState(row, ToolCallState.AWAITING_APPROVAL) }
        // The stream path publishes a request row before the dispatch, so the card
        // attaches to it; a direct-dispatch caller (tests, future non-stream flows) has
        // no row yet — create one from the trusted facts. A card is NEVER dropped:
        // an approval that cannot be shown cannot be approved.
        val existing = _screen.value.toolTimeline.firstOrNull { it.callId == callId }
        if (existing != null) {
            attachCardToRow(callId, card)
        } else {
            publishToolRow(
                turnId = facts.turnId,
                callId = callId,
                toolName = request.binding.toolName,
                requestSummary = CanonicalArgs.canonicalize(facts.args),
                stateLabel = "待审批",
                resultSummary = null,
                card = card,
            )
        }
    }

    /**
     * Retries the newest FAILED turn: a NEW turn re-sends the SAME user
     * message (already persisted) — an explicit user action, never an
     * automatic replay (doc 02 section 5.2; acceptance scenario #10).
     */
    fun retry() {
        workScope.launch {
            val turnId = _screen.value.retryTargetTurnId ?: return@launch
            val session = currentSession() ?: return@launch
            val providerId = session.providerId ?: return@launch
            if (!providerService.chatSelectable(providerId)) {
                setBlocked("该 Provider 未完成连接测试（设置 → Provider → 连接测试）")
                return@launch
            }
            launchTurn(text = null, providerId = providerId, retryTurnId = turnId)
        }
    }

    // --------------------------------------------------------------------------------
    // Turn execution (service-owned; the UI only observes)
    // --------------------------------------------------------------------------------

    @Suppress("ReturnCount") // one fail-closed return per guard (session, snapshot, turn gate)
    private suspend fun launchTurn(
        text: String?,
        providerId: String,
        retryTurnId: String? = null,
    ) {
        val session = currentSession() ?: return
        val sessionId = session.id
        // The Room read runs OUTSIDE the gate: a suspend point must never be
        // reached while holding the monitor (the gate only serializes the
        // turn-start writes below).
        val snapshot =
            try {
                providerSnapshot(providerId)
            } catch (e: IllegalArgumentException) {
                // The provider row was deleted or is corrupt between the gate
                // and the snapshot (storedConfig throws IAE for both): no turn
                // row has been written yet, so surface a blocked state — the
                // send must never vanish.
                Log.e(TAG, "could not snapshot provider $providerId", e)
                setBlocked("消息未发送：Provider 状态已变化，请检查 Provider 设置后重试")
                return
            }
        synchronized(turnGate) {
            if (activeTurnJob?.isActive == true) return
            val turnId = idGenerator()
            val now = clock.now().toEpochMilli()
            if (text != null) {
                storage.withTransaction {
                    storage.turns.start(turnId, sessionId, now)
                    storage.messages.append(idGenerator(), sessionId, turnId, ModelRole.USER.name, KIND_TEXT, text)
                }
            } else {
                storage.turns.start(turnId, sessionId, now)
            }
            val callId = idGenerator()
            storage.modelCalls.append(callId, turnId, snapshot, CALL_RUNNING)
            val turn = storage.turns.resolve(turnId)
            storage.turns.updateState(turn, TurnState.WAITING_MODEL, 0, null, null)
            activeTurnJob =
                workScope.launch {
                    runTurn(sessionId, turnId, callId, providerId, retryTurnId)
                }
            publishTurn(TurnUi(turnId, TurnState.WAITING_MODEL, null, null, false))
        }
    }

    // The boundary catch is deliberately broad: ANY unexpected failure at the
    // model boundary (guard rejects, corrupt rows, a vanished provider) must
    // still terminalize the turn with a safe label — a narrow catch would
    // leave the UI stuck on "sending" (doc 02 section 13).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runTurn(
        sessionId: String,
        turnId: String,
        callId: String,
        providerId: String,
        retryTurnId: String?,
    ) {
        val acc = StreamAccumulator()
        try {
            val request = buildRequest(sessionId, retryTurnId)
            val provider = providerService.modelProviderFor(providerId)
            provider.stream(request).collect { event ->
                applyEvent(event, acc, turnId)
            }
            // HXA-036: a CLEAN stream may end with tool calls — process them serially in
            // model order (doc 02 section 5.3), each through the dispatcher (validation ->
            // policy -> approval -> execution -> verification -> audit). Tool-result
            // back-fill into a second model call is HXA-037; this turn terminalizes after
            // the tools settle.
            val decision =
                if (turnCancels[turnId]?.isCancelled() == true) {
                    TerminalOutcome(TurnState.CANCELLED, null, "已停止")
                } else {
                    terminalDecision(acc)
                }
            if (decision.state == TurnState.COMPLETED) {
                processToolCalls(turnId, acc)
            }
            terminalize(turnId, callId, sessionId, acc, decision)
        } catch (e: CancellationException) {
            terminalize(turnId, callId, sessionId, acc, TerminalOutcome(TurnState.CANCELLED, null, null))
            throw e
        } catch (e: ApprovalCancelledException) {
            // The user stopped the turn while the approval card was pending: the turn
            // terminalizes as CANCELLED; the approval record stays PENDING (no decision
            // was made) and expires with its window. The job completes normally — the
            // cancellation was the user's own action, not a failure. (The exception
            // carries only the approval id — safe to log as metadata.)
            Log.i(TAG, "turn $turnId stopped while awaiting approval: ${e.message}")
            terminalize(turnId, callId, sessionId, acc, TerminalOutcome(TurnState.CANCELLED, null, "已停止"))
        } catch (e: Exception) {
            // An unexpected boundary failure (a guard reject, corrupt rows, a
            // provider row deleted mid-turn): the turn STILL reaches a
            // terminal state with a safe label — never a stuck "sending" UI
            // (doc 02 section 13: raw messages are never shown).
            Log.e(TAG, "turn $turnId failed at the model boundary", e)
            terminalize(
                turnId,
                callId,
                sessionId,
                acc,
                TerminalOutcome(TurnState.FAILED, ErrorCode.INTERNAL.name, "请求失败，请重试"),
            )
        }
    }

    /** The stream collector's working set: the text buffer + the terminal-decision inputs. */
    private class StreamAccumulator {
        val buffer = StringBuilder()
        var usageJson: String? = null
        var refusalLabel: String? = null
        var errorLabel: String? = null
        var errorCode: String? = null
        var receiving = false

        /** The model's tool calls in stream order (index -> working set). */
        val toolCalls = LinkedHashMap<Int, ModelToolCall>()
    }

    /** One in-flight model tool call: the provider call id, the name, the argument deltas. */
    private class ModelToolCall(
        val callId: String,
        val name: String,
    ) {
        val args = StringBuilder()
        var finished = false
    }

    /**
     * One stream event → the turn's working set. M2: reasoning/tool events
     * are consumed but not rendered (the M2 chat UI shows text + terminal
     * state only).
     */
    private fun applyEvent(
        event: ModelEvent,
        acc: StreamAccumulator,
        turnId: String,
    ) {
        when (event) {
            is ModelEvent.TextDelta -> {
                acc.buffer.append(event.text)
                if (!acc.receiving) {
                    acc.receiving = true
                    storage.turns.updateState(
                        storage.turns.resolve(turnId),
                        TurnState.RECEIVING_MODEL,
                        1,
                        null,
                        null,
                    )
                }
                publishTurn(
                    TurnUi(turnId, TurnState.RECEIVING_MODEL, acc.buffer.toString(), null, false),
                )
            }

            is ModelEvent.Usage -> {
                acc.usageJson = usageToJson(event)
            }

            is ModelEvent.Refusal -> {
                acc.refusalLabel = "模型拒绝（安全/策略）"
            }

            is ModelEvent.Error -> {
                acc.errorLabel = ConnectionTestMapping.codeLabel(event.code)
                acc.errorCode = event.code.name
            }

            is ModelEvent.ToolCallStarted -> {
                acc.toolCalls[event.index] = ModelToolCall(event.id.value, event.name)
            }

            is ModelEvent.ToolArgumentsDelta -> {
                acc.toolCalls[event.index]?.args?.append(event.jsonFragment)
            }

            is ModelEvent.ToolCallFinished -> {
                acc.toolCalls[event.index]?.finished = true
            }

            // The M3 chat UI renders tool CALLS through the timeline (HXA-036); reasoning
            // and the stream's terminal marker are still consumed but not rendered.
            is ModelEvent.ReasoningDelta,
            is ModelEvent.Completed,
            -> {
                Unit
            }
        }
    }

    /**
     * The turn's terminal state. Order matters: a refusal is the honest
     * "the model declined" state; otherwise a terminal Error event
     * (transport/auth/protocol — HXA-025 maps EVERY stream failure to a
     * terminal Error, the collector never sees an exception) must persist as
     * FAILED with its safe label + code — never as COMPLETED; only a clean
     * stream completes the turn.
     */
    private fun terminalDecision(acc: StreamAccumulator): TerminalOutcome =
        when {
            acc.refusalLabel != null -> TerminalOutcome(TurnState.FAILED, null, acc.refusalLabel)
            acc.errorCode != null -> TerminalOutcome(TurnState.FAILED, acc.errorCode, acc.errorLabel)
            else -> TerminalOutcome(TurnState.COMPLETED, null, null)
        }

    /** A turn's terminal state: the persisted state, the persisted safe code, the user-visible label. */
    private data class TerminalOutcome(
        val state: TurnState,
        val errorCode: String?,
        val displayLabel: String?,
    )

    /**
     * Persists the turn terminal + the assistant content row (when any) + the
     * model-call terminal, then refreshes the UI state. Runs from the stream
     * completion, the stop path or the error path — always exactly once.
     */
    private fun terminalize(
        turnId: String,
        callId: String,
        sessionId: String,
        acc: StreamAccumulator,
        outcome: TerminalOutcome,
    ) {
        val text = acc.buffer.toString()
        if (text.isNotBlank()) {
            // The turn's OWN session, not the currently open one: the user
            // may have navigated back to the session list while the stream
            // was still running (the in-flight turn keeps running by design).
            storage.messages.append(
                idGenerator(),
                sessionId,
                turnId,
                ModelRole.ASSISTANT.name,
                KIND_TEXT,
                text,
            )
        }
        val turn = storage.turns.resolve(turnId)
        storage.turns.updateState(
            turn,
            outcome.state,
            maxOf(turn.stepCount, 1),
            clock.now().toEpochMilli(),
            outcome.errorCode,
        )
        storage.modelCalls.update(
            storage.modelCalls.resolve(callId),
            callState(outcome.state),
            acc.usageJson,
            null,
        )
        // HXA-036: the turn is over — clear the dispatcher's same-turn denial set for it
        // (a later turn may re-request a previously denied action and get a fresh card)
        // and drop this turn's pipeline state.
        toolPipeline.endTurn(turnId)
        turnCancels.remove(turnId)
        dispatchFacts.values.removeIf { it.turnId == turnId }
        activePendingApprovalId = null
        if (outcome.displayLabel != null) {
            publishTurn(
                TurnUi(turnId, outcome.state, null, outcome.displayLabel, outcome.state == TurnState.FAILED),
            )
        }
        refreshScreen()
    }

    /**
     * Builds the model request from PERSISTED rows: the session's
     * user/assistant history (ChatHistoryBuilder) — for a retry, the retried
     * turn's own assistant rows are excluded but its user message is kept, so
     * the request ends with the same USER message the user is retrying.
     */
    private suspend fun buildRequest(
        sessionId: String,
        retryTurnId: String?,
    ): ModelRequest {
        val rows =
            storage.messages
                .listBySession(sessionId)
                .map { ChatHistoryBuilder.PersistedRow(it.turnId, it.role, storage.messages.readContent(it)) }
        val history = ChatHistoryBuilder.rowsForTurn(rows, retryTurnId)
        val messages = ChatHistoryBuilder.toModelMessages(history)
        require(messages.lastOrNull()?.role == ModelRole.USER) {
            "the request must end with the user message"
        }
        val config = providerService.storedConfig(sessionProviderId(sessionId))
        return ModelRequest(model = config.model, messages = messages)
    }

    // --------------------------------------------------------------------------------
    // HXA-036: tool call processing (model tool calls -> dispatcher -> timeline)
    // --------------------------------------------------------------------------------

    /**
     * The trusted facts the approval card is built from, captured at REQUEST time (before
     * the dispatch). The card must show what the binding hashes — the profile at request
     * time, the descriptor, the canonical arguments — so these facts are immutable once
     * captured; a later profile switch cannot change a pending card (roadmap HXA-036 test
     * B1: 切换 Profile 不改变待审批决定).
     */
    private data class DispatchFacts(
        val descriptor: com.helix.tools.framework.ToolDescriptor?,
        val args: JsonObject,
        val profile: SafetyProfile,
        val dataOrigin: DataOrigin,
        val turnId: String,
        /** The call's egress facet (null when the call does not egress) — the card shows
         * origin / residence / data category from these trusted facts. */
        val egress: com.helix.core.policy.EgressRequest? = null,
    )

    /** The turn's [CancelSignal]: the stop button flips it; the dispatcher checks it at its stage checks. */
    private class TurnCancelSignal : CancelSignal {
        @Volatile
        private var cancelled = false

        fun cancel() {
            cancelled = true
        }

        override fun isCancelled(): Boolean = cancelled
    }

    /**
     * Processes a clean stream's tool calls SERIALLY in model order (doc 02 section 5.3:
     * results later enter the model context in original call sequence — HXA-037). This runs
     * on the work scope's IO thread, so the broker's blocking user-decision wait never
     * touches the main thread.
     */
    private fun processToolCalls(
        turnId: String,
        acc: StreamAccumulator,
    ) {
        for ((_, call) in acc.toolCalls) {
            if (!call.finished) {
                // A stream that did not FINISH this call is not a complete tool call: the
                // turn decision would not have been COMPLETED, so this is defensive — but
                // fail closed: never dispatch a partial argument stream.
                continue
            }
            dispatchToolCall(call.callId, turnId, call.name, call.args.toString())
        }
    }

    /**
     * The single per-call entry point of the tool pipeline (roadmap HXA-036; doc 11: the
     * Dispatcher is the only path between model-requested calls and implementations):
     * persist the tool_call row with the CANONICAL argument bytes (doc 02 section 9.1/9.2
     * — the stored argsJson is the same text the approval binding hashes; the row's
     * primary key IS the model call id, the approvals table's foreign key targets
     * tool_calls.id) → publish the timeline row → run the ToolDispatcher → settle the
     * persisted state, the bounded result and the timeline/card.
     *
     * The stream path uses this per model tool call; direct (non-stream) callers may
     * invoke it on their own worker thread — the dispatcher MAY BLOCK on the approval
     * card's user-decision wait, so never call it from the main thread. The turn row must
     * already be persisted (the tool_call foreign key targets turns.id); the session id is
     * the turn's PERSISTED session (a trusted fact, never a caller-supplied one).
     *
     * The mode is [AgentMode.ACT]: the chat UI has no Plan/Goal tool surface yet (those
     * come with their own milestones) — when one arrives it feeds this field, and the
     * Policy Engine's Plan gate (READ_ONLY + L1 ceiling) applies from that request on.
     *
     * Malformed input that the dispatcher can never see (an invalid tool name, non-object
     * arguments) is persisted + audited here and returned as a stable
     * [ToolDispatchOutcome.Denied]. A turn stop during the approval wait settles the call
     * as CANCELLED (doc 11: every queued call gets a durable outcome) and rethrows
     * [ApprovalCancelledException] for the turn-level handler.
     */
    @Suppress("ReturnCount") // one fail-closed early return per malformed-input kind + the main return
    fun dispatchToolCall(
        toolCallId: String,
        turnId: String,
        toolNameRaw: String,
        rawArgsJson: String,
    ): ToolDispatchOutcome {
        val turn = storage.turns.resolve(turnId)
        val toolName: ToolName =
            runCatching { ToolName(toolNameRaw) }.getOrNull()
                ?: return persistRejectedToolCall(
                    turn,
                    toolCallId,
                    toolNameRaw,
                    rawArgsJson,
                    "unknown",
                    DispatchOutcomeCode.UNKNOWN_TOOL,
                    "工具名不合法，已拒绝",
                )
        val descriptor = toolPipeline.resolveLatest(toolNameRaw)
        val args: JsonObject =
            parseJsonObjectOrNull(rawArgsJson)
                ?: return persistRejectedToolCall(
                    turn,
                    toolCallId,
                    toolNameRaw,
                    rawArgsJson,
                    descriptor?.version?.value?.toString() ?: "unknown",
                    DispatchOutcomeCode.INVALID_ARGUMENTS,
                    "参数不是有效的 JSON 对象，已拒绝",
                )
        val canonical = CanonicalArgs.canonicalize(args)
        val row =
            storage.toolCalls.append(
                id = toolCallId,
                turnId = turnId,
                callId = toolCallId,
                name = toolNameRaw,
                version = descriptor?.version?.value?.toString() ?: "0",
                argsJson = canonical,
                state = ToolCallState.PENDING.name,
            )
        // The card facts: profile at REQUEST time (the consumer profile is STANDARD-pinned;
        // a later switch must not change a pending card — the card renders these trusted
        // facts, never the live store).
        val profile = profile.value
        publishToolRow(turnId, toolCallId, toolNameRaw, canonical, "处理中", null, null)
        val request = buildDispatchRequest(turn, toolCallId, toolName, descriptor, args, profile)
        dispatchFacts[toolCallId] =
            DispatchFacts(descriptor, args, profile, DataOrigin.WORKSPACE, turnId, request.egress)
        return try {
            val outcome = toolPipeline.dispatcher.dispatch(request)
            settleToolCall(row, toolCallId, toolNameRaw, outcome)
            outcome
        } catch (e: ApprovalCancelledException) {
            // The user stopped the turn while the card was pending: the call never
            // executed — settle its durable CANCELLED outcome (doc 11: cancel leaves a
            // durable outcome for every queued call), drop the turn's cancel signal (the
            // turn is over; the stream path's terminalize does the same), then propagate
            // the turn-level cancellation so the turn terminalizes CANCELLED.
            settleToolCall(row, toolCallId, toolNameRaw, ToolDispatchOutcome.Cancelled)
            turnCancels.remove(turnId)
            throw e
        }
    }

    /** A JSON object, or null for any malformed input (parse failures are swallowed — the
     * rejection path handles the malformed input itself; the raw parse text is model
     * content and is never logged or shown). */
    private fun parseJsonObjectOrNull(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject

    /**
     * The trusted dispatch request (doc 11: the dispatcher receives the contract target —
     * the app cannot lower a tool's isolation; scope/egress are null for the HXA-036 tool
     * set: no SAF scope, no egress tools yet).
     */
    private fun buildDispatchRequest(
        turn: com.helix.core.storage.entity.TurnEntity,
        toolCallId: String,
        toolName: ToolName,
        descriptor: com.helix.tools.framework.ToolDescriptor?,
        args: JsonObject,
        profile: SafetyProfile,
    ): ToolDispatchRequest =
        ToolDispatchRequest(
            toolCallId = toolCallId,
            turnId = turn.id,
            sessionId = turn.sessionId,
            toolName = toolName,
            toolVersion = descriptor?.version ?: ToolVersion(0),
            args = args,
            mode = AgentMode.ACT,
            profile = profile,
            executionTarget = descriptor?.executionTarget ?: ExecutionTargetType.LOCAL_ANDROID,
            dataOrigin = DataOrigin.WORKSPACE,
            scope = null,
            uiToken = "chat:${turn.id}",
            egress = null,
            originSeenInSession = true,
            lanScopes = emptySet(),
            overwritesExisting = false,
            codeOrCommandChanged = false,
            sourceBindingChanged = false,
            cancel = turnCancels.getOrPut(turn.id) { TurnCancelSignal() },
        )

    /** Persists the bounded result + the timeline row for a finished dispatch. */
    private fun settleToolCall(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome,
    ) {
        when (outcome) {
            is ToolDispatchOutcome.Succeeded -> {
                settleSucceeded(row, toolCallId, toolName, outcome)
            }

            is ToolDispatchOutcome.Denied -> {
                settleDenied(row, toolCallId, toolName, outcome)
            }

            ToolDispatchOutcome.Cancelled -> {
                settleCancelled(row, toolCallId, toolName)
            }

            is ToolDispatchOutcome.ExecutionFailed -> {
                settleExecutionFailed(row, toolCallId, toolName, outcome)
            }
        }
    }

    private fun settleSucceeded(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome.Succeeded,
    ) {
        storage.toolCalls.updateState(row, ToolCallState.COMPLETED)
        val summary = boundedSummary(outcome.result.payload)
        val result =
            storage.toolResults.append(
                id = idGenerator(),
                toolCallId = toolCallId,
                status = "SUCCEEDED",
                summary = summary,
                content = outcome.result.payload,
            )
        storage.toolResults.markVerified(result)
        setCardStateForCall(toolCallId, ApprovalCardState.SUCCEEDED, null)
        publishToolRow(row.turnId, toolCallId, toolName, row.argsJson, "已执行成功", summary, null)
    }

    private fun settleDenied(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome.Denied,
    ) {
        storage.toolCalls.updateState(row, ToolCallState.DENIED)
        storage.toolResults.append(
            id = idGenerator(),
            toolCallId = toolCallId,
            status = "DENIED",
            summary = outcome.detail,
            content = null,
        )
        setCardStateForCall(
            toolCallId,
            ApprovalCardState.FAILED,
            outcome.code.name + "：" + outcome.detail,
            keepDenied = true,
        )
        publishToolRow(
            row.turnId,
            toolCallId,
            toolName,
            row.argsJson,
            ApprovalUiMapper.codeLabel(outcome.code),
            outcome.detail,
            null,
        )
    }

    private fun settleCancelled(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
    ) {
        storage.toolCalls.updateState(row, ToolCallState.CANCELLED)
        storage.toolResults.append(
            id = idGenerator(),
            toolCallId = toolCallId,
            status = "CANCELLED",
            summary = "启动前取消（无副作用）",
            content = null,
        )
        setCardStateForCall(toolCallId, ApprovalCardState.FAILED, "已停止")
        publishToolRow(row.turnId, toolCallId, toolName, row.argsJson, "已取消", "启动前取消（无副作用）", null)
    }

    private fun settleExecutionFailed(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome.ExecutionFailed,
    ) {
        storage.toolCalls.updateState(row, ToolCallState.FAILED)
        storage.toolResults.append(
            id = idGenerator(),
            toolCallId = toolCallId,
            status = "FAILED",
            summary = outcome.detail,
            content = null,
        )
        setCardStateForCall(
            toolCallId,
            ApprovalCardState.FAILED,
            ApprovalUiMapper.codeLabel(outcome.code) + "：" + outcome.detail,
        )
        publishToolRow(row.turnId, toolCallId, toolName, row.argsJson, "执行失败", outcome.detail, null)
    }

    /**
     * A model tool call that is malformed BEFORE the dispatcher can run it (an invalid
     * tool name, non-object arguments): persist the call row + the failed result + ONE
     * audit event (the dispatcher's own per-dispatch audit contract, emitted here because
     * the dispatcher never sees these calls; its correlationId is the tool call id — the
     * same per-call correlation the dispatcher's own audit events use), show the rejection
     * in the timeline, and return the stable typed rejection.
     */
    private fun persistRejectedToolCall(
        turn: com.helix.core.storage.entity.TurnEntity,
        toolCallId: String,
        toolNameRaw: String,
        rawArgs: String,
        version: String,
        code: DispatchOutcomeCode,
        detail: String,
    ): ToolDispatchOutcome.Denied {
        val startedAt = clock.now().toEpochMilli()
        val finishedAt = clock.now().toEpochMilli()
        storage.toolCalls.append(
            id = toolCallId,
            turnId = turn.id,
            callId = toolCallId,
            name = toolNameRaw,
            version = version,
            argsJson = rawArgs,
            state = ToolCallState.FAILED.name,
        )
        storage.toolResults.append(
            id = idGenerator(),
            toolCallId = toolCallId,
            status = "FAILED",
            summary = detail,
            content = null,
        )
        toolPipeline.auditSink.record(
            DispatchAuditEvent(
                correlationId = toolCallId,
                turnId = turn.id,
                sessionId = turn.sessionId,
                toolName = toolNameRaw,
                toolVersion = version,
                code = code,
                decisionSource = DecisionSource.FRAMEWORK,
                riskLevel = null,
                bindingHash = null,
                actionFingerprint = null,
                outputHash = null,
                outputTruncated = false,
                startedAt = startedAt,
                policyDecidedAt = null,
                approvalAcquiredAt = null,
                executionStartedAt = null,
                finishedAt = finishedAt,
            ),
        )
        publishToolRow(turn.id, toolCallId, toolNameRaw, rawArgs, "已拒绝", detail, null)
        return ToolDispatchOutcome.Denied(code, detail)
    }

    /**
     * Publishes (or replaces) the timeline row for one call. [card] = null PRESERVES the
     * row's current card: a settle (success / denial / failure) must not wipe the approval
     * card — a user-denied card stays visible in its terminal DENIED state, an approved
     * one in SUCCEEDED (the card is the record of the authorization decision).
     */
    private fun publishToolRow(
        turnId: String,
        callId: String,
        toolName: String,
        requestSummary: String,
        stateLabel: String,
        resultSummary: String?,
        card: com.helix.app.approval.ApprovalCardUi?,
    ) {
        val preserved =
            card ?: _screen.value.toolTimeline
                .firstOrNull { it.turnId == turnId && it.callId == callId }
                ?.card
        _screen.value =
            _screen.value.copy(
                toolTimeline =
                    _screen.value.toolTimeline
                        .filterNot { it.turnId == turnId && it.callId == callId }
                        .plus(
                            ToolTimelineRow(
                                turnId,
                                callId,
                                toolName,
                                requestSummary,
                                stateLabel,
                                resultSummary,
                                preserved,
                            ),
                        ),
            )
    }

    private fun attachCardToRow(
        callId: String,
        card: com.helix.app.approval.ApprovalCardUi,
    ) {
        _screen.value =
            _screen.value.copy(
                toolTimeline =
                    _screen.value.toolTimeline.map { row ->
                        if (row.callId == callId) {
                            row.copy(card = card, stateLabel = "待审批")
                        } else {
                            row
                        }
                    },
            )
    }

    private fun updateCard(
        approvalId: String,
        transform: (com.helix.app.approval.ApprovalCardUi) -> com.helix.app.approval.ApprovalCardUi,
    ) {
        _screen.value =
            _screen.value.copy(
                toolTimeline =
                    _screen.value.toolTimeline.map { row ->
                        val card = row.card ?: return@map row
                        if (card.approvalId != approvalId) return@map row
                        row.copy(card = transform(card))
                    },
            )
    }

    private fun setCardStateForCall(
        callId: String,
        state: ApprovalCardState,
        terminalDetail: String?,
        keepDenied: Boolean = false,
    ) {
        _screen.value =
            _screen.value.copy(
                toolTimeline =
                    _screen.value.toolTimeline.map { row ->
                        val card = row.card ?: return@map row
                        // Scoped to THIS call's row: timeline rows keep their terminal
                        // cards (a denied card stays visible), so an unscoped update would
                        // relabel older calls' cards with this call's outcome.
                        if (row.callId != callId) return@map row
                        // A user-denied card stays DENIED — a later framework rejection of
                        // the same call must not relabel the user's own decision.
                        if (keepDenied && card.state == ApprovalCardState.DENIED) return@map row
                        row.copy(card = card.copy(state = state, terminalDetail = terminalDetail))
                    },
            )
    }

    private fun boundedSummary(payload: String): String {
        if (payload.length <= SUMMARY_CAP) return payload
        return payload.take(SUMMARY_CAP) + "…"
    }

    // --------------------------------------------------------------------------------
    // Screen state
    // --------------------------------------------------------------------------------

    private fun refreshScreen() {
        val sessionId = openSessionId
        val screen = _screen.value
        val messages = messagesFor(sessionId, screen)
        val badge = sessionId?.let { badgeFor(it) } ?: screen.badge
        val lastTurn = sessionId?.let { id -> storage.turns.listBySession(id).lastOrNull() }
        val previous = _screen.value
        _screen.value =
            ChatScreenState(
                sessions = _sessions.value,
                openSessionId = sessionId,
                badge = badge,
                messages = messages,
                toolTimeline = toolTimelineFor(sessionId, previous.toolTimeline),
                activeTurn = lastTurn?.let { turnUiFor(it, previous.activeTurn?.streamingText) },
                pendingDisclosure = previous.pendingDisclosure,
                blockedReason = previous.blockedReason,
                retryTargetTurnId = retryTargetFor(sessionId),
            )
    }

    /**
     * The open session's tool timeline: the PERSISTED rows (tool_calls + tool_results,
     * every turn, newest session order) with the LIVE in-memory rows overlaid (the approval
     * card is a live display; its persisted identity is the approvals row). Bounded to the
     * newest [TOOL_TIMELINE_CAP] rows (doc 07 section 10: no unbounded list loads).
     */
    private fun toolTimelineFor(
        sessionId: String?,
        liveRows: List<ToolTimelineRow>,
    ): List<ToolTimelineRow> {
        if (sessionId == null) return emptyList()
        val persisted =
            storage.turns
                .listBySession(sessionId)
                .flatMap { turn ->
                    storage.toolCalls
                        .listByTurn(turn.id)
                        .map { call ->
                            val result = storage.toolResults.byToolCall(call.callId)
                            ToolTimelineRow(
                                turnId = turn.id,
                                callId = call.callId,
                                toolName = call.name,
                                requestSummary = call.argsJson,
                                stateLabel = persistedStateLabel(call.state),
                                resultSummary = result?.summary,
                                card = null,
                            )
                        }
                }.takeLast(TOOL_TIMELINE_CAP)
        return if (liveRows.isEmpty()) {
            persisted
        } else {
            val liveByCall = liveRows.associateBy { it.callId }
            persisted
                .map { row ->
                    val live = liveByCall[row.callId] ?: return@map row
                    row.copy(
                        card = live.card,
                        stateLabel = live.stateLabel,
                        resultSummary = live.resultSummary ?: row.resultSummary,
                    )
                }.plus(liveRows.filter { live -> persisted.none { it.callId == live.callId } })
        }
    }

    /** A persisted tool_call state as its user label (corrupt values fail closed). */
    private fun persistedStateLabel(state: String): String =
        when (runCatching { ToolCallState.valueOf(state) }.getOrNull()) {
            ToolCallState.PENDING -> "处理中"
            ToolCallState.AWAITING_APPROVAL -> "待审批"
            ToolCallState.RUNNING -> "执行中"
            ToolCallState.NEEDS_REVIEW -> "待复核"
            ToolCallState.INTERRUPTED -> "已中断（待恢复）"
            ToolCallState.COMPLETED -> "已执行成功"
            ToolCallState.FAILED -> "执行失败"
            ToolCallState.CANCELLED -> "已取消"
            ToolCallState.DENIED -> "已拒绝"
            null -> "未知状态"
        }

    /** The open session's persisted messages as UI rows (blank assistant rows drop out). */
    private fun messagesFor(
        sessionId: String?,
        screen: ChatScreenState,
    ): List<MessageUi> {
        if (sessionId == null) return screen.messages
        return storage.messages
            .listBySession(sessionId)
            .mapNotNull { entity ->
                val content = storage.messages.readContent(entity)
                if (content.isNullOrBlank() && entity.role != ModelRole.USER.name) {
                    null
                } else {
                    MessageUi(entity.id, entity.role.lowercase(), content.orEmpty())
                }
            }
    }

    /**
     * The persisted last turn as the active-turn UI state. Corrupt enum
     * values fail closed to the conservative reading (FAILED / 请求失败) —
     * a stored row must never be shown as a healthy in-flight turn.
     */
    @Suppress("SwallowedException") // corrupt stored enum: the conservative fallback IS the handling
    private fun turnUiFor(
        entity: TurnEntity,
        previousStreamingText: String?,
    ): TurnUi {
        val state =
            try {
                TurnState.valueOf(entity.state)
            } catch (e: IllegalArgumentException) {
                TurnState.FAILED
            }
        return TurnUi(
            id = entity.id,
            state = state,
            streamingText = if (state.isTerminal) null else previousStreamingText,
            errorLabel =
                entity.errorCode?.let { code ->
                    try {
                        ConnectionTestMapping.codeLabel(ModelErrorCode.valueOf(code))
                    } catch (e: IllegalArgumentException) {
                        "请求失败"
                    }
                },
            retryable = state == TurnState.FAILED,
        )
    }

    /** The newest FAILED turn of the open session — the retry button's target (persisted state). */
    private fun retryTargetFor(sessionId: String?): String? =
        sessionId
            ?.let { id ->
                storage.turns
                    .listBySession(id)
                    .lastOrNull { it.state == TurnState.FAILED.name }
                    ?.id
            }

    private fun badgeFor(sessionId: String): ProviderBadgeUi? {
        val session = storage.sessions.resolve(sessionId)
        val row = session.providerId?.let { pid -> providerService.rows.value.firstOrNull { it.id == pid } }
        return row?.let {
            ProviderBadgeUi(it.displayName, it.model, it.origin, it.residence, it.capabilityChips)
        }
    }

    private fun publishTurn(turn: TurnUi) {
        _screen.value = _screen.value.copy(activeTurn = turn)
    }

    private fun setBlocked(reason: String) {
        _screen.value = _screen.value.copy(blockedReason = reason)
    }

    private fun currentSession() = openSessionId?.let { storage.sessions.resolve(it) }

    private fun sessionProviderId(sessionId: String): String {
        val id = storage.sessions.resolve(sessionId).providerId
        require(id != null) { "session has no provider" }
        return id
    }

    private suspend fun providerSnapshot(providerId: String): String {
        val c = providerService.storedConfig(providerId)
        // The snapshot is an informational, model-call-bound JSON column. The
        // three values are user-supplied (displayName especially may hold a
        // quote), so escape before interpolation — never build JSON by raw
        // string concatenation of untrusted text.
        return buildString {
            append("{\"displayName\":\"")
            append(jsonEscape(c.displayName))
            append("\",\"endpoint\":\"")
            append(jsonEscape(c.endpoint.full))
            append("\",\"model\":\"")
            append(jsonEscape(c.model))
            append("\"}")
        }
    }

    private fun jsonEscape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /** Encodes a [ModelEvent.Usage] for the model_calls.usage column (nulls stay null — never 0). */
    private fun usageToJson(usage: ModelEvent.Usage): String? {
        val input = usage.inputTokens
        val output = usage.outputTokens
        if (input == null && output == null) return null
        val sb = StringBuilder("{")
        if (input != null) sb.append("\"inputTokens\":").append(input)
        if (input != null && output != null) sb.append(",")
        if (output != null) sb.append("\"outputTokens\":").append(output)
        sb.append("}")
        return sb.toString()
    }

    private fun callState(turn: TurnState): String =
        when (turn) {
            TurnState.COMPLETED -> CALL_COMPLETED
            TurnState.CANCELLED -> CALL_CANCELLED
            else -> CALL_FAILED
        }

    private companion object {
        const val TAG = "HelixChat"
        const val SUMMARY_CAP = 500
        const val TOOL_TIMELINE_CAP = 200
        const val KIND_TEXT = "TEXT"
        const val CALL_RUNNING = "RUNNING"
        const val CALL_COMPLETED = "COMPLETED"
        const val CALL_CANCELLED = "CANCELLED"
        const val CALL_FAILED = "FAILED"
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
            )
    }
}

package com.helix.app.chat

import android.util.Log
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ProviderBadgeUi
import com.helix.app.provider.ProviderService
import com.helix.core.model.Clock
import com.helix.core.model.ErrorCode
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity
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
 * the in-flight turn, the gates) by design — splitting it across several
 * services would put the invariants (one active turn per session, the
 * gate→turn hand-off) across objects.
 */
@Suppress("TooManyFunctions")
class ChatService(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    profileStore: SafetyProfileStore,
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

    /** The stop button: cancels the in-flight turn (service-owned Job). */
    fun stop() {
        activeTurnJob?.cancel()
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
            terminalize(turnId, callId, sessionId, acc, terminalDecision(acc))
        } catch (e: CancellationException) {
            terminalize(turnId, callId, sessionId, acc, TerminalOutcome(TurnState.CANCELLED, null, null))
            throw e
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

            // M2 chat UI does not render reasoning or tool events
            is ModelEvent.ReasoningDelta,
            is ModelEvent.ToolCallStarted,
            is ModelEvent.ToolArgumentsDelta,
            is ModelEvent.ToolCallFinished,
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
                activeTurn = lastTurn?.let { turnUiFor(it, previous.activeTurn?.streamingText) },
                pendingDisclosure = previous.pendingDisclosure,
                blockedReason = previous.blockedReason,
                retryTargetTurnId = retryTargetFor(sessionId),
            )
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
                activeTurn = null,
                pendingDisclosure = null,
                blockedReason = null,
                retryTargetTurnId = null,
            )
    }
}

package com.helix.app.chat

import android.util.Log
import com.helix.app.R
import com.helix.app.approval.ApprovalCancelledException
import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalUiMapper
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ProviderBadgeUi
import com.helix.app.provider.ProviderService
import com.helix.app.tool.ToolPipeline
import com.helix.core.model.AgentMode
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ArtifactRef
import com.helix.core.model.AttachmentClassification
import com.helix.core.model.AttachmentPurpose
import com.helix.core.model.Clock
import com.helix.core.model.ErrorCode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SystemClock
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnState
import com.helix.core.model.VisionLimits
import com.helix.core.policy.DataOrigin
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.repository.MessageAttachmentRepository
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import com.helix.feature.files.AttachmentClassifier
import com.helix.feature.files.AttachmentImportResult
import com.helix.feature.files.AttachmentMaterialization
import com.helix.feature.files.AttachmentSendDecision
import com.helix.feature.files.AttachmentSendGate
import com.helix.feature.files.ImageNormalizer
import com.helix.feature.files.ImportRefusal
import com.helix.feature.files.ImportStatus
import com.helix.feature.files.NormalizationCode
import com.helix.feature.files.NormalizationOutcome
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.StagedAttachment
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.CanonicalArgs
import com.helix.tools.framework.DecisionSource
import com.helix.tools.framework.DispatchAuditEvent
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolDispatchRequest
import com.helix.tools.framework.ToolScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
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
 * The class-level suppressions record that single-owner decision; LongParameterList
 * covers the primary constructor, whose parameters are each a distinct injected seam
 * (HXA-069 added the pure-JVM `strings` locale resolver — ChatService has no Android
 * Context to read string resources itself, so the resolver must be injected).
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
class ChatService(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    profileStore: SafetyProfileStore,
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
) {
    private val workScope = scope

    /** Resolves a string-resource id (+ optional format args) to the current locale (HXA-069). */
    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    /**
     * The localized label for a terminal turn, or null when the terminal carries none (a clean
     * COMPLETED). CANCELLED is the fixed stop label; FAILED resolves [errorCode] via
     * [modelTerminalCodeRes].
     */
    private fun terminalLabel(
        state: TurnState,
        errorCode: String?,
    ): String? =
        when (state) {
            TurnState.FAILED -> str(modelTerminalCodeRes(errorCode))
            TurnState.CANCELLED -> str(R.string.turn_stopped)
            else -> null
        }

    /** Maps a terminal turn's stable error code to its user-visible string-resource id. */
    private fun modelTerminalCodeRes(errorCode: String?): Int =
        when (errorCode) {
            ModelStreamState.REFUSAL -> R.string.model_refused
            ModelStreamState.TOOL_STREAM_TRUNCATED -> R.string.model_error_tool_stream_truncated
            ModelStreamState.TOOL_STREAM_INVALID -> R.string.model_error_tool_stream_invalid
            ModelStreamState.TOOL_ARGUMENTS_OVERFLOW -> R.string.model_error_tool_args_overflow
            ModelStreamState.TOOL_CALL_COUNT_OVERFLOW -> R.string.model_error_tool_call_count_overflow
            ModelStreamState.MODEL_TEXT_OVERFLOW -> R.string.model_error_model_text_overflow
            "TOOL_STEP_LIMIT" -> R.string.model_error_tool_step_limit
            null -> R.string.model_error_generic
            else -> modelErrorCodeLabelRes(errorCode)
        }

    /** Maps a persisted provider [ModelErrorCode] name to its string-resource id (fail-closed). */
    @Suppress("SwallowedException") // unknown code: the conservative generic label IS the handling
    private fun modelErrorCodeLabelRes(code: String): Int =
        try {
            when (ModelErrorCode.valueOf(code)) {
                ModelErrorCode.TRANSPORT -> R.string.conn_error_transport
                ModelErrorCode.TIMEOUT -> R.string.conn_error_timeout
                ModelErrorCode.AUTH -> R.string.conn_error_auth
                ModelErrorCode.RATE_LIMITED -> R.string.conn_error_rate_limited
                ModelErrorCode.SERVER_ERROR -> R.string.conn_error_server
                ModelErrorCode.HTTP_ERROR -> R.string.conn_error_http
                ModelErrorCode.PROTOCOL -> R.string.conn_error_protocol
                ModelErrorCode.CONTENT_FILTER -> R.string.conn_error_content_filter
            }
        } catch (e: IllegalArgumentException) {
            R.string.model_error_generic
        }

    /** The localized label for an egress rejection's stable code (never the matched content). */
    private fun egressRejectedLabel(code: String): String =
        if (code == ForbiddenContentGuard.CREDENTIAL_DETECTED) {
            str(R.string.egress_credential_rejected)
        } else {
            str(R.string.model_error_generic)
        }

    private val _sessions = MutableStateFlow<List<SessionRowUi>>(emptyList())
    private val _screen = MutableStateFlow(EMPTY_SCREEN)

    val sessions: StateFlow<List<SessionRowUi>> = _sessions.asStateFlow()
    val screen: StateFlow<ChatScreenState> = _screen.asStateFlow()

    /** The runtime safety profile for the chat header (ADR-0005 display). */
    val profile: StateFlow<SafetyProfile> = profileStore.flow

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

    /** The in-memory facts of one staged attachment (internal; the UI sees [PendingAttachmentUi]). */
    @Suppress("LongParameterList") // one distinct staged fact per parameter (raw + HXA-055 normalized facts)
    private class StagedAttachmentEntry(
        /** The session that staged this entry — an in-flight switch/close drops it (ADR-0014 §5). */
        val sessionId: String,
        val artifactId: String,
        val fileName: String,
        val sizeBytes: Long,
        val boundSha256: String,
        /** The scope-relative workspace path the model chunk-reads the full content through. */
        val relativePath: String,
        /** The real workspace path — hashing/probing only, never exposed. */
        val file: Path,
        /** HXA-055 image facts (null/0 for text): the registered id of the NORMALIZED artifact. */
        val normalizedArtifactId: String? = null,
        /** The bound SHA-256 of the normalized artifact (re-verified at send, retry and restore). */
        val normalizedSha256: String? = null,
        /** The real path of the normalized artifact — hashing only, never exposed. */
        val normalizedFile: Path? = null,
        val normalizedWidth: Int = 0,
        val normalizedHeight: Int = 0,
        val normalizedMediaType: String? = null,
        /**
         * Set when the on-device normalization FAILED at staging (HXA-055, ADR-0014 §4): the raw
         * artifact stays local (save/preview still possible) but the send is blocked with this
         * actionable, user-visible reason — never a raw-base64 fallback.
         */
        val imageSendError: String? = null,
    )

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
        openSessionId = null
        clearStagedAttachments()
        shareDraftText = null
        workScope.launch { refreshScreen() }
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
     * Accepts a share draft from the system share sheet (HXA-056, ADR-0014 §5): creates a
     * dedicated provider-free draft session, opens it, and lands the shared content LOCALLY
     * — [text] becomes the one-shot composer pre-fill ([ChatScreenState.shareDraftText])
     * and every [imageUris] reference goes through the EXISTING attachment pipeline
     * (import → closed classification → normalize → stage). Nothing is ever sent: staging
     * and pre-filling are local, and only an explicit [send] after the user's review reaches
     * the model. An empty draft is a no-op. Each image fails closed individually: one bad
     * share item blocks only itself (user-visible reason), the rest still stage.
     *
     * Runs on the work scope; the UI follows [screen] (the draft session opens and its
     * staged attachments appear as they import). Called by the activity for launch intents
     * and `onNewIntent` re-shares.
     */
    fun acceptShareDraft(
        text: String?,
        imageUris: List<String>,
    ) {
        if ((text?.isEmpty() ?: true) && imageUris.isEmpty()) return
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
            imageUris.forEach { uri -> stageAttachmentNow(uri) }
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
        if (stagedAttachments.size >= AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE) {
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

    /**
     * Completes staging for a COMPLETED import (fail closed at every step): the file must
     * classify as a first-batch UTF-8 text attachment (unsupported types are surfaced and
     * NOT staged), its snapshot must be complete, and the artifact row must register
     * (`message_attachments.artifactId` is an FK to `artifacts`; the register re-verifies
     * the durable bytes — hash and size). The resolved real path stays service-internal.
     */
    @Suppress(
        "ReturnCount",
        "SwallowedException",
        "TooGenericExceptionCaught",
        "LongMethod",
        "CyclomaticComplexMethod",
    ) // one fail-closed return per staging step; the HXA-055 image branch adds its closed failure ladder
    private fun stageImportedAttachment(
        result: AttachmentImportResult,
        sessionId: String,
    ) {
        val fileName = result.fileName.orEmpty()
        val classification = result.classification
        if (classification !is AttachmentClassification.TextAttachment &&
            classification !is AttachmentClassification.ImageAttachment
        ) {
            // The classifier re-derived this from the durable bytes; unsupported types are
            // never parsed/decoded/rendered — the user is told and the file is not staged.
            setBlocked(str(R.string.chat_blocked_unsupported_type, fileName))
            return
        }
        val sha = result.sha256
        if (sha == null || result.sizeBytes < 0) {
            setBlocked(str(R.string.chat_blocked_snapshot_incomplete))
            return
        }
        val scopePath =
            try {
                FileScopePath.fromModelReference(result.modelRef.orEmpty())
            } catch (e: IllegalArgumentException) {
                setBlocked(str(R.string.chat_blocked_path_check_failed))
                return
            }
        val realPath =
            try {
                attachmentStaging.resolveWorkspacePath(scopePath)
            } catch (e: RuntimeException) {
                // The scope vanished or the path escaped containment: fail closed. The
                // exception is not logged — it can carry a real path, which never may be.
                setBlocked(str(R.string.chat_blocked_workspace_path_unavailable))
                return
            }
        val artifactId =
            try {
                storage.artifacts
                    .register(
                        id = "art_" + idGenerator(),
                        sessionId = sessionId,
                        relativePath = scopePath.relativePath,
                        mediaType =
                            when (classification) {
                                is AttachmentClassification.ImageAttachment -> classification.mediaType
                                else -> result.mimeType ?: "text/plain"
                            },
                        size = result.sizeBytes,
                        sha256 = sha,
                        file = realPath.toFile(),
                    ).id
            } catch (e: IllegalArgumentException) {
                // A failed registration means the durable file no longer verifies against
                // its snapshot — do not leave a reference the user could never send.
                deleteQuietly(realPath)
                setBlocked(str(R.string.chat_blocked_register_failed))
                return
            }
        // HXA-055 (ADR-0014 §4): a staged image is normalized ON-DEVICE right here —
        // decode within VisionLimits, manual EXIF orientation, re-encode (the EXIF strip),
        // and the size-budget ladder — so the send path only ever moves verified, bounded
        // bytes. A normalization failure does NOT drop the attachment: the raw artifact stays
        // local (save/preview possible) and the entry carries an actionable, user-visible
        // send error (fail closed — never a raw-base64 fallback).
        val normalized =
            if (classification is AttachmentClassification.ImageAttachment) {
                normalizeStagedImage(realPath, scopePath, sessionId, classification.mediaType)
            } else {
                null
            }
        // In-lock admission (ADR-0014 §5): the pending list is local to the session that
        // staged it and the cap is re-checked inside — two concurrent stages cannot race past it.
        if (
            admitStagedEntry(
                sessionId = sessionId,
                fileName = fileName,
                result = result,
                sha = sha,
                scopePath = scopePath,
                realPath = realPath,
                artifactId = artifactId,
                normalizedArtifactId = normalized?.id,
                normalizedSha256 = normalized?.sha256,
                normalizedFile = normalized?.file,
                normalizedWidth = normalized?.width ?: 0,
                normalizedHeight = normalized?.height ?: 0,
                normalizedMediaType = normalized?.mediaType,
                imageSendError = normalized?.failureReason,
            )
        ) {
            refreshScreen()
        }
    }

    /** The registered facts of one successfully normalized staged image (all nulls + a reason on failure). */
    private class NormalizedStagedImage(
        val id: String?,
        val sha256: String?,
        val file: Path?,
        val width: Int,
        val height: Int,
        val mediaType: String?,
        val failureReason: String?,
    )

    /**
     * Normalizes one staged image and registers the result as a SECOND, app-private artifact
     * in the same staging directory (`normalized.<ext>`). The raw artifact stays registered —
     * it is the local save/preview source; only the normalized artifact is ever sendable.
     */
    @Suppress(
        "ReturnCount",
        "SwallowedException",
        "TooGenericExceptionCaught",
    ) // every failure step maps to the closed NormalizationCode path
    private fun normalizeStagedImage(
        rawFile: Path,
        scopePath: FileScopePath,
        sessionId: String,
        rawMediaType: String,
    ): NormalizedStagedImage {
        val stagingDir = rawFile.parent ?: return failedStagedNormalization()
        val outcome =
            try {
                ImageNormalizer.normalize(rawFile, rawMediaType, stagingDir)
            } catch (e: Exception) {
                // A crash in the decode path (not a caught OOM) is the same closed outcome:
                // fail the normalization, keep the raw file, never crash the app.
                NormalizationOutcome.Failed(NormalizationCode.DECODE_FAILED, "unexpected")
            }
        val ok = outcome as? NormalizationOutcome.Ok ?: return failedStagedNormalization()
        val normalizedPath = ok.image.file
        if (!Files.exists(normalizedPath) || !Files.isRegularFile(normalizedPath)) {
            return failedStagedNormalization()
        }
        // The normalizer wrote `normalized.<ext>` into the RAW file's staging directory —
        // derive the scope-relative path from the raw one (same dir, fixed file name).
        val dirRel = scopePath.relativePath.substringBeforeLast('/')
        val ext = normalizedPath.fileName?.toString()?.substringAfterLast('.', missingDelimiterValue = "") ?: ""
        val normalizedRelative =
            runCatching {
                FileScopePath(scopePath.scopeId, "$dirRel/normalized.$ext")
            }.getOrNull()
        if (normalizedRelative == null || ext.isEmpty()) {
            deleteQuietly(normalizedPath)
            return failedStagedNormalization()
        }
        // Containment: the registered path must resolve to EXACTLY the file the normalizer
        // wrote — never a stray file the scope would also accept (fail closed).
        val resolved =
            runCatching { attachmentStaging.resolveWorkspacePath(normalizedRelative) }.getOrNull()
        if (resolved?.toFile()?.canonicalFile != normalizedPath.toFile().canonicalFile) {
            deleteQuietly(normalizedPath)
            return failedStagedNormalization()
        }
        val id =
            try {
                storage.artifacts
                    .register(
                        id = "art_" + idGenerator(),
                        sessionId = sessionId,
                        relativePath = normalizedRelative.relativePath,
                        mediaType = ok.image.mediaType,
                        size = ok.image.sizeBytes,
                        sha256 = ok.image.sha256,
                        file = normalizedPath.toFile(),
                    ).id
            } catch (e: IllegalArgumentException) {
                // The register re-verifies the bytes; a mismatch deletes nothing (the row is
                // absent) and the normalization is treated as failed (fail closed).
                return failedStagedNormalization()
            }
        return NormalizedStagedImage(
            id,
            ok.image.sha256,
            normalizedPath,
            ok.image.width,
            ok.image.height,
            ok.image.mediaType,
            null,
        )
    }

    /** The closed, fail-closed staging outcome of a failed image normalization (HXA-055). */
    private fun failedStagedNormalization() =
        NormalizedStagedImage(null, null, null, 0, 0, null, imageNormalizationBlockText())

    /** The fixed, user-visible (Chinese) send block for a failed image normalization (HXA-055). */
    private fun imageNormalizationBlockText(): String =
        str(R.string.chat_capability_image_normalization_failed, VisionLimits.MAX_EDGE_PX)

    /**
     * The authoritative, in-lock append of one staged attachment: it appends only when the
     * session has NOT switched in flight and the per-message cap has not been exceeded — either
     * failure sets the blocked state and appends NOTHING (the durable artifact row stays, inert).
     * Returns true when the entry was appended.
     */
    @Suppress("LongParameterList") // each parameter is one staged fact (raw + HXA-055 normalized image facts)
    private fun admitStagedEntry(
        sessionId: String,
        fileName: String,
        result: AttachmentImportResult,
        sha: String,
        scopePath: FileScopePath,
        realPath: Path,
        artifactId: String,
        normalizedArtifactId: String? = null,
        normalizedSha256: String? = null,
        normalizedFile: Path? = null,
        normalizedWidth: Int = 0,
        normalizedHeight: Int = 0,
        normalizedMediaType: String? = null,
        imageSendError: String? = null,
    ): Boolean {
        var admitted = false
        synchronized(stagedLock) {
            if (openSessionId == sessionId) {
                if (stagedAttachments.size >= AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE) {
                    setBlocked(
                        str(R.string.chat_blocked_max_attachments, AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE),
                    )
                } else {
                    stagedAttachments =
                        stagedAttachments +
                        StagedAttachmentEntry(
                            sessionId = sessionId,
                            artifactId = artifactId,
                            fileName = fileName,
                            sizeBytes = result.sizeBytes,
                            boundSha256 = sha,
                            relativePath = scopePath.relativePath,
                            file = realPath,
                            normalizedArtifactId = normalizedArtifactId,
                            normalizedSha256 = normalizedSha256,
                            normalizedFile = normalizedFile,
                            normalizedWidth = normalizedWidth,
                            normalizedHeight = normalizedHeight,
                            normalizedMediaType = normalizedMediaType,
                            imageSendError = imageSendError,
                        )
                    admitted = true
                }
            }
        }
        return admitted
    }

    /** Best-effort delete of a file whose staging failed (an unreferenced orphan otherwise). */
    private fun deleteQuietly(path: Path) {
        runCatching { Files.deleteIfExists(path) }
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
            synchronized(stagedLock) {
                stagedAttachments = stagedAttachments.filterNot { it.artifactId == id }
            }
            refreshScreen()
        }
    }

    // --------------------------------------------------------------------------------
    // Send path
    // --------------------------------------------------------------------------------

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
    fun send(text: String) {
        workScope.launch { sendNow(text) }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one fail-closed early return per gate condition
    private suspend fun sendNow(text: String) {
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
                applyEgressDecision(outcome.decision, staged, text, providerId, target)
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
                launchTurn(text, providerId)
            }

            is EgressDisclosure.Decision.Confirm -> {
                // Bind the pending confirmation to BOTH the exact target the dialog shows AND the
                // exact attachment set it enumerated (ADR-0014 §5): confirmSendNow re-derives the
                // live target (blocking on provider/origin drift) and requires the current staged
                // set to be IDENTICAL to [staged] — an attachment never shown in the dialog can
                // never leave the device; an old confirmation is never reusable.
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
        // Capture the approved target AND attachment set BEFORE clearing the pending state: the
        // binding checks below compare the LIVE target and the CURRENT staged set against exactly
        // what the dialog showed.
        val approvedTarget = pendingEgress
        val approvedAttachmentIds = pendingAttachmentIds
        val session = currentSession() ?: return
        val providerId = session.providerId ?: return
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
        confirmStagedSend(text, providerId, approvedAttachmentIds, liveTarget)
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
            launchTurn(text, providerId)
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
        launchTurn(
            text = AttachmentContext.buildUserMessageContent(text, blocks),
            providerId = providerId,
            attachmentBindings = bindings,
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
    fun stop() {
        activePendingApprovalId?.let { toolPipeline.broker.cancel(it) }
        val sessionId = openSessionId ?: return
        val active = sessionTurnAdmission.activeTurn(sessionId) ?: return
        turnCancels[active.turnId]?.cancel()
        active.job.cancel()
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
                    updateCard(approvalId) {
                        it.copy(
                            state = ApprovalCardState.DENIED,
                            terminalDetail = str(R.string.approval_terminal_user_denied),
                        )
                    }
                }
            } catch (e: Exception) {
                // A stale card (the record was already decided or the id is unknown): the
                // repository's one-time guard throws — surface a stable card error, never a
                // crash, never a silent no-op (the user's tap must be visible as handled).
                Log.e(TAG, "approval $approvalId could not be decided", e)
                updateCard(approvalId) {
                    it.copy(
                        state = ApprovalCardState.FAILED,
                        terminalDetail = str(R.string.approval_terminal_op_failed),
                    )
                }
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
                stateLabel = str(R.string.tool_state_awaiting_approval),
                resultSummary = null,
                card = card,
            )
        }
    }

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
            when (val stagedCheck = retryStagedFor(session.id, turnId)) {
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
            launchTurn(text = null, providerId = providerId, retryTurnId = turnId)
        }
    }

    /**
     * The retried turn's bound attachments, resolved for the gate's re-verification
     * (ADR-0014 §5): [RetryStagedCheck.None] when the turn has NO bound attachment (the
     * pure-text retry — launchTurn behaves exactly as before); [RetryStagedCheck.Staged]
     * with the bound files in the message's binding (ordinal/staged) order;
     * [RetryStagedCheck.Unavailable] when a bound artifact can no longer be resolved
     * (row vanished, workspace path unresolvable) — the caller blocks fail-closed. The
     * real paths cross out of this function NEVER (they exist for hashing/probing only).
     */
    private sealed interface RetryStagedCheck {
        /** The retried turn has no bound attachments. */
        data object None : RetryStagedCheck

        /** The bound files, in the message's binding (ordinal/staged) order. */
        data class Staged(
            val attachments: List<StagedAttachment>,
        ) : RetryStagedCheck

        /** A bound artifact can no longer be resolved — the retry must be blocked. */
        data object Unavailable : RetryStagedCheck
    }

    @Suppress("SwallowedException", "TooGenericExceptionCaught") // ANY resolution failure = Unavailable
    private suspend fun retryStagedFor(sessionId: String, turnId: String): RetryStagedCheck =
        try {
            val messageId =
                storage.messages
                    .listBySession(sessionId)
                    .firstOrNull { it.turnId == turnId && it.role == ModelRole.USER.name }
                    ?.id
            val bindings = messageId?.let { storage.messageAttachments.listByMessage(it) }
            if (bindings.isNullOrEmpty()) {
                RetryStagedCheck.None
            } else {
                RetryStagedCheck.Staged(
                    bindings.map { binding ->
                        val artifact = storage.artifacts.resolve(binding.artifactId)
                        val scopePath =
                            FileScopePath(attachmentStaging.workspaceScopeId, artifact.relativePath)
                        val file = attachmentStaging.resolveWorkspacePath(scopePath)
                        // HXA-055: an image binding points at the NORMALIZED artifact (the
                        // bytes that leave) — the retry re-verifies exactly that file, twice
                        // (as `file` and as `normalizedFile`; the raw artifact is local-only
                        // and no longer part of the binding). Dimensions are unknown at retry
                        // (not persisted) and are 0 — the gate does not need them to re-verify.
                        val isImage = artifact.mediaType in VisionLimits.NORMALIZED_MEDIA_TYPES
                        StagedAttachment(
                            fileName = scopePath.name,
                            boundSha256 = binding.boundSha256,
                            file = file,
                            normalizedFile = if (isImage) file else null,
                            normalizedSha256 = if (isImage) binding.boundSha256 else null,
                            mediaType = if (isImage) artifact.mediaType else null,
                            normalizedWidth = 0,
                            normalizedHeight = 0,
                        )
                    },
                )
            }
        } catch (_: Exception) {
            // The exception can carry a real path or a corrupt row — it is NOT logged;
            // the caller blocks the retry fail-closed.
            RetryStagedCheck.Unavailable
        }

    // --------------------------------------------------------------------------------
    // Turn execution (service-owned; the UI only observes)
    // --------------------------------------------------------------------------------

    @Suppress("ReturnCount") // one fail-closed return per guard (session, snapshot, turn gate)
    private suspend fun launchTurn(
        text: String?,
        providerId: String,
        retryTurnId: String? = null,
        attachmentBindings: List<MessageAttachmentRepository.Binding> = emptyList(),
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
                setBlocked(str(R.string.chat_blocked_provider_state_changed))
                return
            }
        synchronized(turnGate) {
            // Per-session admission: refuse only when THIS session already has an in-flight turn —
            // a turn in another session must never make this send vanish.
            if (sessionTurnAdmission.hasActive(sessionId)) return
            val turnId = idGenerator()
            val callId = idGenerator()
            val coordinator =
                TurnCoordinator.start(
                    storage = storage,
                    clock = clock,
                    idGenerator = idGenerator,
                    spec = TurnStartSpec(sessionId, turnId, callId, snapshot, text, attachmentBindings),
                )
            val job =
                workScope.launch {
                    runTurn(sessionId, coordinator, providerId, retryTurnId)
                }
            sessionTurnAdmission.register(sessionId, job, turnId)
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
        coordinator: TurnCoordinator,
        providerId: String,
        retryTurnId: String?,
    ) {
        val turnId = coordinator.id
        try {
            val decision = runToolLoop(sessionId, coordinator, providerId, retryTurnId)
            terminalize(coordinator, decision)
        } catch (e: CancellationException) {
            terminalize(coordinator, ModelStreamTerminal(TurnState.CANCELLED, null))
            throw e
        } catch (e: ApprovalCancelledException) {
            // The user stopped the turn while the approval card was pending: the turn
            // terminalizes as CANCELLED; the approval record stays PENDING (no decision
            // was made) and expires with its window. The job completes normally — the
            // cancellation was the user's own action, not a failure. (The exception
            // carries only the approval id — safe to log as metadata.)
            Log.i(TAG, "turn $turnId stopped while awaiting approval: ${e.message}")
            terminalize(coordinator, ModelStreamTerminal(TurnState.CANCELLED, null))
        } catch (e: Exception) {
            // An unexpected boundary failure (a guard reject, corrupt rows, a
            // provider row deleted mid-turn): the turn STILL reaches a
            // terminal state with a safe label — never a stuck "sending" UI
            // (doc 02 section 13: raw messages are never shown).
            Log.e(TAG, "turn $turnId failed at the model boundary", e)
            terminalize(
                coordinator,
                ModelStreamTerminal(TurnState.FAILED, ErrorCode.INTERNAL.name),
            )
        }
    }

    /**
     * The multi-step tool loop (roadmap HXA-037; doc 11 sections 3/5): model step →
     * (bounded-parallel) tool round → results settled IN CALL SEQUENCE → persisted
     * (`model-visible ⇔ persisted`) → back-filled into the next model request → repeat
     * until the model stops calling tools, a step fails, the user stops, or the turn's
     * tool-round budget is exhausted (fail closed).
     *
     * Every model step gets its own `model_calls` row; every tool call gets its durable
     * outcome through the dispatcher (cancel/recovery invariants — doc 11 section 7).
     */
    @Suppress("ReturnCount") // one early return per terminal condition of the loop (cancel / non-completed / budget)
    private suspend fun runToolLoop(
        sessionId: String,
        coordinator: TurnCoordinator,
        providerId: String,
        retryTurnId: String?,
    ): ModelStreamTerminal {
        val turnId = coordinator.id
        val provider = providerService.modelProviderFor(providerId)
        var request = buildRequest(sessionId, retryTurnId)
        var toolRounds = 0
        while (true) {
            if (turnCancels[turnId]?.isCancelled() == true) {
                return ModelStreamTerminal(TurnState.CANCELLED, null)
            }
            val acc = coordinator.beginModelStream()
            provider.stream(request).collect { event ->
                applyEvent(event, acc, turnId)
            }
            val decision = acc.terminal(turnCancels[turnId]?.isCancelled() == true)
            if (decision.state == TurnState.COMPLETED) {
                val toolRound =
                    runToolRound(
                        coordinator,
                        acc,
                        toolRounds,
                    )
                if (toolRound is ToolRoundLimit) {
                    // The turn's tool-round budget is exhausted: fail closed.
                    return ModelStreamTerminal(TurnState.FAILED, "TOOL_STEP_LIMIT")
                }
                if (toolRound is ToolRoundContinued) {
                    toolRounds = toolRound.toolRounds
                    request = buildBackfillRequest(sessionId)
                    continue
                }
            }
            // The loop is terminal: the turn is cancelled or failed, or the model gave a
            // final answer (no more tool calls). A continued round already advanced the
            // loop state above.
            return decision
        }
    }

    /** The one tool round of [runToolLoop]: continued, budget-limited, or none (no finished calls). */
    private sealed class ToolRoundResult

    private class ToolRoundContinued(
        val toolRounds: Int,
    ) : ToolRoundResult()

    private class ToolRoundLimit : ToolRoundResult()

    /**
     * Runs ONE tool round when the decision is COMPLETED with finished tool calls: closes
     * the model step's row, persists the assistant's tool-call step, runs the batch
     * (bounded parallel execution, deterministic call-order settlement), persists the
     * results in the SAME call sequence, and opens the next model step's row.
     * [ToolRoundLimit] when the turn's tool-round budget is exhausted (fail closed — the
     * turn ends FAILED with the safe label rather than silently truncating the work);
     * null when there is no finished call to run (the model's final answer).
     */
    @Suppress("ReturnCount") // one early return per guard (no finished call / budget limit)
    private suspend fun runToolRound(
        coordinator: TurnCoordinator,
        acc: ModelStreamState,
        toolRounds: Int,
    ): ToolRoundResult? {
        val turnId = coordinator.id
        val calls = acc.finishedToolCalls
        if (calls.isEmpty()) return null
        if (toolRounds >= MAX_TOOL_ROUNDS_PER_TURN) return ToolRoundLimit()
        coordinator.beginToolBatch(calls.map { it.callId })
        coordinator.commitModelToolStep(assistantToolStepJson(calls))
        val turn = storage.turns.resolve(turnId)
        val settled = runToolBatch(turn, turnId, calls, coordinator)
        val nextCallId = idGenerator()
        coordinator.openNextModelCall(settled.map(::toolResultDraft), nextCallId)
        return ToolRoundContinued(toolRounds + 1)
    }

    /**
     * One stream event → pure stream state → application effects. Reasoning/tool events
     * are accumulated but not rendered; tool calls enter the timeline only through the
     * dispatcher path.
     */
    private fun applyEvent(
        event: com.helix.core.model.ModelEvent,
        acc: ModelStreamState,
        turnId: String,
    ) {
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
        dispatchFacts.values.removeIf { it.turnId == turnId }
        activePendingApprovalId = null
        terminalLabel(outcome.state, outcome.errorCode)?.let { label ->
            publishTurn(
                TurnUi(turnId, outcome.state, null, label, outcome.state == TurnState.FAILED),
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
        val history = persistedHistory(sessionId, retryTurnId)
        require(history.lastOrNull()?.role == ModelRole.USER) {
            "the request must end with the user message"
        }
        val config = providerService.storedConfig(sessionProviderId(sessionId))
        visionSessionBinder(sessionId)
        return ModelRequest(model = config.model, messages = history, maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS)
    }

    /**
     * The next model request of a tool loop (roadmap HXA-037 back-fill): the FULL
     * persisted history, which now ends with the just-settled TOOL result rows —
     * `model-visible ⇔ persisted`: every message the model sees was persisted FIRST
     * (doc 11 section 4: no model-visible input without a persisted event).
     */
    private suspend fun buildBackfillRequest(sessionId: String): ModelRequest {
        val history = persistedHistory(sessionId, null)
        require(history.lastOrNull()?.role == ModelRole.TOOL) {
            "a back-fill request must end with the tool results"
        }
        val config = providerService.storedConfig(sessionProviderId(sessionId))
        visionSessionBinder(sessionId)
        return ModelRequest(model = config.model, messages = history, maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS)
    }

    /**
     * The persisted rows → strict model messages (a malformed tool row fails the turn closed).
     *
     * HXA-055: every USER message's persisted `message_attachments` bindings whose artifact is
     * an image become that message's [ModelMessage.images] — re-verified at EVERY request build
     * (send, retry, tool-loop back-fill, restore): the artifact must still exist, its bytes must
     * hash to the bound SHA-256, and its magic must agree with the registered type. Any miss
     * fails the turn closed (ADR-0014 §4: 「发送、重试、恢复前重验 hash；变化或缺失即失败关闭」).
     * The TOTAL base64 of all images in the request is bounded by
     * [VisionLimits.MAX_TOTAL_BASE64_PER_REQUEST_BYTES] — the strictest provider request-size
     * bound — and an over-budget conversation fails closed with an actionable error.
     */
    private suspend fun persistedHistory(
        sessionId: String,
        retryTurnId: String?,
    ): List<ModelMessage> {
        val rows =
            storage.messages
                .listBySession(sessionId)
                .map {
                    ChatHistoryBuilder.PersistedRow(
                        turnId = it.turnId,
                        role = it.role,
                        kind = it.kind,
                        content = storage.messages.readContent(it),
                        messageId = it.id,
                    )
                }
        val historyRows = ChatHistoryBuilder.rowsForTurn(rows, retryTurnId)
        val messages = ChatHistoryBuilder.toModelMessagesStrict(historyRows)
        // USER rows that produce a message: non-blank content (the builder's own rule) — the
        // count must match the history's USER messages exactly, or the pairing would attach an
        // image to the wrong message and we fail closed instead.
        val userRows =
            historyRows.filter { row ->
                row.role == ModelRole.USER.name && row.messageId != null && !row.content.isNullOrBlank()
            }
        val userMessages = messages.filter { it.role == ModelRole.USER }
        require(userRows.size == userMessages.size) {
            "history USER rows and USER messages diverge — image binding refused"
        }
        var userRow = 0
        return messages.map { message ->
            if (message.role == ModelRole.USER) {
                message.copy(images = imageReferencesFor(userRows[userRow++].messageId.orEmpty()))
            } else {
                message
            }
        }
    }

    /**
     * The verified [ImageReference]s bound to one persisted USER message (HXA-055): every
     * binding whose artifact is an image (closed media type) is re-verified — artifact present,
     * bytes hash to the bound SHA-256, magic agrees with the registered type — and the
     * request-wide base64 budget is enforced. Any miss throws [IllegalArgumentException] and
     * the turn fails closed; there is no silent drop and no raw fallback.
     */
    private suspend fun imageReferencesFor(messageId: String): List<ImageReference> {
        val bindings = storage.messageAttachments.listByMessage(messageId)
        if (bindings.isEmpty()) return emptyList()
        var totalBase64 = 0L
        val images = ArrayList<ImageReference>(bindings.size)
        for (binding in bindings) {
            val facts = verifiedImageBinding(binding) ?: continue // a text binding is not an image
            totalBase64 += facts.base64Bytes
            images += facts.reference
        }
        require(totalBase64 <= VisionLimits.MAX_TOTAL_BASE64_PER_REQUEST_BYTES) {
            "the session's image data exceeds the per-request budget — start a new session to send more images"
        }
        return images
    }

    /**
     * One persisted binding re-verified against its artifact (HXA-055): [null] when the binding
     * is NOT an image (a text attachment), a verified [ImageReference] + its base64 size when it
     * is, and an [IllegalArgumentException] (the turn fails closed) when the artifact changed or
     * vanished — the ADR's re-verify-before-send/retry/restore rule.
     */
    private data class ImageBindingFacts(
        val reference: ImageReference,
        val base64Bytes: Long,
    )

    @Suppress("ThrowsCount") // one throw per closed re-verification failure (existence / path / hash / magic)
    private suspend fun verifiedImageBinding(
        binding: com.helix.core.storage.entity.MessageAttachmentEntity,
    ): ImageBindingFacts? {
        val artifact =
            runCatching { storage.artifacts.resolve(binding.artifactId) }
                .getOrNull()
                ?: throw IllegalArgumentException("bound image artifact no longer exists — re-verify the session")
        if (artifact.mediaType !in VisionLimits.NORMALIZED_MEDIA_TYPES) return null // text binding
        require(artifact.size <= VisionLimits.MAX_NORMALIZED_RAW_BYTES) {
            "bound image exceeds the per-image wire budget"
        }
        val scopePath =
            runCatching { FileScopePath(attachmentStaging.workspaceScopeId, artifact.relativePath) }
                .getOrNull()
                ?: throw IllegalArgumentException("bound image artifact path is invalid — re-verify the session")
        val file =
            runCatching { attachmentStaging.resolveWorkspacePath(scopePath) }
                .getOrNull()
                ?: throw IllegalArgumentException("bound image artifact path escapes the workspace")
        require(Files.isRegularFile(file)) {
            "bound image artifact is missing — the message can no longer be restored"
        }
        val actualHash =
            try {
                AtomicFileWriter.sha256Hex(file)
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException("bound image artifact is unreadable — re-verify the session", e)
            }
        require(actualHash == binding.boundSha256) {
            "bound image hash no longer matches the message binding"
        }
        val bytes =
            try {
                Files.readAllBytes(file)
            } catch (e: java.io.IOException) {
                throw IllegalArgumentException("bound image artifact is unreadable — re-verify the session", e)
            }
        val magic = ContentProbe.probeBytes(bytes, bytes.size.toLong()).mimeType
        require(magic == artifact.mediaType) { "bound image bytes do not match their registered type" }
        return ImageBindingFacts(
            reference = ImageReference(ArtifactRef(artifact.id), artifact.mediaType),
            base64Bytes = ((bytes.size + 2L) / 3L) * 4L,
        )
    }

    /**
     * Persists the assistant's tool-call step (roadmap HXA-037): the model-visible content
     * of the step is exactly the calls — `[{"id","name","arguments"}]` in the model's
     * ORIGINAL order (`arguments` is the model's RAW argument JSON object string). The
     * approval binding hashes the CANONICAL form of the same object (sorted keys,
     * minified — [CanonicalArgs.canonicalize]); the two representations agree as
     * objects but are generally different byte strings, so this row stores the model's
     * original text while the binding hashes its canonical form (same source, two
     * representations).
     */
    private fun assistantToolStepJson(calls: List<BufferedModelToolCall>): String =
        buildJsonArray {
            calls.forEach { call ->
                add(
                    buildJsonObject {
                        put("id", call.callId)
                        put("name", call.name)
                        put("arguments", call.arguments)
                    },
                )
            }
        }.toString()

    /**
     * Persists ONE settled tool result as a TOOL message (called in CALL SEQUENCE — the
     * back-fill order the next model request re-carries). The content is the bounded
     * `{"id","tool","status","summary"}` — exactly the text the timeline shows.
     */
    private fun toolResultDraft(settled: SettledCall): TurnMessageDraft {
        val status: String
        val summary: String
        when (val o = settled.outcome) {
            is ToolDispatchOutcome.Succeeded -> {
                status = "SUCCEEDED"
                summary = boundedSummary(o.result.payload)
            }

            is ToolDispatchOutcome.Denied -> {
                status = o.code.name
                summary = o.detail
            }

            is ToolDispatchOutcome.ExecutionFailed -> {
                status = o.code.name
                summary = o.detail
            }

            ToolDispatchOutcome.Cancelled -> {
                status = "CANCELLED"
                summary = str(R.string.tool_summary_cancelled_before_start)
            }
        }
        val body =
            buildJsonObject {
                put("id", settled.callId)
                put("tool", settled.toolName)
                put("status", status)
                put("summary", summary)
            }
        return TurnMessageDraft(
            role = ModelRole.TOOL,
            kind = ChatHistoryBuilder.KIND_TOOL_RESULT,
            content = body.toString(),
        )
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
        val descriptor: ToolDescriptor?,
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
     * One model call's tool round (roadmap HXA-037; doc 11 section 3): prepares every
     * finished tool call (persist the tool_call row with the CANONICAL argument bytes —
     * doc 02 section 9.1/9.2: the stored argsJson is the same text the approval binding
     * hashes; the row's primary key IS the model call id, the approvals table's foreign
     * key targets tool_calls.id), then runs them through the [ToolScheduler] — bounded
     * platform-decided parallelism, call-order deterministic settlement.
     *
     * Every call gets a DURABLE outcome (doc 11 section 7): a dispatcher abort (turn
     * stop during an approval wait) settles the affected call CANCELLED and rethrows
     * [ApprovalCancelledException] AFTER all settled calls are persisted.
     *
     * This runs on the work scope's IO thread — the scheduler and the broker's blocking
     * user-decision wait never touch the main thread.
     */
    private fun runToolBatch(
        turn: com.helix.core.storage.entity.TurnEntity,
        turnId: String,
        calls: List<BufferedModelToolCall>,
        coordinator: TurnCoordinator,
    ): List<SettledCall> {
        val prepareds =
            calls.map { call ->
                prepareToolCall(turn, call.callId, call.name, call.arguments)
            }
        val requests = prepareds.mapNotNull { it.request }
        val batch =
            if (requests.isEmpty()) {
                ToolScheduler.BatchResult(emptyList())
            } else {
                toolPipeline.scheduler.scheduleBatch(requests)
            }
        var slot = 0
        val settled =
            prepareds.map { p ->
                if (p.preSettled != null) {
                    // Malformed BEFORE the dispatcher (invalid name / non-object args):
                    // persistRejectedToolCall already wrote row + result + audit.
                    coordinator.settleBatchCall(p.callId, sideEffectUnknown = false)
                    SettledCall(p.callId, p.toolNameRaw, p.preSettled)
                } else {
                    val settlement = batch.settlements[slot++]
                    val thrown = (settlement as? ToolScheduler.BatchSettlement.Thrown)?.cause
                    val unknown = thrown != null && thrown !is ApprovalCancelledException
                    val outcome =
                        when (settlement) {
                            is ToolScheduler.BatchSettlement.Outcome -> settlement.outcome
                            is ToolScheduler.BatchSettlement.Thrown -> unsettledSlotSettlement(settlement.cause)
                        }
                    settleToolCall(p.row!!, p.callId, p.toolNameRaw, outcome, unknown)
                    coordinator.settleBatchCall(p.callId, sideEffectUnknown = unknown)
                    SettledCall(p.callId, p.toolNameRaw, outcome)
                }
            }
        batch.firstError?.let { error ->
            if (error is ApprovalCancelledException) {
                // The turn is over (doc 11: cancel leaves a durable outcome for every
                // queued call — all slots above are settled); drop the signal and
                // propagate the turn-level cancellation.
                turnCancels.remove(turnId)
            }
            throw error
        }
        return settled
    }

    /** A settled tool call: the model call id, its name, the durable outcome (call order). */
    private data class SettledCall(
        val callId: String,
        val toolName: String,
        val outcome: ToolDispatchOutcome,
    )

    /** One prepared tool call: the persisted row + dispatch request, or a pre-settled rejection. */
    private class PreparedToolCall(
        val callId: String,
        val toolNameRaw: String,
        val row: com.helix.core.storage.entity.ToolCallEntity?,
        val request: ToolDispatchRequest?,
        val preSettled: ToolDispatchOutcome.Denied?,
    )

    /**
     * The per-call preparation of the tool pipeline (roadmap HXA-036/037; doc 11: the
     * Dispatcher is the only path between model-requested calls and implementations):
     * validate the name/arguments, persist the tool_call row with the canonical bytes,
     * publish the timeline row, build the trusted dispatch request and register the card
     * facts. Malformed input the dispatcher can never see (an invalid tool name,
     * non-object arguments) is persisted + audited HERE as a stable
     * [ToolDispatchOutcome.Denied] (preSettled) — the dispatcher is never fed garbage.
     */
    private fun prepareToolCall(
        turn: com.helix.core.storage.entity.TurnEntity,
        toolCallId: String,
        toolNameRaw: String,
        rawArgsJson: String,
    ): PreparedToolCall {
        val turnId = turn.id
        val toolName: ToolName? = runCatching { ToolName(toolNameRaw) }.getOrNull()
        val descriptor = toolPipeline.resolveLatest(toolNameRaw)
        // No-argument tools (e.g. time.now, whose ONLY valid input is {}) receive
        // arguments as an empty string or no argument fragments at all on many
        // OpenAI-compatible servers (observed: Ollama) — the decoders skip blank
        // fragments, so the accumulated buffer ends up empty. Normalize empty to the
        // empty object: a tool that REQUIRES arguments still gets its precise schema
        // rejection (missing properties), instead of the misleading "not a valid JSON
        // object" for a call the model made correctly.
        val normalizedArgs = if (rawArgsJson.isBlank()) "{}" else rawArgsJson
        val args: JsonObject? = parseJsonObjectOrNull(normalizedArgs)
        // Malformed input the dispatcher can never see (an invalid tool name, non-object
        // arguments) is persisted + audited HERE as a stable Denied (preSettled).
        val rejection = invalidToolCallRejection(turn, toolCallId, toolNameRaw, rawArgsJson, toolName, args, descriptor)
        rejection?.let { return it }
        val validName = toolName!!
        val validArgs = args!!
        val canonical = CanonicalArgs.canonicalize(validArgs)
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
        publishToolRow(turnId, toolCallId, toolNameRaw, canonical, str(R.string.tool_state_processing), null, null)
        val request = buildDispatchRequest(turn, toolCallId, validName, descriptor, validArgs, profile)
        dispatchFacts[toolCallId] =
            DispatchFacts(descriptor, validArgs, profile, DataOrigin.WORKSPACE, turnId, request.egress)
        return PreparedToolCall(toolCallId, toolNameRaw, row, request, null)
    }

    /** The pre-settled Denied for an invalid tool NAME or non-object ARGUMENTS; null when both are valid. */
    @Suppress("LongParameterList") // one parameter per validated fact; splitting the pair would obscure the invariant
    private fun invalidToolCallRejection(
        turn: com.helix.core.storage.entity.TurnEntity,
        toolCallId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        toolName: ToolName?,
        args: JsonObject?,
        descriptor: ToolDescriptor?,
    ): PreparedToolCall? =
        when {
            toolName == null -> {
                PreparedToolCall(
                    toolCallId,
                    toolNameRaw,
                    null,
                    null,
                    persistRejectedToolCall(
                        turn,
                        toolCallId,
                        toolNameRaw,
                        rawArgsJson,
                        "unknown",
                        DispatchOutcomeCode.UNKNOWN_TOOL,
                        str(R.string.tool_rejected_bad_name),
                    ),
                )
            }

            args == null -> {
                PreparedToolCall(
                    toolCallId,
                    toolNameRaw,
                    null,
                    null,
                    persistRejectedToolCall(
                        turn,
                        toolCallId,
                        toolNameRaw,
                        rawArgsJson,
                        descriptor?.version?.value?.toString() ?: "unknown",
                        DispatchOutcomeCode.INVALID_ARGUMENTS,
                        str(R.string.tool_rejected_bad_args),
                    ),
                )
            }

            else -> {
                null
            }
        }

    /**
     * The single per-call entry point of the tool pipeline (roadmap HXA-036; kept for the
     * direct (non-stream) callers and the device tests): prepare → single-call scheduler
     * batch → settle. The dispatcher MAY BLOCK on the approval card's user-decision wait,
     * so never call this from the main thread. The turn row must already be persisted;
     * the session id is the turn's PERSISTED session (a trusted fact). The mode is
     * [AgentMode.ACT]: the chat UI has no Plan/Goal tool surface yet (those come with
     * their own milestones) — when one arrives it feeds the request's mode field, and the
     * Policy Engine's Plan gate (READ_ONLY + L1 ceiling) applies from that request on.
     *
     * A turn stop during the approval wait settles the call as CANCELLED (doc 11: every
     * queued call gets a durable outcome) and rethrows [ApprovalCancelledException] for
     * the turn-level handler.
     */
    fun dispatchToolCall(
        toolCallId: String,
        turnId: String,
        toolNameRaw: String,
        rawArgsJson: String,
    ): ToolDispatchOutcome {
        val turn = storage.turns.resolve(turnId)
        val prepared = prepareToolCall(turn, toolCallId, toolNameRaw, rawArgsJson)
        prepared.preSettled?.let { return it }
        val batch = toolPipeline.scheduler.scheduleBatch(listOf(prepared.request!!))
        val settlement = batch.settlements.single()
        val thrown = (settlement as? ToolScheduler.BatchSettlement.Thrown)?.cause
        val unknown = thrown != null && thrown !is ApprovalCancelledException
        val outcome =
            when (settlement) {
                is ToolScheduler.BatchSettlement.Outcome -> settlement.outcome
                is ToolScheduler.BatchSettlement.Thrown -> unsettledSlotSettlement(settlement.cause)
            }
        settleToolCall(prepared.row!!, toolCallId, toolNameRaw, outcome, unknown)
        // The call has settled (either way): its cancel signal has served its purpose.
        // Releasing it here (the direct path has no turn-level finalizer, unlike the
        // stream path) prevents both a process-lifetime leak and a later stop() reaching
        // a call of this turn that was never started.
        turnCancels.remove(turnId)
        batch.firstError?.let { error ->
            throw error
        }
        return outcome
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
        descriptor: ToolDescriptor?,
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

    /**
     * The durable settlement for a slot the dispatcher threw away instead of returning
     * (the scheduler records it as that slot's [ToolScheduler.BatchSettlement.Thrown]).
     * The honest outcome depends on the cause: the broker's cancel exception is the ONLY proof that
     * nothing executed ("cancelled before start, no side effects" -> CANCELLED); every
     * other throw (executor crash, pool rejection, framework ISE) means the side-effect
     * state is UNKNOWN -> FAILED. Settling an unknown as "no side effects" would tell
     * the model the call never happened while the audit row says it failed — the exact
     * settlement/audit disagreement the audit page exists to prevent.
     */
    private fun unsettledSlotSettlement(error: Throwable?): ToolDispatchOutcome =
        if (error is ApprovalCancelledException) {
            ToolDispatchOutcome.Cancelled
        } else {
            ToolDispatchOutcome.ExecutionFailed(
                DispatchOutcomeCode.TOOL_FAILED,
                str(
                    R.string.tool_interrupted_by_orchestrator,
                    error?.javaClass?.simpleName ?: str(R.string.common_unknown),
                ),
            )
        }

    private fun settleToolCall(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome,
        sideEffectUnknown: Boolean = false,
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
                settleExecutionFailed(row, toolCallId, toolName, outcome, sideEffectUnknown)
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
        publishToolRow(
            row.turnId,
            toolCallId,
            toolName,
            row.argsJson,
            str(R.string.tool_state_completed),
            summary,
            null,
        )
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
            str(ApprovalUiMapper.codeLabel(outcome.code)),
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
            summary = str(R.string.tool_summary_cancelled_before_start),
            content = null,
        )
        setCardStateForCall(toolCallId, ApprovalCardState.FAILED, str(R.string.turn_stopped))
        publishToolRow(
            row.turnId,
            toolCallId,
            toolName,
            row.argsJson,
            str(R.string.tool_state_cancelled),
            str(R.string.tool_summary_cancelled_before_start),
            null,
        )
    }

    private fun settleExecutionFailed(
        row: com.helix.core.storage.entity.ToolCallEntity,
        toolCallId: String,
        toolName: String,
        outcome: ToolDispatchOutcome.ExecutionFailed,
        sideEffectUnknown: Boolean,
    ) {
        val state = if (sideEffectUnknown) ToolCallState.NEEDS_REVIEW else ToolCallState.FAILED
        storage.toolCalls.updateState(row, state)
        storage.toolResults.append(
            id = idGenerator(),
            toolCallId = toolCallId,
            status = state.name,
            summary = outcome.detail,
            content = null,
        )
        setCardStateForCall(
            toolCallId,
            ApprovalCardState.FAILED,
            str(ApprovalUiMapper.codeLabel(outcome.code)) + "：" + outcome.detail,
        )
        val label =
            if (sideEffectUnknown) {
                str(R.string.tool_state_side_effect_pending)
            } else {
                str(R.string.tool_state_failed)
            }
        publishToolRow(row.turnId, toolCallId, toolName, row.argsJson, label, outcome.detail, null)
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
        publishToolRow(turn.id, toolCallId, toolNameRaw, rawArgs, str(R.string.tool_state_denied), detail, null)
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
        // Atomic update: this row mutation races other timeline writers (the card sink
        // runs on a scheduler pool thread; settle/cancel run on the IO scope). A
        // read-modify-write on the whole screen state would let a concurrent write lose
        // this update — and the card is published exactly ONCE, so a lost publish is a
        // turn the user can never approve.
        _screen.update { screen ->
            val preserved =
                card ?: screen.toolTimeline
                    .firstOrNull { it.turnId == turnId && it.callId == callId }
                    ?.card
            screen.copy(
                toolTimeline =
                    screen.toolTimeline
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
    }

    private fun attachCardToRow(
        callId: String,
        card: com.helix.app.approval.ApprovalCardUi,
    ) {
        _screen.update { screen ->
            screen.copy(
                toolTimeline =
                    screen.toolTimeline.map { row ->
                        if (row.callId == callId) {
                            row.copy(card = card, stateLabel = str(R.string.tool_state_awaiting_approval))
                        } else {
                            row
                        }
                    },
            )
        }
    }

    private fun updateCard(
        approvalId: String,
        transform: (com.helix.app.approval.ApprovalCardUi) -> com.helix.app.approval.ApprovalCardUi,
    ) {
        _screen.update { screen ->
            screen.copy(
                toolTimeline =
                    screen.toolTimeline.map { row ->
                        val card = row.card ?: return@map row
                        if (card.approvalId != approvalId) return@map row
                        row.copy(card = transform(card))
                    },
            )
        }
    }

    private fun setCardStateForCall(
        callId: String,
        state: ApprovalCardState,
        terminalDetail: String?,
        keepDenied: Boolean = false,
    ) {
        _screen.update { screen ->
            screen.copy(
                toolTimeline =
                    screen.toolTimeline.map { row ->
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
    }

    private fun boundedSummary(payload: String): String {
        if (payload.length <= SUMMARY_CAP) return payload
        return payload.take(SUMMARY_CAP) + "…"
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
        val sessionId = resolvableOpenSessionId()
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
                pendingAttachments = stagedAttachmentsUi(),
                shareDraftText = shareDraftText,
            )
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
        // No open session: KEEP the live rows as-is. A pending approval card lives ONLY
        // here (the dispatcher is still blocked in the broker while the user navigates
        // away); dropping it on close would leave the call "待审批" with no card to tap —
        // the turn un-approvable until stop or the 24h window expiry. The session-list
        // screen does not render the timeline, so this is invisible there and the overlay
        // is restored verbatim when the session reopens.
        if (sessionId == null) return liveRows
        val sessionTurns = storage.turns.listBySession(sessionId)
        val persisted =
            sessionTurns
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
        // Scope the overlay to THIS session's turns: a live row from another session
        // (e.g. a pending card left open when the user switched) must not appear here.
        val turnsInSession = sessionTurns.map { it.id }.toSet()
        val scoped = liveRows.filter { it.turnId in turnsInSession }
        return if (scoped.isEmpty()) {
            persisted
        } else {
            val liveByCall = scoped.associateBy { it.callId }
            persisted
                .map { row ->
                    val live = liveByCall[row.callId] ?: return@map row
                    row.copy(
                        card = live.card,
                        stateLabel = live.stateLabel,
                        resultSummary = live.resultSummary ?: row.resultSummary,
                    )
                }.plus(scoped.filter { live -> persisted.none { it.callId == live.callId } })
        }
    }

    /** A persisted tool_call state as its user label (corrupt values fail closed). */
    private fun persistedStateLabel(state: String): String =
        when (runCatching { ToolCallState.valueOf(state) }.getOrNull()) {
            ToolCallState.PENDING -> str(R.string.tool_state_processing)
            ToolCallState.AWAITING_APPROVAL -> str(R.string.tool_state_awaiting_approval)
            ToolCallState.RUNNING -> str(R.string.tool_state_running)
            ToolCallState.NEEDS_REVIEW -> str(R.string.tool_state_needs_review)
            ToolCallState.INTERRUPTED -> str(R.string.tool_state_interrupted)
            ToolCallState.COMPLETED -> str(R.string.tool_state_completed)
            ToolCallState.FAILED -> str(R.string.tool_state_failed)
            ToolCallState.CANCELLED -> str(R.string.tool_state_cancelled)
            ToolCallState.DENIED -> str(R.string.tool_state_denied)
            null -> str(R.string.tool_state_unknown)
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
            errorLabel = entity.errorCode?.let { code -> str(modelTerminalCodeRes(code)) },
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
        _screen.update { it.copy(activeTurn = turn) }
    }

    private fun setBlocked(reason: String) {
        _screen.update { it.copy(blockedReason = reason) }
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
            append(jsonEscape(c.model))
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
        const val TOOL_TIMELINE_CAP = 200
        const val KIND_TEXT = ChatHistoryBuilder.KIND_TEXT

        /**
         * The turn's tool-round budget (roadmap HXA-037): a turn may run at most this many
         * model→tools rounds before it ends FAILED (fail closed — an unbounded tool loop
         * would burn the user's model budget; the full user-configurable TurnBudgets
         * arrive with the budget UI, this is the hard product cap in the meantime).
         */
        const val MAX_TOOL_ROUNDS_PER_TURN = 8
        const val DEFAULT_MAX_OUTPUT_TOKENS = 4_096L

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

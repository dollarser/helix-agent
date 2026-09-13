package com.helix.app.chat

import com.helix.app.agent.ChatContextRequest
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.TurnContextAssembler
import com.helix.app.goal.goalReportContext
import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.ModePolicy
import com.helix.core.agent.ToolModeProfile
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage

/**
 * The single context-construction system (HX2-03; the doc section 34 ContextEngine entry):
 * this assembler (request build + compaction admission) + [ChatHistoryBuilder] (persisted rows
 * to model messages) + [ContextCompaction] (the only trim mechanism). The dormant first-version
 * core ContextBuilder was retired as the parallel "architecture" context system (doc section
 * 9.1); its source/trust domain remains design input for the tiered upgrade (doc section 12).
 * Reads current persisted facts for each request; owns no live Turn or approval state. Also the
 * agent loop's [TurnContextAssembler] port (HX2-02): every model request the loop builds flows
 * through here.
 */
internal class ChatRequestAssembler(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    private val toolPipeline: ToolPipeline,
    private val attachmentStaging: AttachmentStagingSupport,
    private val visionSessionBinder: (String) -> Unit,
    /**
     * P1 (research doc section 8): resolves the session workspace's project-instruction file
     * (AGENTS.md / CLAUDE.md / HELIX.md) to the bounded, trust-framed block registered as the
     * PROJECT section of the goal system prompt. Called only for an active goal turn. The
     * default yields "" so JVM/device services without a workspace reader behave exactly as
     * before (no project instructions injected).
     */
    private val projectInstructionsReader: (String) -> String = { "" },
) : TurnContextAssembler {
    private val imageVerifier = ImageReferenceVerifier(storage, attachmentStaging)

    // AgentLoop port (HX2-02): the loop-facing names of the two plain request paths.
    override suspend fun build(
        sessionId: String,
        retryTurnId: String?,
        control: RunControlConfig,
    ): ChatContextRequest = buildRequest(sessionId, retryTurnId, control)

    override suspend fun buildBackfill(
        sessionId: String,
        control: RunControlConfig,
    ): ChatContextRequest = buildBackfillRequest(sessionId, control)

    /** Read-only repair preflight; the ordinary send path still performs its full admission. */
    suspend fun contextFits(
        sessionId: String,
        control: RunControlConfig,
        prompt: String,
    ): Boolean {
        val config = providerService.storedConfig(sessionProviderId(sessionId))
        val model = storage.sessions.resolve(sessionId).modelId ?: config.model
        val request =
            ChatContextRequest(
                model,
                persistedHistory(sessionId, null) + ModelMessage(ModelRole.USER, prompt),
                modelTools(sessionId, control),
                minOf(DEFAULT_MAX_OUTPUT_TOKENS, control.budgets.maxOutputTokens),
                com.helix.core.model.ReasoningEffort.OFF,
            )
        val window =
            providerService.contextSettingsStore
                .read(
                    config.id,
                    config.endpoint.full,
                    model,
                ).window
        return request.messages.size <= ModelRequest.MAX_MESSAGES &&
            request.inputTokens() <= control.budgets.maxInputTokens &&
            request.inputTokens() + request.maxOutputTokens <= window
    }

    /**
     * Builds the model request from PERSISTED rows: the session's
     * user/assistant history (ChatHistoryBuilder) — for a retry, the retried
     * turn's own assistant rows are excluded but its user message is kept, so
     * the request ends with the same USER message the user is retrying.
     */
    suspend fun buildRequest(
        sessionId: String,
        retryTurnId: String?,
        control: RunControlConfig,
    ): ChatContextRequest {
        val history = persistedHistory(sessionId, retryTurnId)
        require(history.lastOrNull()?.role == ModelRole.USER) {
            "the request must end with the user message"
        }
        val config = providerService.storedConfig(sessionProviderId(sessionId))
        visionSessionBinder(sessionId)
        return ChatContextRequest(
            model = storage.sessions.resolve(sessionId).modelId ?: config.model,
            messages = history,
            tools = modelTools(sessionId, control),
            maxOutputTokens = minOf(DEFAULT_MAX_OUTPUT_TOKENS, control.budgets.maxOutputTokens),
            reasoning =
                if ((storage.sessions.resolve(sessionId).modelId ?: config.model) == config.model &&
                    com.helix.provider.api.ProviderCapabilities
                        .parse(
                            config.capabilitySnapshot,
                        ).reasoning
                ) {
                    control.reasoning
                } else {
                    com.helix.core.model.ReasoningEffort.OFF
                },
        )
    }

    /**
     * The next model request of a tool loop (roadmap HXA-037 back-fill): the FULL
     * persisted history, which now ends with the just-settled TOOL result rows —
     * `model-visible ⇔ persisted`: every message the model sees was persisted FIRST
     * (doc 11 section 4: no model-visible input without a persisted event).
     */
    suspend fun buildBackfillRequest(
        sessionId: String,
        control: RunControlConfig,
    ): ChatContextRequest {
        val history = persistedHistory(sessionId, null)
        require(history.lastOrNull()?.role == ModelRole.TOOL) {
            "a back-fill request must end with the tool results"
        }
        val config = providerService.storedConfig(sessionProviderId(sessionId))
        visionSessionBinder(sessionId)
        return ChatContextRequest(
            model = storage.sessions.resolve(sessionId).modelId ?: config.model,
            messages = history,
            tools = modelTools(sessionId, control),
            maxOutputTokens = minOf(DEFAULT_MAX_OUTPUT_TOKENS, control.budgets.maxOutputTokens),
            reasoning =
                if ((storage.sessions.resolve(sessionId).modelId ?: config.model) == config.model &&
                    com.helix.provider.api.ProviderCapabilities
                        .parse(
                            config.capabilitySnapshot,
                        ).reasoning
                ) {
                    control.reasoning
                } else {
                    com.helix.core.model.ReasoningEffort.OFF
                },
        )
    }

    override suspend fun rebuild(
        sessionId: String,
        retryTurnId: String?,
        control: RunControlConfig,
        previous: ChatContextRequest,
    ): ChatContextRequest =
        if (previous.messages.lastOrNull()?.role == ModelRole.TOOL) {
            buildBackfillRequest(sessionId, control)
        } else {
            buildRequest(sessionId, retryTurnId, control)
        }

    /** Latest registered contracts admitted by the selected mode. This is exposure only. */
    private fun modelTools(
        sessionId: String,
        control: RunControlConfig,
    ): List<ModelToolSchema> {
        val latest =
            toolPipeline.registry.all().groupBy { it.name }.values.map { versions ->
                versions.maxBy { it.version.value }
            }
        val admitted =
            ModePolicy
                .filterTools(control.mode, latest, control.chatToolsEnabled) {
                    ToolModeProfile(it.operationClass, it.baseRisk)
                }
        return toolPipeline.mcpDiscovery
            .visible(sessionId, admitted)
            .filter { it.name.value != "goal.report" || control.mode == com.helix.core.model.AgentMode.GOAL }
            .sortedBy { if (it.name.value == "goal.report") 0 else 1 }
            .take(ModelRequest.MAX_TOOLS)
            .map { ModelToolSchema(it.name, it.description, it.inputSchema.toString()) }
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
        val allRows = storage.messages.listBySession(sessionId)
        val checkpoint = ContextCompaction.checkpoint(storage, allRows)
        val rows =
            ContextCompaction
                .retained(allRows, checkpoint)
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
        val restored =
            messages.map { message ->
                if (message.role == ModelRole.USER) {
                    message.copy(images = imageVerifier.imageReferencesFor(userRows[userRow++].messageId.orEmpty()))
                } else {
                    message
                }
            }
        return storage.goalReportContext(sessionId) { projectInstructionsReader(sessionId) } +
            if (checkpoint == null) {
                restored
            } else {
                restored.filter { it.role == ModelRole.SYSTEM } + ContextCompaction.summaryMessage(checkpoint) +
                    restored.filter { it.role != ModelRole.SYSTEM }
            }
    }

    private fun sessionProviderId(sessionId: String): String =
        requireNotNull(storage.sessions.resolve(sessionId).providerId) { "session has no provider" }

    private companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 4_096L
    }
}

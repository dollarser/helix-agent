package com.helix.app.chat

import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.ModePolicy
import com.helix.core.agent.ToolModeProfile
import com.helix.core.model.ArtifactRef
import com.helix.core.model.ImageReference
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import java.nio.file.Files

/** Reads current persisted facts for each request; owns no live Turn or approval state. */
internal class ChatRequestAssembler(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    private val toolPipeline: ToolPipeline,
    private val attachmentStaging: AttachmentStagingSupport,
    private val visionSessionBinder: (String) -> Unit,
) {
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
    ): ModelRequest {
        val history = persistedHistory(sessionId, retryTurnId)
        require(history.lastOrNull()?.role == ModelRole.USER) {
            "the request must end with the user message"
        }
        val config = providerService.storedConfig(sessionProviderId(sessionId))
        visionSessionBinder(sessionId)
        return ModelRequest(
            model = storage.sessions.resolve(sessionId).modelId ?: config.model,
            messages = history,
            tools = modelTools(sessionId, control),
            maxOutputTokens = minOf(DEFAULT_MAX_OUTPUT_TOKENS, control.budgets.maxOutputTokens),
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
    ): ModelRequest {
        val history = persistedHistory(sessionId, null)
        require(history.lastOrNull()?.role == ModelRole.TOOL) {
            "a back-fill request must end with the tool results"
        }
        val config = providerService.storedConfig(sessionProviderId(sessionId))
        visionSessionBinder(sessionId)
        return ModelRequest(
            model = storage.sessions.resolve(sessionId).modelId ?: config.model,
            messages = history,
            tools = modelTools(sessionId, control),
            maxOutputTokens = minOf(DEFAULT_MAX_OUTPUT_TOKENS, control.budgets.maxOutputTokens),
        )
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

    private fun sessionProviderId(sessionId: String): String =
        requireNotNull(storage.sessions.resolve(sessionId).providerId) { "session has no provider" }

    private companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 4_096L
    }
}

package com.helix.app.infrastructure.conversation

import com.helix.app.chat.ArtifactQuery
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.chat.MessageRunMetrics
import com.helix.app.chat.MessageRunMetricsReader
import com.helix.app.chat.ToolInspectionQuery
import com.helix.app.proot.CommandEntry
import com.helix.app.proot.CommandResultBrowser
import com.helix.app.proot.CommandResultProjection
import com.helix.app.proot.CommandResultView
import com.helix.core.model.TurnState
import com.helix.core.model.citation.Citation
import com.helix.core.model.citation.CitationContent
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.ContentRef
import com.helix.core.workspace.FileScopePath
import com.helix.feature.conversation.application.ConversationHistoryReader
import com.helix.feature.conversation.presentation.COMMAND_TOOL_NAMES
import com.helix.feature.conversation.presentation.ChatScreenProjection
import com.helix.feature.conversation.presentation.MessageUi
import com.helix.feature.conversation.presentation.ToolInspectionContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-owned inspection of saved work: files, provenance, command results and request metrics.
 * Opening a detail never dispatches, resumes, acknowledges or grants authority to an execution.
 */
class ConversationWorkspaceDetails internal constructor(
    private val storage: HelixStorage,
    private val history: ConversationHistoryReader,
    private val projection: ChatScreenProjection,
    private val currentSessionId: () -> String?,
) {
    suspend fun messageRunMetrics(messageId: String): MessageRunMetrics =
        withContext(Dispatchers.IO) {
            MessageRunMetricsReader(storage).read(messageId)
        }

    suspend fun resolveCitation(citation: Citation): CitationContent? =
        withContext(Dispatchers.IO) {
            if (citation.kind != "session") return@withContext null
            val messageId = citation.messageId ?: return@withContext null
            var result: CitationContent? = null
            storage.withTransaction {
                val session = storage.sessions.find(citation.id) ?: return@withTransaction
                val message = storage.messages.find(messageId) ?: return@withTransaction
                if (message.sessionId != session.id) return@withTransaction
                val reference = message.contentRef?.let(ContentRef::parse)
                if (reference?.sha256 != citation.revision) return@withTransaction
                val body = storage.messages.readContentBounded(message, 1_048_576) ?: return@withTransaction
                result = CitationContent(session.title, citation.excerpt(body))
            }
            result
        }

    suspend fun taskResult(turnId: String): List<MessageUi> =
        withContext(Dispatchers.IO) {
            var result = emptyList<MessageUi>()
            storage.withTransaction {
                val turn = storage.turns.resolve(turnId)
                require(TurnState.valueOf(turn.state).isTerminal || turn.state == "NEEDS_REVIEW")
                result = projection.messagesFor(history.messagesForTurn(turnId))
            }
            result
        }

    suspend fun inspectToolResult(
        turnId: String,
        callId: String,
    ): ToolInspectionContent? {
        val original = currentSessionId() ?: return null
        val result =
            withContext(Dispatchers.IO) {
                var content: ToolInspectionContent? = null
                storage.withTransaction { content = ToolInspectionQuery(storage).read(original, turnId, callId) }
                content
            }
        return result.takeIf { currentSessionId() == original }
    }

    internal suspend fun taskArtifactsForTurn(turnId: String): List<ArtifactRowUi> =
        withContext(Dispatchers.IO) {
            ArtifactQuery(storage).forTurn(turnId)
        }

    internal suspend fun taskArtifactsForGoal(goalId: String): List<ArtifactRowUi> =
        withContext(Dispatchers.IO) {
            ArtifactQuery(storage).forGoal(goalId)
        }

    internal suspend fun conversationArtifacts(sessionId: String): List<ArtifactRowUi> =
        withContext(Dispatchers.IO) {
            ArtifactQuery(storage).forSession(sessionId)
        }

    /** Source provenance is distinct from generated outputs; neither is admitted as fresh input here. */
    internal suspend fun conversationDocumentSources(sessionId: String): List<ArtifactRowUi> =
        withContext(Dispatchers.IO) {
            storage.documentSnapshots.listBySession(sessionId).map { source ->
                val path = FileScopePath.fromModelReference(source.sourcePath)
                val ref = ContentRef.parse(source.contentRef)
                ArtifactRowUi(
                    source.id,
                    sessionId,
                    source.sourcePath,
                    path.relativePath.substringAfterLast('/'),
                    if (source.format == "pdf") {
                        "application/pdf"
                    } else {
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    },
                    ref.size,
                    ref.sha256,
                    null,
                    null,
                    path.scopeId.startsWith("saf-"),
                )
            }
        }

    suspend fun commandResult(
        turnId: String,
        callId: String,
    ): CommandResultView? =
        withContext(Dispatchers.IO) {
            CommandResultBrowser.browse(storage, turnId, callId)
        }

    suspend fun turnCommands(turnId: String): List<CommandEntry> =
        withContext(Dispatchers.IO) {
            var result = emptyList<CommandEntry>()
            storage.withTransaction {
                val turn = storage.turns.resolve(turnId)
                result =
                    storage.toolCalls
                        .listByTurn(turnId)
                        .filter { it.name in COMMAND_TOOL_NAMES }
                        .map { call ->
                            CommandEntry(
                                call.callId,
                                call.name,
                                CommandResultProjection.commandTextFromArgs(call.argsJson),
                                call.state,
                                turn.state,
                            )
                        }
            }
            result
        }
}

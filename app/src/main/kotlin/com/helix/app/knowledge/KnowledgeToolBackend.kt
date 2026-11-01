package com.helix.app.knowledge

import com.helix.core.storage.repository.KnowledgeReference
import com.helix.core.storage.repository.KnowledgeReferences
import com.helix.core.storage.repository.SessionHistoryCaller
import com.helix.core.storage.repository.SessionInputState
import com.helix.tools.framework.ExecutableToolCall

/** Durable target-conversation grant; model-supplied IDs never create authority. */
internal class KnowledgeToolBackend(
    private val history: com.helix.core.storage.repository.SessionHistoryRepository,
    private val input: (String) -> com.helix.core.storage.repository.SessionInputRecord?,
    private val global: KnowledgeReadTools.Library,
) : KnowledgeReadTools.Backend {
    private fun caller(call: ExecutableToolCall) =
        SessionHistoryCaller(
            requireNotNull(call.sessionId),
            requireNotNull(call.turnId),
            call.toolCallId,
            call.toolName,
        )

    override fun checkCaller(call: ExecutableToolCall) {
        history.caller(caller(call))
    }

    override fun allowed() = global.allowed()

    override suspend fun search(query: String) = global.search(query)

    override suspend fun related(
        query: String,
        references: List<KnowledgeReference>?,
    ) = global.related(query, references)

    override suspend fun read(
        kind: String,
        id: String,
    ) = global.read(kind, id)

    override suspend fun originalPdf(
        call: ExecutableToolCall,
        id: String,
        file: Int,
        page: Int,
        offset: Int,
        limit: Int,
    ) = global.originalPdf(call, id, file, page, offset, limit)

    override fun references(
        call: ExecutableToolCall,
        inputId: String,
        hash: String,
    ): List<KnowledgeReference> {
        val identity = caller(call)
        val input = requireNotNull(input(inputId)) { "KNOWLEDGE_NOT_SHARED" }
        require(input.state == SessionInputState.APPENDED && input.sessionId == identity.sessionId) {
            "KNOWLEDGE_NOT_SHARED"
        }
        val message = history.message(identity, requireNotNull(input.messageId))
        require(message.role == "USER" && message.turnId == input.consumedTurnId) { "KNOWLEDGE_NOT_SHARED" }
        require(input.knowledgeReferences.isNotEmpty() && KnowledgeReferences.hash(input.knowledgeReferences) == hash) {
            "KNOWLEDGE_REFERENCE_CHANGED"
        }
        return input.knowledgeReferences
    }
}

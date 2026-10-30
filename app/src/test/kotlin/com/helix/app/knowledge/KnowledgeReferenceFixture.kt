package com.helix.app.knowledge

import com.helix.app.chat.SessionToolsTest
import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.repository.InputConfiguration
import com.helix.core.storage.repository.KnowledgeReference
import com.helix.core.storage.repository.KnowledgeReferences
import com.helix.core.storage.repository.SessionHistoryRepository
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/** Durable input/caller DAO and library I/O are fake; production grant and executors are unchanged. */
internal class KnowledgeReferenceFixture(
    root: File,
    initial: String,
    val category: String = "NOTE",
    val initialCompleteness: String = "USER_CONTENT",
) {
    val dao =
        SessionToolsTest.HistoryDao().apply {
            allowedTools =
                setOf("knowledge.search", "knowledge.read", "session.history", "session.search", "session.read")
        }
    val documents = mutableMapOf("selected" to initial, "private" to "UNSELECTED_PRIVATE_EVIDENCE")
    var enabled = false
    var completeness = initialCompleteness
    val reads = mutableListOf<String>()
    val pdfPages = mutableMapOf<Int, String>()
    val pdfReads = mutableListOf<String>()
    var afterPdfRead: () -> Unit = {}
    var pdfFailure: java.io.IOException? = null
    var afterRead: () -> Unit = {}
    val reference =
        KnowledgeReference(
            category,
            "selected",
            "Synthetic note",
            knowledgeHash(initial),
            initial.codePointCount(0, initial.length),
            initialCompleteness,
        )
    var input =
        SessionInputRecord(
            1,
            "input",
            "s",
            1,
            SessionInputDelivery.QUEUE,
            null,
            1,
            "unused",
            0,
            emptyList(),
            InputConfiguration("p", "m", "ACT", "config"),
            SessionInputState.APPENDED,
            "original",
            "owner",
            null,
            null,
            1,
            1,
            knowledgeReferences = listOf(reference),
        )
    val library =
        object : KnowledgeReadTools.Library {
            override fun allowed() = enabled

            override suspend fun originalPdf(
                call: ExecutableToolCall,
                id: String,
                file: Int,
                page: Int,
                offset: Int,
                limit: Int,
            ): JsonObject? {
                pdfReads += id
                afterPdfRead()
                pdfFailure?.let { throw it }
                val body = pdfPages[page] ?: return null
                val sliced =
                    com.helix.app.documents
                        .pdfTextSlice(sequenceOf(body), offset, limit)
                return buildJsonObject {
                    put("sourceRevision", knowledgeHash(requireNotNull(documents[id])))
                    put("text", sliced.text)
                    put("offset", offset)
                    put("endOffset", sliced.end)
                    put("truncated", sliced.truncated)
                    put("page", page)
                    put("fileIndex", file)
                    if (sliced.truncated) put("nextOffset", sliced.end)
                }
            }

            override suspend fun search(query: String) =
                buildJsonObject {
                    put("items", kotlinx.serialization.json.JsonArray(emptyList()))
                }

            override suspend fun read(
                kind: String,
                id: String,
            ): JsonObject? {
                reads += id
                val body = documents[id] ?: return null
                afterRead()
                return buildJsonObject {
                    put("kind", kind)
                    put("id", id)
                    put("title", "Synthetic note")
                    put("category", category)
                    put("completeness", completeness)
                    put("text", body)
                    put("contentHash", knowledgeHash(body))
                    if (category == "SOURCE") put("sourceRevision", knowledgeHash(body))
                    put("untrustedReference", true)
                    put(
                        "references",
                        kotlinx.serialization.json.JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("excerpt", "UNSELECTED_RELATED_EXCERPT")
                                },
                            ),
                        ),
                    )
                }
            }
        }
    val contentStore = FileContentStore(root)
    val history = SessionHistoryRepository(dao, contentStore)
    val backend =
        KnowledgeToolBackend(
            history,
            { id -> input.takeIf { it.inputId == id } },
            library,
        )

    init {
        val content = KnowledgeReferences.message("Original question", listOf(reference), input.inputId)
        dao.rows +=
            MessageEntity(
                "owner",
                "s",
                "original",
                "USER",
                "TEXT",
                contentStore.write(content).toStorageString(),
                1,
            )
    }

    fun scoped(extra: JsonObject) =
        JsonObject(
            extra +
                mapOf(
                    "referenceInputId" to JsonPrimitive(input.inputId),
                    "referenceHash" to JsonPrimitive(KnowledgeReferences.hash(listOf(reference))),
                ),
        )

    fun args(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    fun execute(
        name: String,
        args: JsonObject,
        turn: String = "t",
        callId: String = "call",
        cancel: CancelSignal = NoCancellation,
        deadline: Instant = Instant.now().plusSeconds(10),
    ): com.helix.tools.framework.ToolExecutorResult {
        dao.activeTurn = turn
        dao.activeCall = callId
        return KnowledgeReadTools.executor(name == "knowledge.search", backend).execute(
            ExecutableToolCall(callId, name, "1", args, ExecutionTargetType.LOCAL_ANDROID, deadline, cancel, "s", turn),
        )
    }
}

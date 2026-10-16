package com.helix.app.chat

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.model.isReviewModeAdmitted
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.dao.SessionHistoryDao
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.MessageReferenceSnapshotEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.repository.SessionHistoryRepository
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class SessionToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dao = HistoryDao()
    private val store by lazy { FileContentStore(temporary.root) }

    @Test fun schemasNeverAcceptCallerIdentityAndRenameIsNotAPlanExemption() {
        SessionToolDescriptors.names.forEach { name ->
            val tool = SessionToolDescriptors.descriptor(name)
            assertFalse(
                tool.inputSchema
                    .getValue("additionalProperties")
                    .jsonPrimitive.boolean,
            )
            assertFalse("sessionId" in tool.inputSchema.getValue("properties").jsonObject)
            assertEquals(name != "session.rename", tool.operationClass.isReviewModeAdmitted)
        }
        assertEquals(
            ToolOperationClass.LOCAL_MUTATION,
            SessionToolDescriptors.descriptor("session.rename").operationClass,
        )
    }

    @Test fun historyPagesBackwardsAndIncludesOldSavedTextWithoutSystemOrSupersededRows() {
        repeat(25) { body("m$it", "message $it", it.toLong()) }
        body("system", "secret prompt", 26, role = "SYSTEM")
        body("old", "revised away", 27, supersededBy = "new")
        val page = output(execute("session.history"))
        assertEquals(20, page.getValue("entries").jsonArray.size)
        assertEquals(
            "m24",
            page
                .getValue("entries")
                .jsonArray
                .first()
                .jsonObject
                .text("messageId"),
        )
        assertEquals(5, page.getValue("nextBeforeSequence").jsonPrimitive.int)
        body("newly-appended", "new", 100)
        val older = output(execute("session.history", """{"beforeSequence":5}"""))
        assertEquals(5, older.getValue("entries").jsonArray.size)
        assertFalse(older.getValue("hasMore").jsonPrimitive.boolean)
        assertFalse(page.toString().contains("secret prompt"))
    }

    @Test fun referenceIsFrozenAndNeverOpensTheSourceSession() {
        body("shared", "see earlier conversation", 1)
        val frozen = store.write("Earlier decision: use green.")
        dao.snapshots +=
            MessageReferenceSnapshotEntity(
                messageId = "shared",
                ordinal = 0,
                sourceSessionId = "foreign",
                sourceSessionTitle = "Source title",
                selectionKind = "RECENT_MESSAGES",
                sourceMessageIdsJson = "[\"foreign-message\"]",
                contentRef = frozen.toStorageString(),
                contentSha256 = frozen.sha256,
                createdAtEpoch = 1,
            )
        body("foreign-message", "PRIVATE live update", 2, session = "foreign")
        val found = output(execute("session.search", """{"query":"GREEN"}"""))
        val entry =
            found
                .getValue("entries")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("foreign", entry.text("sourceSessionId"))
        assertEquals(0, entry.getValue("referenceOrdinal").jsonPrimitive.int)
        dao.rows.removeAll { it.sessionId == "foreign" }
        val read = output(execute("session.read", readArgs("shared", frozen, 0)))
        assertEquals("Earlier decision: use green.", read.text("content"))
        assertTrue(execute("session.read", readArgs("foreign-message", frozen)) is ToolExecutorResult.Failed)
    }

    @Test fun readRejectsOtherSessionAndWrongHashEvenWhenIdsAreKnown() {
        val ref = body("other", "private body", 1, session = "foreign")
        assertTrue(execute("session.read", readArgs("other", ref)) is ToolExecutorResult.Failed)
        body("ours", "public body", 2)
        assertTrue(execute("session.read", readArgs("ours", ref)) is ToolExecutorResult.Failed)
        dao.active = false
        assertTrue(execute("session.status") is ToolExecutorResult.Failed)
    }

    @Test fun unicodeReadPagesAreExactAndOversizedOffsetsFail() {
        val content = "😀中".repeat(1500)
        val ref = body("m", content, 1)
        val first = output(execute("session.read", readArgs("m", ref)))
        val offset = first.getValue("nextOffset").jsonPrimitive.int
        assertEquals(2048, offset)
        val last = output(execute("session.read", readArgs("m", ref, offset = offset)))
        assertEquals(content, first.text("content") + last.text("content"))
        assertFalse(last.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(execute("session.read", readArgs("m", ref, offset = Int.MAX_VALUE)) is ToolExecutorResult.Failed)
    }

    @Test fun emptySearchPageHasACursorAndCanReachOlderMatches() {
        body("needle", "a Needle older than page", 0)
        repeat(41) { body("recent$it", "other text", it.toLong() + 1) }
        val first = output(execute("session.search", """{"query":"needle"}"""))
        assertTrue(first.getValue("entries").jsonArray.isEmpty())
        assertTrue(first.getValue("hasMore").jsonPrimitive.boolean)
        val next = first.getValue("nextBeforeSequence").jsonPrimitive.int
        val older = output(execute("session.search", """{"query":"needle","beforeSequence":$next}"""))
        assertEquals(
            "needle",
            older
                .getValue("entries")
                .jsonArray
                .single()
                .jsonObject
                .text("messageId"),
        )
    }

    @Test fun missingAndOversizedBodiesAreReportedWithoutReturningUnverifiedContent() {
        val missing = body("missing", "secret needle missing", 1)
        store.delete(missing)
        body("large", "needle" + "x".repeat(SessionHistoryRepository.MAX_CONTENT_BYTES), 2)
        body("good", "needle available", 3)
        val result = output(execute("session.search", """{"query":"needle"}"""))
        assertEquals(2, result.getValue("unavailableContents").jsonPrimitive.int)
        assertEquals(1, result.getValue("entries").jsonArray.size)
        assertFalse(result.toString().contains("secret needle missing"))
    }

    @Test fun cancellationBeforeAndDuringReadNeverPublishesContent() {
        val ref = body("m", "private content", 1)
        var cancelled = true
        val signal =
            object : CancelSignal {
                override fun isCancelled() = cancelled
            }
        assertEquals(ToolExecutorResult.Cancelled, execute("session.history", cancel = signal))
        cancelled = false
        val interrupting =
            object : ContentStore by store {
                override fun readBounded(
                    ref: ContentRef,
                    maxBytes: Int,
                ): String = store.readBounded(ref, maxBytes).also { cancelled = true }
            }
        assertEquals(ToolExecutorResult.Cancelled, execute("session.read", readArgs("m", ref), signal, interrupting))
        cancelled = false
        val search = execute("session.search", """{"query":"private"}""", signal, interrupting)
        assertEquals(ToolExecutorResult.Cancelled, search)
        assertEquals(ToolExecutorResult.TimedOut, execute("session.status", deadline = Instant.EPOCH))
    }

    @Test fun revisionDuringReadCannotReleaseTheOldMessage() {
        val ref = body("m", "private content", 1)
        val revising =
            object : ContentStore by store {
                override fun readBounded(
                    ref: ContentRef,
                    maxBytes: Int,
                ): String =
                    store.readBounded(ref, maxBytes).also {
                        dao.rows[0] = dao.rows[0].copy(supersededBy = "new")
                    }
            }
        assertTrue(execute("session.read", readArgs("m", ref), content = revising) is ToolExecutorResult.Failed)
    }

    @Test fun renameIsConditionalAndOnlyChangesCurrentTitle() {
        val before = dao.session
        val renamed = execute("session.rename", """{"expectedTitle":"Original","title":"New title"}""")
        assertTrue(renamed is ToolExecutorResult.Completed)
        assertEquals(before.copy(title = "New title", searchTitle = "new title"), dao.session)
        val stale = execute("session.rename", """{"expectedTitle":"Original","title":"Overwrite"}""")
        assertTrue(stale is ToolExecutorResult.Failed)
        dao.active = false
        val late = execute("session.rename", """{"expectedTitle":"New title","title":"Late"}""")
        assertTrue(late is ToolExecutorResult.Failed)
        assertEquals("New title", dao.session.title)
    }

    @Test fun notificationFailureDoesNotMisreportCommittedRename() {
        val result =
            execute(
                "session.rename",
                """{"expectedTitle":"Original","title":"Saved"}""",
                onRenamed = { error("observer failed") },
            )
        assertTrue(result is ToolExecutorResult.Completed)
        assertEquals("Saved", dao.session.title)
    }

    private fun body(
        id: String,
        text: String,
        sequence: Long,
        session: String = "s",
        role: String = "USER",
        supersededBy: String? = null,
    ): ContentRef {
        val ref = store.write(text)
        dao.rows += MessageEntity(id, session, null, role, "TEXT", ref.toStorageString(), sequence, supersededBy)
        return ref
    }

    private fun readArgs(
        id: String,
        ref: ContentRef,
        ordinal: Int? = null,
        offset: Int = 0,
    ): String =
        """{"messageId":"$id","contentHash":"${ref.sha256}","offset":$offset""" +
            ordinal?.let { ",\"referenceOrdinal\":$it" }.orEmpty() + "}"

    private fun execute(
        name: String,
        args: String = "{}",
        cancel: CancelSignal = NoCancellation,
        content: ContentStore = store,
        deadline: Instant = Instant.now().plusSeconds(30),
        onRenamed: () -> Unit = {},
    ): ToolExecutorResult {
        val registry = ToolRegistry()
        SessionTools.register(registry, SessionHistoryRepository(dao, content), onRenamed)
        return requireNotNull(registry.executor(ToolName(name), ToolVersion(1))).execute(
            ExecutableToolCall(
                "c",
                name,
                "1",
                Json.parseToJsonElement(args).jsonObject,
                ExecutionTargetType.LOCAL_ANDROID,
                deadline,
                cancel,
                "s",
                "t",
            ),
        )
    }

    private fun output(result: ToolExecutorResult) = (result as ToolExecutorResult.Completed).output.jsonObject

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private class HistoryDao : SessionHistoryDao {
        var session = SessionEntity("s", "Original", "p", "model", 1, null, "scope:workspace:folder")
        var active = true
        val rows = mutableListOf<MessageEntity>()
        val snapshots = mutableListOf<MessageReferenceSnapshotEntity>()

        override fun caller(
            sessionId: String,
            turnId: String,
            callId: String,
            toolName: String,
        ): SessionEntity? =
            session.takeIf {
                active && sessionId == "s" && turnId == "t" && callId == "c" && toolName in SessionToolDescriptors.names
            }

        override fun page(
            sessionId: String,
            beforeSequence: Long,
            limit: Int,
        ) = rows
            .filter {
                it.sessionId == sessionId && it.sequence < beforeSequence && it.supersededBy == null &&
                    it.kind == "TEXT" && it.role in setOf("USER", "ASSISTANT")
            }.sortedByDescending { it.sequence }
            .take(limit)

        override fun message(
            sessionId: String,
            messageId: String,
        ) = page(sessionId, Long.MAX_VALUE, Int.MAX_VALUE).find { it.id == messageId }

        override fun references(messageId: String) = snapshots.filter { it.messageId == messageId }

        override fun rename(
            sessionId: String,
            turnId: String,
            callId: String,
            expectedTitle: String,
            title: String,
            searchTitle: String,
        ): Int {
            if (caller(sessionId, turnId, callId, "session.rename") == null || session.title != expectedTitle) return 0
            session = session.copy(title = title, searchTitle = searchTitle)
            return 1
        }
    }
}

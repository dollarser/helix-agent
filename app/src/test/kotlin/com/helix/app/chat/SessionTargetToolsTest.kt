package com.helix.app.chat

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.dao.SessionAccessDao
import com.helix.core.storage.dao.SessionAccessExecution
import com.helix.core.storage.dao.SessionAccessSummary
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.SessionAccessGrantEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.repository.SessionAccessRepository
import com.helix.core.storage.repository.SessionHistoryRepository
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy
import java.time.Instant

class SessionTargetToolsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val history = SessionToolsTest.HistoryDao()
    private val store by lazy { FileContentStore(temporary.root) }
    private val grants = mutableMapOf<String, SessionAccessGrantEntity>()
    private val executions = mutableMapOf<String, SessionAccessExecution>()
    private val dao =
        Proxy.newProxyInstance(
            SessionAccessDao::class.java.classLoader,
            arrayOf(SessionAccessDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execution" -> {
                    executions[args[0]]?.takeIf { it.state !in args[1] as List<*> }
                }

                "grant" -> {
                    grants[args[1]]?.takeIf { it.sourceSessionId == args[0] }
                }

                "grants" -> {
                    grants.values.filter { it.sourceSessionId == args[0] }.sortedBy { it.targetSessionId }
                }

                "list" -> {
                    grants.values
                        .filter {
                            it.sourceSessionId == args[0] &&
                                (it.readHistory || it.manageMetadata || it.delegateTasks) &&
                                (args[1] == null || it.targetSessionId > args[1] as String)
                        }.sortedBy { it.targetSessionId }
                        .take(args[2] as Int)
                        .mapNotNull { grant ->
                            history.sourceSession(grant.targetSessionId)?.let {
                                SessionAccessSummary(
                                    it.id,
                                    it.title,
                                    it.archivedAt,
                                    grant.readHistory,
                                    grant.manageMetadata,
                                    grant.revision,
                                    grant.delegateTasks,
                                )
                            }
                        }
                }

                else -> {
                    error("Unexpected ${method.name}")
                }
            }
        } as SessionAccessDao

    @Test fun unknownAndUnauthorizedAreIndistinguishableAndManageDoesNotRead() {
        target("private", read = false, manage = true)
        val denied = execute("session.history", """{"targetSessionId":"private"}""") as ToolExecutorResult.Failed
        val unknown = execute("session.history", """{"targetSessionId":"missing"}""") as ToolExecutorResult.Failed
        assertEquals(denied, unknown)
        assertTrue(execute("session.status", """{"targetSessionId":"private"}""") is ToolExecutorResult.Completed)
    }

    @Test fun delegationGrantIsDiscoverableWithoutOpeningHistory() {
        target("delegation", read = false, manage = false)
        grants["delegation"] = grants.getValue("delegation").copy(delegateTasks = true)
        val listed =
            output(execute("session.list"))
                .getValue("entries")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("delegation", listed.getValue("sessionId").jsonPrimitive.content)
        assertEquals("true", listed.getValue("delegateTasks").jsonPrimitive.content)
        val status = output(execute("session.status", """{"targetSessionId":"delegation"}"""))
        assertEquals("true", status.getValue("delegateTasks").jsonPrimitive.content)
        assertEquals("false", status.getValue("readHistory").jsonPrimitive.content)
        assertTrue(execute("session.history", """{"targetSessionId":"delegation"}""") is ToolExecutorResult.Failed)
    }

    @Test fun targetStatusDistinguishesActiveBlockedAndNoTurnWithoutHistoryAccess() {
        target("delegation", read = false, manage = false)
        grants["delegation"] = grants.getValue("delegation").copy(delegateTasks = true)

        fun status() = output(execute("session.status", """{"targetSessionId":"delegation"}"""))
        assertEquals("NONE", status().getValue("executionStatus").jsonPrimitive.content)
        executions["delegation"] = SessionAccessExecution("target-turn", "WAITING_MODEL")
        assertEquals("ACTIVE", status().getValue("executionStatus").jsonPrimitive.content)
        assertEquals("target-turn", status().getValue("activeTurnId").jsonPrimitive.content)
        executions["delegation"] = SessionAccessExecution("target-turn", "NEEDS_REVIEW")
        assertEquals("BLOCKED", status().getValue("executionStatus").jsonPrimitive.content)
        assertEquals("null", status().getValue("activeTurnId").toString())
        executions["delegation"] = SessionAccessExecution("target-turn", "COMPLETED")
        assertEquals("NONE", status().getValue("executionStatus").jsonPrimitive.content)
        assertTrue(execute("session.history", """{"targetSessionId":"delegation"}""") is ToolExecutorResult.Failed)
    }

    @Test fun listFiltersBeforeDisclosureAndContinuationBindsMetadataAndScope() {
        repeat(23) { target("target%02d".format(it)) }
        history.sources["secret"] = SessionEntity("secret", "Never disclose", null, null, 1, null)
        val first = output(execute("session.list"))
        assertEquals(20, first.getValue("entries").jsonArray.size)
        assertFalse(first.toString().contains("Never disclose"))
        val cursor = first.getValue("cursor").jsonPrimitive.content
        assertEquals(3, output(execute("session.list", """{"cursor":"$cursor"}""")).getValue("entries").jsonArray.size)
        grants["target00"] = grants.getValue("target00").copy(revision = 2, readHistory = false)
        assertTrue(execute("session.list", """{"cursor":"$cursor"}""") is ToolExecutorResult.Failed)
    }

    @Test fun historyCursorBindsTargetQuerySnapshotAndGrantRevision() {
        target("a")
        target("b")
        repeat(25) { body("a", "a$it", it.toLong(), "needle $it") }
        val first = output(execute("session.history", """{"targetSessionId":"a"}"""))
        val cursor = first.getValue("cursor").jsonPrimitive.content
        assertTrue(
            execute("session.history", """{"targetSessionId":"b","cursor":"$cursor"}""") is ToolExecutorResult.Failed,
        )
        assertTrue(
            execute(
                "session.search",
                """{"targetSessionId":"a","query":"needle","cursor":"$cursor"}""",
            ) is ToolExecutorResult.Failed,
        )
        body("a", "new", 100, "append outside fixed cursor")
        assertEquals(
            5,
            output(
                execute("session.history", """{"targetSessionId":"a","cursor":"$cursor"}"""),
            ).getValue("entries").jsonArray.size,
        )
        val search = output(execute("session.search", """{"targetSessionId":"a","query":"needle"}"""))
        val searchCursor = search.getValue("cursor").jsonPrimitive.content
        assertTrue(
            execute(
                "session.search",
                """{"targetSessionId":"a","query":"changed","cursor":"$searchCursor"}""",
            ) is ToolExecutorResult.Failed,
        )
        val rowIndex = history.rows.indexOfFirst { it.id == "a1" }
        val originalRow = history.rows[rowIndex]
        history.rows[rowIndex] = originalRow.copy(contentRef = store.write("edited").toStorageString())
        assertTrue(
            execute("session.history", """{"targetSessionId":"a","cursor":"$cursor"}""") is ToolExecutorResult.Failed,
        )
        history.rows[rowIndex] = originalRow
        grants["a"] = grants.getValue("a").copy(revision = 2)
        assertTrue(
            execute("session.history", """{"targetSessionId":"a","cursor":"$cursor"}""") is ToolExecutorResult.Failed,
        )
    }

    @Test fun revokedWhileReadingCannotPublishBody() {
        target("a")
        val ref = body("a", "message", 1, "private body")
        val revoking =
            object : ContentStore by store {
                override fun readBounded(
                    ref: ContentRef,
                    maxBytes: Int,
                ): String {
                    val result = store.readBounded(ref, maxBytes)
                    grants["a"] = grants.getValue("a").copy(revision = 2, readHistory = false)
                    return result
                }
            }
        val result =
            execute(
                "session.read",
                """{"targetSessionId":"a","messageId":"message","contentHash":"${ref.sha256}","offset":0}""",
                revoking,
            )
        assertTrue(result is ToolExecutorResult.Failed)
        assertFalse(result.toString().contains("private body"))
    }

    @Test fun snapshotScanHonorsCancellationAndExpiredDeadlineWithoutPublishing() {
        target("a")
        repeat(100) { body("a", "m$it", it.toLong(), "body") }
        var checks = 0
        val cancel =
            object : CancelSignal {
                override fun isCancelled(): Boolean = ++checks >= 5
            }
        assertEquals(
            ToolExecutorResult.Cancelled,
            execute(
                "session.history",
                """{"targetSessionId":"a"}""",
                cancel = cancel,
            ),
        )
        assertTrue(checks >= 5)
        assertEquals(
            ToolExecutorResult.TimedOut,
            execute(
                "session.history",
                """{"targetSessionId":"a"}""",
                deadline = Instant.EPOCH,
            ),
        )
    }

    private fun target(
        id: String,
        read: Boolean = true,
        manage: Boolean = false,
    ) {
        history.sources[id] = SessionEntity(id, "Title $id", null, null, 1, null)
        grants[id] = SessionAccessGrantEntity("s", id, read, manage, 1)
    }

    private fun body(
        target: String,
        id: String,
        sequence: Long,
        text: String,
    ): ContentRef =
        store.write(text).also {
            history.rows += MessageEntity(id, target, null, "USER", "TEXT", it.toStorageString(), sequence)
        }

    private fun execute(
        name: String,
        args: String = "{}",
        content: ContentStore = store,
        cancel: CancelSignal = NoCancellation,
        deadline: Instant = Instant.now().plusSeconds(30),
    ): ToolExecutorResult {
        val registry = ToolRegistry()
        val access = SessionAccessRepository(dao, history)
        SessionTools.register(registry, SessionHistoryRepository(history, content, access), accessRepository = access)
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
}

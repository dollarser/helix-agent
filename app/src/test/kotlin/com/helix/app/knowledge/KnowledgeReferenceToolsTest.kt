package com.helix.app.knowledge

import com.helix.core.model.CancelSignal
import com.helix.core.storage.repository.SessionInputState
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class KnowledgeReferenceToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(text: String = "Selected evidence") = KnowledgeReferenceFixture(temporary.root, text)

    private fun output(result: ToolExecutorResult) = (result as ToolExecutorResult.Completed).output.jsonObject

    private fun read(
        f: KnowledgeReferenceFixture,
        extra: JsonObject = buildJsonObject {},
    ): JsonObject {
        val args =
            buildJsonObject {
                put("kind", if (f.reference.kind == "SOURCE") "source" else "note")
                put("id", "selected")
            }
        return f.scoped(JsonObject(args + extra))
    }

    @Test fun globalOffAllowsOnlySelectedAndGlobalOnNeverWidensExplicitScope() {
        val f = fixture()
        val selected = output(f.execute("knowledge.read", read(f)))
        assertFalse(selected.containsKey("references"))
        assertFalse(selected.toString().contains("UNSELECTED_RELATED_EXCERPT"))
        assertTrue(
            f.execute("knowledge.read", f.args("""{"kind":"note","id":"private"}""")) is ToolExecutorResult.Failed,
        )
        f.enabled = true
        assertTrue(
            f.execute(
                "knowledge.read",
                f.scoped(f.args("""{"kind":"note","id":"private"}""")),
            ) is ToolExecutorResult.Failed,
        )
        assertFalse(f.reads.contains("private"))
        assertTrue(
            f.execute("knowledge.read", f.args("""{"kind":"note","id":"private"}""")) is ToolExecutorResult.Completed,
        )
    }

    @Test fun longUnicodePagesReassembleExactlyAndSearchFindsTailPast12000() {
        val text = "😀中".repeat(8000) + "TAIL_EVIDENCE"
        val f = fixture(text)
        val hit =
            output(f.execute("knowledge.search", f.scoped(f.args("""{"query":"TAIL_EVIDENCE"}"""))))
                .getValue("items")
                .jsonArray
                .single()
                .jsonObject
        assertTrue(hit.getValue("matchOffset").jsonPrimitive.int > 12_000)
        var offset = 0
        val combined = StringBuilder()
        do {
            val page =
                output(
                    f.execute(
                        "knowledge.read",
                        read(
                            f,
                            buildJsonObject {
                                put("offset", offset)
                                put("limit", 1024)
                                put("contentHash", f.reference.sourceHash)
                            },
                        ),
                    ),
                )
            val body = page.getValue("text").jsonPrimitive.content
            assertTrue(body.codePointCount(0, body.length) <= 1024)
            combined.append(body)
            offset = page["nextOffset"]?.jsonPrimitive?.int ?: -1
        } while (offset >= 0)
        assertEquals(text, combined.toString())
    }

    @Test fun searchCursorsPreserveAllMatchesAndRejectDifferentQueryOrScope() {
        val f = fixture((1..35).joinToString(" ") { "marker-$it" })
        val found = mutableListOf<Int>()
        var cursor: String? = null
        do {
            val response =
                output(
                    f.execute(
                        "knowledge.search",
                        f.scoped(
                            buildJsonObject {
                                put("query", "marker")
                                cursor?.let { put("cursor", it) }
                            },
                        ),
                    ),
                )
            found +=
                response.getValue("items").jsonArray.map {
                    it.jsonObject
                        .getValue("matchOffset")
                        .jsonPrimitive.int
                }
            cursor = response["nextCursor"]?.jsonPrimitive?.content
            if (cursor != null) {
                assertTrue(
                    f.execute(
                        "knowledge.search",
                        f.scoped(
                            buildJsonObject {
                                put("query", "changed")
                                put("cursor", cursor)
                            },
                        ),
                    ) is ToolExecutorResult.Failed,
                )
            }
        } while (cursor != null)
        assertEquals(35, found.size)
        assertEquals(found.size, found.distinct().size)
    }

    @Test fun changedDeletedAndConcurrentMutationFailInsteadOfSubstitutingCurrentBody() {
        val f = fixture()
        f.documents["selected"] = "Changed evidence"
        assertTrue(f.execute("knowledge.read", read(f)) is ToolExecutorResult.Failed)
        f.documents.remove("selected")
        assertTrue(f.execute("knowledge.read", read(f)) is ToolExecutorResult.Failed)
        f.documents["selected"] = "Selected evidence"
        f.afterRead = { f.documents["selected"] = "Changed during read" }
        assertTrue(f.execute("knowledge.read", read(f)) is ToolExecutorResult.Failed)
    }

    @Test fun laterTurnsRetainGrantButOtherConversationSupersessionAndRevocationDeny() {
        val f = fixture()
        assertTrue(f.execute("knowledge.read", read(f), turn = "followup") is ToolExecutorResult.Completed)
        f.input = f.input.copy(sessionId = "other")
        assertTrue(f.execute("knowledge.read", read(f)) is ToolExecutorResult.Failed)
        f.input = f.input.copy(sessionId = "s")
        f.dao.rows[0] = f.dao.rows[0].copy(supersededBy = "edited-owner")
        assertTrue(f.execute("knowledge.read", read(f)) is ToolExecutorResult.Failed)
        f.dao.rows[0] = f.dao.rows[0].copy(supersededBy = null)
        f.afterRead = { f.input = f.input.copy(state = SessionInputState.WITHDRAWN) }
        assertTrue(f.execute("knowledge.read", read(f)) is ToolExecutorResult.Failed)
    }

    @Test fun invalidRangesCancellationAndCredentialsNeverPublishData() {
        val f = fixture()
        for (extra in listOf("""{"offset":1}""", """{"offset":-1}""", """{"limit":4097}""")) {
            assertTrue(f.execute("knowledge.read", read(f, f.args(extra))) is ToolExecutorResult.Failed)
        }
        assertEquals(ToolExecutorResult.TimedOut, f.execute("knowledge.read", read(f), deadline = Instant.EPOCH))
        val cancel =
            object : CancelSignal {
                override fun isCancelled() = true
            }
        assertEquals(ToolExecutorResult.Cancelled, f.execute("knowledge.read", read(f), cancel = cancel))
        val guarded = fixture("synthetic key sk-abcdefghijklmnopq")
        assertTrue(guarded.execute("knowledge.read", read(guarded)) is ToolExecutorResult.Failed)
        assertTrue(
            guarded.execute("knowledge.search", guarded.scoped(guarded.args("""{"query":"synthetic"}""")))
                is ToolExecutorResult.Failed,
        )
    }

    @Test fun forgedIdentityUnacceptedInputAndTerminatedCallerAreRejectedBeforeRead() {
        val f = fixture()
        for (field in listOf("referenceInputId", "referenceHash")) {
            val forged = JsonObject(read(f) + (field to kotlinx.serialization.json.JsonPrimitive("0".repeat(64))))
            assertTrue(f.execute("knowledge.read", forged) is ToolExecutorResult.Failed)
        }
        f.input = f.input.copy(state = SessionInputState.PENDING)
        assertTrue(f.execute("knowledge.read", read(f)) is ToolExecutorResult.Failed)
        f.input = f.input.copy(state = SessionInputState.APPENDED)
        f.dao.active = false
        assertTrue(f.execute("knowledge.read", read(f)) is ToolExecutorResult.Failed)
        assertTrue(f.reads.isEmpty())
    }

    @Test fun cancellationAndGlobalRevocationAfterIoNeverReleaseThePage() {
        val f = fixture()
        var stopped = false
        val cancel =
            object : CancelSignal {
                override fun isCancelled() = stopped
            }
        f.afterRead = { stopped = true }
        assertEquals(ToolExecutorResult.Cancelled, f.execute("knowledge.read", read(f), cancel = cancel))
        f.enabled = true
        f.afterRead = { f.enabled = false }
        assertTrue(
            f.execute("knowledge.read", f.args("""{"kind":"note","id":"selected"}"""))
                is ToolExecutorResult.Failed,
        )
    }

    @Test fun notesKnowledgeAndImportedTextUseTheSameScopedBoundaryAndExtractionState() {
        for (kind in listOf("NOTE", "KNOWLEDGE", "SOURCE")) {
            val state = if (kind == "SOURCE") "RAW_TEXT" else "USER_CONTENT"
            val f = KnowledgeReferenceFixture(temporary.root, "😀中文", kind, state)
            val result = output(f.execute("knowledge.read", read(f)))
            assertEquals("😀中文", result.getValue("text").jsonPrimitive.content)
            assertEquals(3, result.getValue("totalCharacters").jsonPrimitive.int)
            assertEquals(kind, result.getValue("category").jsonPrimitive.content)
            f.completeness = "READY"
            assertTrue(f.execute("knowledge.read", read(f)) is ToolExecutorResult.Failed)
        }
    }
}

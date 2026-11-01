package com.helix.app.knowledge

import com.helix.app.citation.Citation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KnowledgeOriginalPdfToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sourceIdentityAndTextHashStayDistinctForSearchReadAndCitation() {
        val body = "Synthetic derived PDF evidence"
        val original = knowledgeHash("Synthetic original file identities and extraction coverage")
        val f = KnowledgeReferenceFixture(temporary.root, body, "SOURCE", "INCOMPLETE", original)
        val result = f.execute("knowledge.search", f.scoped(f.args("""{"query":"evidence"}""")))
        val output = (result as ToolExecutorResult.Completed).output.jsonObject
        val hit =
            output
                .getValue("items")
                .jsonArray
                .single()
                .jsonObject
        val hash = hit.getValue("contentHash").jsonPrimitive.content
        assertEquals(knowledgeHash(body), hash)
        assertEquals(original, hit.getValue("sourceRevision").jsonPrimitive.content)
        val rawCitation = hit.getValue("citation").jsonPrimitive.content
        val citation = requireNotNull(Citation.parse(rawCitation))
        assertEquals(hash, citation.revision)
        assertTrue(citation.excerpt(body).contains("evidence"))
        val next =
            f.scoped(
                buildJsonObject {
                    put("kind", "source")
                    put("id", "selected")
                    put("contentHash", hash)
                },
            )
        assertTrue(f.execute("knowledge.read", next) is ToolExecutorResult.Completed)
    }

    @Test fun nativeIoInterruptionSettlesAsCancelledAndNeverPublishesAPage() {
        val f = KnowledgeReferenceFixture(temporary.root, "Preview", "SOURCE", "INCOMPLETE")
        var cancelled = false
        f.afterPdfRead = { cancelled = true }
        f.pdfFailure =
            com.helix.extensions.documents
                .DocumentException("PARSER_CONNECTION_FAILED")
        val result =
            f.execute(
                "knowledge.read",
                f.scoped(
                    f.args(
                        """{"kind":"source","id":"selected","fileIndex":0,"page":1}""",
                    ),
                ),
                cancel =
                    object : com.helix.core.model.CancelSignal {
                        override fun isCancelled() = cancelled
                    },
            )
        assertTrue(result is ToolExecutorResult.Cancelled)
        assertEquals(listOf("selected"), f.pdfReads)
    }

    @Test fun selectedOriginalTailIsReadableWithoutEnablingOrExpandingGlobalAccess() {
        val f = KnowledgeReferenceFixture(temporary.root, "Preview only", "SOURCE", "INCOMPLETE")
        f.pdfPages[57] = "x".repeat(25_000) + "独特末尾答案"

        fun request(
            id: String,
            offset: Int,
        ) = f.scoped(
            buildJsonObject {
                put("kind", "source")
                put("id", id)
                put("fileIndex", 0)
                put("page", 57)
                put("offset", offset)
                put("limit", 64)
                put("contentHash", f.reference.sourceHash)
            },
        )
        val result = f.execute("knowledge.read", request("selected", 25_000)) as ToolExecutorResult.Completed
        val output = result.output.jsonObject
        assertTrue(
            output
                .getValue("text")
                .jsonPrimitive.content
                .contains("独特末尾答案"),
        )
        assertEquals(57, output.getValue("page").jsonPrimitive.int)
        assertTrue(
            com.helix.app.citation.Citation
                .parse(output.getValue("citation").jsonPrimitive.content) != null,
        )
        listOf(false, true).forEach { global ->
            f.enabled = global
            assertTrue(f.execute("knowledge.read", request("private", 0)) is ToolExecutorResult.Failed)
        }
        assertEquals(listOf("selected"), f.pdfReads)
        f.documents["selected"] = "Changed preview"
        assertTrue(f.execute("knowledge.read", request("selected", 0)) is ToolExecutorResult.Failed)
        assertEquals(listOf("selected"), f.pdfReads)
    }
}

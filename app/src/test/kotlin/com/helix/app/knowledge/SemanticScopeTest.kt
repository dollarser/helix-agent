package com.helix.app.knowledge

import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SemanticScopeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun candidate(
        id: String,
        text: String,
    ): JsonObject =
        buildJsonObject {
            put("kind", "note")
            put("id", id)
            put("contentHash", knowledgeHash(text))
            put("sourceRevision", knowledgeHash(text))
            put("readOffset", 0)
            put("endOffset", text.length)
            put("excerpt", "UNTRUSTED_INDEX_TEXT")
        }

    @Test fun cancellationStopsPendingRetrievalAndReturnsCancelled() {
        val f = KnowledgeReferenceFixture(temporary.root, "Synthetic source")
        val cancelled =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val released =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        f.related = {
            cancelled.set(true)
            try {
                kotlinx.coroutines.delay(10_000)
                emptyList()
            } finally {
                released.set(true)
            }
        }
        val result =
            f.execute(
                "knowledge.search",
                f.scoped(f.args("""{"query":"different words"}""")),
                cancel =
                    object : com.helix.core.model.CancelSignal {
                        override fun isCancelled() = cancelled.get()
                    },
            )
        assertTrue(result is ToolExecutorResult.Cancelled)
        assertTrue(released.get())
    }

    @Test fun selectedScopeSurvivesGlobalToggleAndNeverTrustsCachedExcerpt() {
        val f = KnowledgeReferenceFixture(temporary.root, "Verified source evidence")
        var observed = emptyList<String>()
        f.related =
            { refs ->
                observed = refs.orEmpty().map { it.resourceId }
                listOf(candidate("selected", f.documents.getValue("selected")))
            }
        for (global in listOf(false, true)) {
            f.enabled = global
            val result =
                f.execute(
                    "knowledge.search",
                    f.scoped(f.args("""{"query":"different words"}""")),
                ) as ToolExecutorResult.Completed
            assertEquals(listOf("selected"), observed)
            assertEquals(
                1,
                result.output.jsonObject
                    .getValue("related")
                    .jsonArray.size,
            )
            assertTrue(result.output.toString().contains("Verified source evidence"))
            assertFalse(result.output.toString().contains("UNTRUSTED_INDEX_TEXT"))
        }
        f.related = { listOf(candidate("private", f.documents.getValue("private"))) }
        assertTrue(
            f.execute(
                "knowledge.search",
                f.scoped(f.args("""{"query":"different words"}""")),
            ) is ToolExecutorResult.Failed,
        )
        assertFalse("private" in f.reads)
    }

    @Test fun changedAndDeletedDocumentsCannotBeReturnedByAnOldIndex() {
        val f = KnowledgeReferenceFixture(temporary.root, "Original evidence")
        f.related = {
            f.documents["selected"] = "Edited evidence"
            listOf(candidate("selected", "Original evidence"))
        }
        assertTrue(
            f.execute("knowledge.search", f.scoped(f.args("""{"query":"absent"}"""))) is ToolExecutorResult.Failed,
        )
        f.documents["selected"] = "Original evidence"
        f.related = {
            f.documents.remove("selected")
            listOf(candidate("selected", "Original evidence"))
        }
        assertTrue(
            f.execute("knowledge.search", f.scoped(f.args("""{"query":"absent"}"""))) is ToolExecutorResult.Failed,
        )
    }
}

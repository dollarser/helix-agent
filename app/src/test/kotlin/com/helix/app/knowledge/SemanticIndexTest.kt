package com.helix.app.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SemanticIndexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun scanPageDelimitersAreNotMistakenForReadableMaterial() {
        val source =
            com.helix.core.storage.knowledge.CollectedSource(
                "scan",
                "Synthetic scan",
                1,
                "fixture",
                "",
                "",
                state = "INCOMPLETE",
                extracted = "\n\n## PDF page 1\n",
                extraction = """[{"pages":[{"page":1,"state":"no_text"}]}]""",
            )
        assertNull(semanticSourceDocument(source))
        val readable = source.copy(extracted = source.extracted + "Actually extracted words")
        assertEquals(readable.extracted, semanticSourceDocument(readable)!!.text)
    }

    @Test fun unicodeChunksCoverTailAndCacheCannotSubstituteRevisionsOrModels() {
        val originalBuild = SemanticConfiguration(model = "synthetic", indexGeneration = "first-build")
        val rebuild = originalBuild.copy(indexGeneration = "next-build")
        assertFalse(originalBuild.fingerprint == rebuild.fingerprint)
        val document = SemanticDocument("note:chosen", "revision-a", "Synthetic", "😀中文".repeat(9000) + "TAIL")
        val ranges = SemanticIndex.ranges(document.text)
        assertEquals(0, ranges.first().first)
        assertEquals(document.text.codePointCount(0, document.text.length), ranges.last().second)
        ranges.zipWithNext().forEach { (left, right) -> assertTrue(right.first <= left.second) }
        assertTrue(SemanticIndex.text(document, ranges.last().first, ranges.last().second).endsWith("TAIL"))
        val index = SemanticIndex(temporary.root)
        val chunks = ranges.map { SemanticChunk(it.first, it.second, floatArrayOf(1f, 0f)) }
        index.write("model-a", document, chunks)
        assertEquals(chunks.size, index.read("model-a", document)!!.size)
        assertNull(index.read("model-b", document))
        assertNull(index.read("model-a", document.copy(revision = "edited")))
        val file = temporary.root.listFiles()!!.single()
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(index.read("model-a", document))
    }

    @Test fun selectedCorpusCapacityReadsTailAndModelRebuildRemovesOnlyDerivedCache() {
        val index = SemanticIndex(temporary.root)
        val documents =
            (0 until 64).map { n ->
                val text = "合成技术资料包含可重建的检索片段。".repeat(650) + "唯一编号 SYN-$n"
                val document = SemanticDocument("note:n$n", "r1", "合成资料 $n", text)
                val chunks =
                    SemanticIndex.ranges(text).map { (start, end) ->
                        SemanticChunk(start, end, FloatArray(1024).also { it[0] = 1f })
                    }
                index.write("model-a", document, chunks)
                document
            }
        val rows = documents.map { it to requireNotNull(index.read("model-a", it)) }
        val target = documents.last()
        assertTrue(
            SemanticIndex.rank("SYN-63", null, rows).any {
                it.document == target &&
                    SemanticIndex.text(it.document, it.start, it.end).contains("SYN-63")
            },
        )
        assertNull(index.read("model-a", target.copy(revision = "edited")))
        index.retain("model-b", documents.map { it.key }.toSet())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
        assertTrue(documents.all { index.read("model-b", it) == null })
    }

    @Test fun fusionFindsSemanticOnlyResultAndRetainDeletesDerivedEntriesOnly() {
        val blue = SemanticDocument("source:blue", "a", "Blue", "Harbor beacon route")
        val red = SemanticDocument("source:red", "b", "Red", "Mountain trail route")
        val rows =
            listOf(
                blue to listOf(SemanticChunk(0, blue.text.length, floatArrayOf(1f, 0f))),
                red to listOf(SemanticChunk(0, red.text.length, floatArrayOf(0f, 1f))),
            )
        assertEquals(blue, SemanticIndex.rank("navigation light", floatArrayOf(1f, 0f), rows).first().document)
        assertTrue(SemanticIndex.rank("no-match", null, rows).isEmpty())
        val index = SemanticIndex(temporary.root)
        rows.forEach { index.write("model", it.first, it.second) }
        val unrelated = java.io.File(temporary.root, "unrelated.txt").apply { writeText("keep") }
        val interrupted = java.io.File(temporary.root, "index-123.tmp").apply { writeText("incomplete cache") }
        index.retain("model", setOf(blue.key))
        assertTrue(unrelated.exists())
        assertFalse(interrupted.exists())
        assertNull(index.read("model", red))
        assertFalse(index.read("model", blue).isNullOrEmpty())
    }
}

package com.helix.core.workspace.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class MarkdownMemoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val global = MemoryScope.Global

    private fun store() =
        MarkdownMemoryStore(temporary.root.toPath().resolve("memory")) {
            it.contains("credential-secret")
        }

    private fun text(body: String) = MemoryMarkdown.encode("user", "user feedback", body, 1)

    @Test fun markdownReopenAndManualEditAreCanonicalWithoutAnIndexDatabase() {
        val saved = store().write(global, "user.md", text("Answer in Chinese"), "new")
        assertEquals(saved.hash, store().read(global, "user.md").hash)
        val file = temporary.root.toPath().resolve("memory/global/user.md")
        Files.write(file, text("Answer concisely").toByteArray())
        assertEquals(1, store().search(global, "concisely").size)
        assertTrue(store().search(global, "Chinese").isEmpty())
        assertNotEquals(saved.hash, store().index(global).single().hash)
    }

    @Test fun projectsAndGlobalArePhysicallyIsolated() {
        val a = MemoryScope.Project(ProjectMemoryScopeKey("project-a"))
        val b = MemoryScope.Project(ProjectMemoryScopeKey("project-b"))
        store().write(a, "decisions.md", MemoryMarkdown.encode("project", "verified build", "Use JDK17", 1), "new")
        assertTrue(store().index(b).isEmpty())
        assertTrue(store().index(global).isEmpty())
        assertEquals("decisions.md", store().index(a).single().path)
        assertThrows(IllegalArgumentException::class.java) {
            store().write(global, "decisions.md", MemoryMarkdown.encode("project", "project", "Use JDK17", 1), "new")
        }
    }

    @Test fun conflictsNeverOverwriteAndDeleteInvalidatesTheRebuiltIndex() {
        val saved = store().write(global, "feedback.md", text("old"), "new")
        val changed = store().edit(global, "feedback.md", saved.hash, "old", "new feedback")
        assertThrows(
            IllegalArgumentException::class.java,
        ) { store().write(global, "feedback.md", text("stale"), saved.hash) }
        assertThrows(IllegalArgumentException::class.java) { store().delete(global, "feedback.md", saved.hash) }
        assertEquals(changed, store().read(global, "feedback.md"))
        store().delete(global, "feedback.md", changed.hash)
        assertTrue(store().index(global).isEmpty())
    }

    @Test fun editRequiresExactlyOneMatchAndSecretsNeverPersist() {
        val saved = store().write(global, "feedback.md", text("same same"), "new")
        assertThrows(IllegalArgumentException::class.java) {
            store().edit(global, "feedback.md", saved.hash, "same", "changed")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().write(global, "secret.md", text("credential-secret"), "new")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().write(global, "credential-secret.md", text("safe body"), "new")
        }
        assertEquals(1, store().index(global).size)
    }

    @Test fun traversalAndSymlinkCannotReadOrWriteOutsideMemory() {
        val s = store()
        val saved = s.write(global, "user.md", text("safe"), "new")
        for (name in listOf("../escape.md", "/escape.md", "sub/file.md", ".hidden.md")) {
            assertThrows(IllegalArgumentException::class.java) { s.write(global, name, "bad", "new") }
        }
        val target = temporary.newFile("outside.md").toPath()
        Files.write(target, "outside".toByteArray())
        val link = temporary.root.toPath().resolve("memory/global/link.md")
        Files.createSymbolicLink(link, target)
        assertThrows(IllegalArgumentException::class.java) { s.read(global, "link.md") }
        assertThrows(IllegalArgumentException::class.java) { s.write(global, "link.md", "bad", "new") }
        assertEquals("outside", String(Files.readAllBytes(target)))
        assertEquals(saved.hash, s.read(global, "user.md").hash)
    }

    @Test fun overLimitDocumentIsRejectedWithoutChangingExistingBytes() {
        val saved = store().write(global, "user.md", text("retained"), "new")
        assertThrows(IllegalArgumentException::class.java) {
            store().write(global, "user.md", "x".repeat(MemoryMarkdown.MAX_BYTES + 1), saved.hash)
        }
        assertEquals(saved.hash, store().read(global, "user.md").hash)
    }

    @Test fun summaryOnlyContextIsBoundedAndTopicsRequireExplicitDiscovery() {
        store().write(global, "memory_summary.md", text("Prefer concise Chinese responses."), "new")
        store().write(global, "user.md", text("TOPIC_NOT_PRELOADED"), "new")
        val context = MemoryContext.render(store(), listOf(global))
        assertTrue(context.contains("Prefer concise Chinese"))
        assertFalse(context.contains("TOPIC_NOT_PRELOADED"))
        assertTrue(context.contains("UNTRUSTED_MEMORY"))
        assertTrue(context.toByteArray().size <= MemoryContext.MAX_CONTEXT_BYTES)
        assertEquals("user.md", store().search(global, "TOPIC_NOT_PRELOADED").single().path)
    }

    @Test fun aggregateQuotaRejectsTheNextWriteWithoutRemovingExistingTopics() {
        val s = store()
        repeat(32) { index -> s.write(global, "topic-$index.md", "a".repeat(32_000), "new") }
        assertThrows(IllegalArgumentException::class.java) {
            s.write(global, "too-much.md", "a".repeat(32_000), "new")
        }
        assertEquals(32, s.index(global).size)
    }

    @Test fun unavailableSummaryDoesNotBlockContextOrLoadOtherFiles() {
        store().write(global, "user.md", text("not preloaded"), "new")
        assertFalse(MemoryContext.render(store(), listOf(global)).contains("not preloaded"))
    }
}

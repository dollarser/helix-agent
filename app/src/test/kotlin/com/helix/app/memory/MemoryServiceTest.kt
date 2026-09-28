package com.helix.app.memory

import com.helix.core.workspace.memory.MarkdownMemoryStore
import com.helix.core.workspace.memory.MemoryScope
import com.helix.core.workspace.memory.ProjectMemoryScopeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val flags = mutableMapOf<String, Boolean>()

    private fun service(project: (String) -> ProjectMemoryScopeKey? = { null }) =
        MemoryService(
            MarkdownMemoryStore(temporary.root.toPath()),
            { flags[it] ?: false },
            { key, value -> flags[key] = value },
            project,
        )

    @Test fun memoryOffOnABUsesTheSameSummaryWithoutChangingTopicsOrProjectIdentity() {
        val service = service()
        service.save(
            MemoryScope.Global,
            "memory_summary.md",
            service.newMarkdown("user", "user", "Answer in Chinese"),
            "new",
        )
        assertEquals("", service.context("a"))
        service.configure("enabled", true)
        assertTrue(service.context("a").contains("Answer in Chinese"))
        assertEquals(service.context("a"), service.context("b"))
        service.configure("enabled", false)
        assertEquals("", service.context("a"))
        assertEquals(1, service.list(MemoryScope.Global).size)
        assertThrows(IllegalArgumentException::class.java) { service.scope("project", "a") }
    }

    @Test fun modelCannotEnableItselfAndAutoWriteIsAnAdditionalGate() {
        val service = service()
        assertThrows(IllegalStateException::class.java) { service.requireModelAccess(MemoryScope.Global, false) }
        service.configure("enabled", true)
        service.requireModelAccess(MemoryScope.Global, false)
        assertThrows(IllegalStateException::class.java) { service.requireModelAccess(MemoryScope.Global, true) }
        service.configure("auto-global", true)
        service.requireModelAccess(MemoryScope.Global, true)
        assertThrows(IllegalArgumentException::class.java) { service.configure("project-id", true) }
    }

    @Test fun onlyTrustedSessionResolverCanSelectAProject() {
        val service = service { if (it == "session-a") ProjectMemoryScopeKey("project-a") else null }
        assertTrue(service.projectAvailable("session-a"))
        assertFalse(service.projectAvailable("project-a"))
        assertThrows(IllegalArgumentException::class.java) { service.scope("project", "project-a") }
        assertEquals(MemoryScope.Project(ProjectMemoryScopeKey("project-a")), service.scope("project", "session-a"))
    }
}

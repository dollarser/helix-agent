package com.helix.extensions.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAuthorSampleTest {
    @Test fun pureWorkflowExampleUsesTheProductionParserWithoutPlaceholderTools() {
        val paths = listOf("plugin.json", "skills/pure-workflow/SKILL.md")
        val files =
            paths.associateWith { path ->
                requireNotNull(javaClass.getResourceAsStream("/samples/pure-workflow/$path")).use { it.readBytes() }
            }
        val bundle = PluginPackageReader().parse(files)
        assertEquals("pure-workflow", bundle.name)
        assertTrue(bundle.endpoints.isEmpty())
        assertEquals(1, bundle.skills.size)
        assertTrue(bundle.diagnostics.isEmpty())
    }
}

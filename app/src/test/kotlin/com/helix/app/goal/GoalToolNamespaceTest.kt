package com.helix.app.goal

import com.helix.core.model.ToolName
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalToolNamespaceTest {
    @Test fun lifecycleRegistryRemovesOldEntriesWithoutRedirectingCalls() {
        val registry = ToolRegistry()
        GoalLifecycleTools.register(registry) { ToolExecutorResult.Cancelled }
        assertEquals(setOf("goal.create", "goal.get", "goal.update"), registry.all().map { it.name.value }.toSet())
        listOf("create_goal", "get_goal", "update_goal").forEach {
            assertNull(registry.resolveLatest(ToolName(it)))
        }
    }
}

package com.helix.app.chat

import com.helix.core.model.AgentMode
import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEnvironmentContextTest {
    @Test fun packagedTemplatesAreSelectedAndBindTheRealDirectory() {
        val directory = FileScopePath("app", "work/project")
        assertEquals(1, ChatEnvironmentContext.messages(directory, AgentMode.CHAT, false).size)
        val plan = ChatEnvironmentContext.messages(directory, AgentMode.PLAN, true)
        assertEquals(4, plan.size)
        assertFalse(plan[1].text.contains("scope:app:work/project"))
        assertTrue(plan[1].text.contains("Read existing content before changing it"))
        assertTrue(plan.last().text.contains("scope:app:work/project"))
        assertTrue(plan[2].text.contains("read-only"))
        val moved = ChatEnvironmentContext.messages(FileScopePath("other", "next"), AgentMode.PLAN, true)
        assertEquals(plan.dropLast(1), moved.dropLast(1))
        assertTrue(moved.last().text.contains("scope:other:next"))
        assertFalse(moved.last().text.contains("scope:app:work/project"))
    }
}

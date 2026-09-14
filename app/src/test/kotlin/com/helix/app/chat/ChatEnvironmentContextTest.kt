package com.helix.app.chat

import com.helix.core.model.AgentMode
import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEnvironmentContextTest {
    @Test fun packagedTemplatesAreSelectedAndBindTheRealDirectory() {
        val directory = FileScopePath("app", "work/project")
        assertEquals(1, ChatEnvironmentContext.messages(directory, AgentMode.CHAT, false).size)
        val plan = ChatEnvironmentContext.messages(directory, AgentMode.PLAN, true)
        assertEquals(3, plan.size)
        assertTrue(plan[1].text.contains("scope:app:work/project"))
        assertTrue(plan.last().text.contains("read-only"))
    }
}

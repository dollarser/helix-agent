package com.helix.app.ui

import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyConversationHintTest {
    @Test
    fun ordinaryTasksOfferResearchDocumentsAndPlanning() {
        val suggestions = emptyConversationSuggestions(goalMode = false)
        assertEquals(3, suggestions.size)
        assertTrue(suggestions.contains(R.string.chat_task_web))
        assertTrue(suggestions.contains(R.string.chat_task_document))
        assertTrue(suggestions.contains(R.string.chat_task_plan))
    }

    @Test
    fun goalTasksDescribeVerifiableDeliverables() {
        val suggestions = emptyConversationSuggestions(goalMode = true)
        assertEquals(2, suggestions.size)
        assertTrue(suggestions.contains(R.string.chat_task_goal_research))
        assertTrue(suggestions.contains(R.string.chat_task_goal_files))
    }
}

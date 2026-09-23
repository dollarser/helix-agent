package com.helix.app.ui

import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyConversationHintTest {
    @Test
    fun suggestionsForChatModeOfferExplorationAndPlanning() {
        val suggestions = emptyConversationSuggestions(goalMode = false)
        assertEquals(3, suggestions.size)
        assertTrue(suggestions.contains(R.string.chat_prompt_suggestion_code_analysis))
        assertTrue(suggestions.contains(R.string.chat_prompt_suggestion_plan_feature))
        assertTrue(suggestions.contains(R.string.chat_prompt_suggestion_run_tests))
    }

    @Test
    fun suggestionsForGoalModeOfferFixAndRefactor() {
        val suggestions = emptyConversationSuggestions(goalMode = true)
        assertEquals(2, suggestions.size)
        assertTrue(suggestions.contains(R.string.chat_prompt_suggestion_goal_fix))
        assertTrue(suggestions.contains(R.string.chat_prompt_suggestion_goal_refactor))
    }
}

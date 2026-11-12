package com.helix.feature.conversation.application.input

import com.helix.core.model.input.InputSkillActivation
import org.junit.Assert.assertEquals
import org.junit.Test

class SkillInputTextTest {
    private val skill = InputSkillActivation("BUILT_IN", "review", "a".repeat(64), "")

    @Test fun explicitSkillsSupplyModelTextWithoutMutatingTheInput() {
        val original = ""
        assertEquals("Use skill: review, test", skillInputText(original, listOf(skill, skill.copy(name = "test"))))
        assertEquals("", original)
    }

    @Test fun ordinaryTextAndPlainEmptyInputsStayUnchanged() {
        assertEquals("\n中文任务 ", skillInputText("\n中文任务 ", listOf(skill)))
        assertEquals("", skillInputText("", emptyList()))
        assertEquals("  ", skillInputText("  ", emptyList()))
    }

    @Test fun argumentsNeverBecomeInventedUserText() {
        assertEquals("Use skill: review", skillInputText(" ", listOf(skill.copy(arguments = "private argument"))))
    }
}

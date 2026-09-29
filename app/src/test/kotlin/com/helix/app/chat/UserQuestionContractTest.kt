package com.helix.app.chat

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.core.model.ModelRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserQuestionContractTest {
    @Test fun packagedQuestionToolPassesProductionRegistrationValidation() {
        val descriptor = UserQuestionTool.descriptor()
        val registry =
            com.helix.tools.framework
                .ToolRegistry()
        registry.register(
            descriptor,
            object : com.helix.tools.framework.ToolExecutor {
                override fun execute(call: com.helix.tools.framework.ExecutableToolCall) =
                    com.helix.tools.framework.ToolExecutorResult.Cancelled
            },
        )
        assertEquals("ask_user", descriptor.name.value)
        assertEquals(com.helix.core.model.ToolOperationClass.METADATA, descriptor.operationClass)
    }

    @Test fun supportsChoicesAndCustomOnlyQuestions() {
        val choices =
            UserQuestionService.decode(
                "q",
                "s",
                Json
                    .parseToJsonElement(
                        """{"question":"Which format?","options":["PDF","Markdown"],"multiple":true}""",
                    ).jsonObject,
            )
        assertEquals(listOf("PDF", "Markdown"), choices.options)
        assertTrue(choices.multiple)
        val custom =
            UserQuestionService.decode(
                "q",
                "s",
                Json
                    .parseToJsonElement(
                        """{"question":"What should the report cover?"}""",
                    ).jsonObject,
            )
        assertTrue(custom.options.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateOptionsAreRejected() {
        UserQuestionService.decode(
            "q",
            "s",
            Json
                .parseToJsonElement(
                    """{"question":"Pick","options":["same","same"]}""",
                ).jsonObject,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankQuestionIsRejected() {
        UserQuestionService.decode("q", "s", Json.parseToJsonElement("""{"question":" "}""").jsonObject)
    }

    @Test fun shippedGuidanceSeparatesAnswersFromApproval() {
        val guidance = packagedPromptTemplates.text("ask-user")
        assertTrue(guidance.contains("Answers do not grant tool permissions"))
        assertTrue(guidance.contains("Continue independent work"))
    }
}

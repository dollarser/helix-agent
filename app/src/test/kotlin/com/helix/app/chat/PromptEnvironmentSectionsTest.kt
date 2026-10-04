package com.helix.app.chat

import com.helix.core.agent.PromptRegistry
import com.helix.core.agent.PromptScope
import com.helix.core.agent.PromptSource
import com.helix.core.model.AgentMode
import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The environment-section selection rules (mainline ChatEnvironmentContext, registry form):
 * base always, files only when the file tools are exposed, plan only in Plan mode — and the
 * working directory is substituted from session facts, never from model-visible text.
 */
class PromptEnvironmentSectionsTest {
    @Test
    fun mobileGuidanceIsConditionalAndDoesNotOverridePlanMode() {
        for (mode in listOf(AgentMode.CHAT, AgentMode.PLAN)) {
            val registry = PromptRegistry()
            PromptEnvironmentSections.register(
                registry,
                directory,
                mode,
                false,
                mobileSkillContext = "Plugin guidance: ui.wait; ACTION_OUTCOME_UNKNOWN; does not grant permission",
            )
            val mobile = registry.resolve().single { it.name == PromptEnvironmentSections.MOBILE_NAME }
            assertEquals(PromptSource.EXTERNAL_CONTENT, mobile.source)
            assertEquals(PromptScope.SKILL, mobile.scope)
            assertTrue(mobile.content.contains("does not grant permission"))
            assertTrue(mobile.content.contains("ui.wait"))
            assertTrue(mobile.content.contains("ACTION_OUTCOME_UNKNOWN"))
            assertEquals(
                mode == AgentMode.PLAN,
                registry.resolve().any { it.name == PromptEnvironmentSections.PLAN_NAME },
            )
        }
        val registry = PromptRegistry()
        PromptEnvironmentSections.register(registry, directory, AgentMode.CHAT, false)
        assertTrue(registry.resolve().none { it.name == PromptEnvironmentSections.MOBILE_NAME })
        assertTrue(!registry.assemble().contains("ui.screenshot"))
    }

    private val templates =
        PromptTemplateSource { name ->
            when (name) {
                "base" -> "BASE in {{working_directory}}"
                "files" -> "FILES at {{working_directory}}"
                "plan" -> "PLAN-ONLY"
                else -> error("unknown template: $name")
            }
        }

    private val directory = FileScopePath("app", "work/demo")

    @Test
    fun chatModeWithoutFileToolsRegistersOnlyBase() {
        val registry = PromptRegistry()
        PromptEnvironmentSections.register(registry, directory, AgentMode.CHAT, false, templates)
        val resolved = registry.resolve()
        assertEquals(listOf("env.base"), resolved.map { it.name })
        // The working directory is the session fact rendered as a bounded model reference.
        assertEquals("BASE in scope:app:work/demo", registry.assemble())
    }

    @Test
    fun fileToolsAddTheFilesSection() {
        val registry = PromptRegistry()
        PromptEnvironmentSections.register(registry, directory, AgentMode.CHAT, true, templates)
        val resolved = registry.resolve()
        assertEquals(listOf("env.base", "env.files"), resolved.map { it.name })
        assertTrue("FILES at scope:app:work/demo" in registry.assemble())
    }

    @Test
    fun planModeAddsThePlanSectionRegardlessOfFileTools() {
        val registry = PromptRegistry()
        PromptEnvironmentSections.register(registry, directory, AgentMode.PLAN, false, templates)
        assertEquals(
            listOf("env.base", "env.plan"),
            registry.resolve().map { it.name },
        )
    }

    @Test
    fun environmentSectionsPrecedeTheGoalSectionsAndStaySystemTrusted() {
        val registry =
            PromptRegistry().register(
                com.helix.core.agent.PromptSection(
                    "goal.identity",
                    -1000,
                    PromptScope.IDENTITY,
                    PromptSource.BUILTIN_TEMPLATE,
                ) { "goal" },
            )
        PromptEnvironmentSections.register(registry, directory, AgentMode.GOAL, true, templates)
        val resolved = registry.resolve()
        assertEquals(
            listOf("env.base", "env.files", "goal.identity"),
            resolved.map { it.name },
        )
        assertTrue(resolved.all { it.trust == com.helix.core.agent.TrustLevel.SYSTEM })
    }

    @Test
    fun packagedPromptExplainsParallelBatchesAndDependencyOrderInEveryMode() {
        for (mode in AgentMode.values()) {
            val registry = PromptRegistry()
            PromptEnvironmentSections.register(registry, directory, mode, true)
            val base = registry.resolve().single { it.name == PromptEnvironmentSections.BASE_NAME }
            assertEquals(PromptSource.BUILTIN_TEMPLATE, base.source)
            val prompt = registry.assemble()
            assertTrue(prompt.contains("Tool calls in the same response may execute concurrently"))
            assertTrue(prompt.contains("Batch only independent operations"))
            assertTrue(prompt.contains("wait for its successful result"))
            assertTrue(prompt.contains("dependent call in a later response"))
            assertTrue(prompt.contains("Do not bypass a denied action"))
        }
    }

    @Test
    fun aBlankTemplateIsDroppedForThatStep() {
        val blankPlan =
            PromptTemplateSource { name ->
                if (name == "plan") "" else templates.text(name)
            }
        val registry = PromptRegistry()
        PromptEnvironmentSections.register(registry, directory, AgentMode.PLAN, false, blankPlan)
        assertEquals(listOf("env.base"), registry.resolve().map { it.name })
    }

    @Test
    fun aMissingPackagedTemplateFailsClosed() {
        val missing = PromptTemplateSource { name -> error("no such resource: $name") }
        val registry = PromptRegistry()
        PromptEnvironmentSections.register(registry, directory, AgentMode.CHAT, false, missing)
        // The loader's error propagates at resolution — no silent shorter prompt.
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { registry.resolve() }
    }
}

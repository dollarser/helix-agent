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

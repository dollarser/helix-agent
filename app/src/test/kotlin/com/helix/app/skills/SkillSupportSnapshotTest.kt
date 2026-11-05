package com.helix.app.skills

import com.helix.extensions.skills.SkillLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSupportSnapshotTest {
    @Test fun executionExtensionsAndRetainedFieldsStayDistinct() {
        val doc =
            SkillLoader().loadBuiltIn(
                "---\nname: sample\ndescription: Sample\ncontext: fork\nmodel: external\nargument-hint: file\n" +
                    "user-invocable: true\ndisable-model-invocation: false\nallowed-tools: Bash\n---\nGuide",
                "sample",
            )
        val facts = SkillSupportSnapshot(doc, emptyList(), null)
        assertEquals(setOf("context"), facts.unsupported)
        assertEquals(setOf("model", "argument-hint"), facts.unapplied)
        assertEquals("Bash", facts.document.allowedTools)
    }

    @Test fun ordinarySkillDoesNotAcquireSyntheticCompatibilityWarnings() {
        val doc = SkillLoader().loadBuiltIn("---\nname: simple\ndescription: Simple\n---\nGuide", "simple")
        val facts = SkillSupportSnapshot(doc, emptyList(), null)
        assertTrue(facts.unsupported.isEmpty())
        assertTrue(facts.unapplied.isEmpty())
        assertEquals(null, facts.web)
    }

    @Test fun readableGuidanceDoesNotMakeAnUnavailableBrowserProvided() {
        val guidance =
            com.helix.app.plugin.PluginComponentFact(
                "guide",
                com.helix.extensions.plugin.PluginComponentKind.SKILL,
                com.helix.extensions.plugin.PluginComponentState.AVAILABLE,
                true,
                true,
            )
        val browser =
            guidance.copy(
                ownerId = "browser",
                kind = com.helix.extensions.plugin.PluginComponentKind.BROWSER,
                state = com.helix.extensions.plugin.PluginComponentState.HOST_UNAVAILABLE,
            )
        val facts =
            com.helix.app.plugin.PluginReadinessSnapshot(
                "browser",
                1,
                true,
                true,
                listOf(guidance, browser),
                emptyList(),
            )
        assertEquals(false, browserCapabilityProvided(facts, true))
        assertEquals(
            true,
            browserCapabilityProvided(
                facts.copy(
                    components =
                        listOf(browser.copy(state = com.helix.extensions.plugin.PluginComponentState.AVAILABLE)),
                ),
                true,
            ),
        )
    }

    @Test fun sessionSelectionAndPluginDisablementRemainRequired() {
        val browser =
            com.helix.app.plugin.PluginComponentFact(
                "browser",
                com.helix.extensions.plugin.PluginComponentKind.BROWSER,
                com.helix.extensions.plugin.PluginComponentState.AVAILABLE,
                true,
                true,
            )
        val facts =
            com.helix.app.plugin.PluginReadinessSnapshot(
                "browser",
                1,
                false,
                true,
                listOf(browser),
                emptyList(),
            )
        assertEquals(false, browserCapabilityProvided(facts, true))
        assertEquals(true, browserCapabilityProvided(facts, false))
        assertEquals(false, browserCapabilityProvided(facts.copy(selected = true, enabled = false), true))
    }
}

package com.helix.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSkillsTest {
    @Test
    fun `ships M7 built-ins plus the gated M9 android UI skill`() {
        val documents = BuiltInSkills.documents()

        assertEquals(
            listOf(
                "android-ui-task",
                "data-transform",
                "mcp-installer",
                "notification-digest",
                "organize-files-preview",
                "repo-inspection",
                "skill-creator",
                "skill-installer",
                "web-research",
            ),
            documents.map { it.catalogEntry.name },
        )
        assertTrue(documents.all { it.catalogEntry.source == SkillSource.BUILT_IN })
        assertTrue(documents.all { it.body.isNotBlank() })
        assertTrue(documents.all { it.metadata["helix.built-in-version"] == "1" })
        assertTrue(documents.all { it.license == "Apache-2.0" })
        assertTrue(documents.filterNot { it.catalogEntry.name == "android-ui-task" }.all { it.allowedTools == null })
        val androidUi = documents.single { it.catalogEntry.name == "android-ui-task" }
        assertEquals(
            "ui.snapshot ui.find ui.click ui.long_click ui.set_text ui.scroll ui.back ui.home ui.wait",
            androidUi.allowedTools,
        )
        assertTrue(androidUi.body.contains("Never invent coordinates"))
        assertTrue(androidUi.body.contains("take a new snapshot"))
        assertTrue(androidUi.body.contains("ends this run immediately"))
        assertFalse(androidUi.body.contains("request a permission".replace("a ", "")))
        assertNull(documents.single { it.catalogEntry.name == "notification-digest" }.compatibility)
    }
}

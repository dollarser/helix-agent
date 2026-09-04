package com.helix.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSkillsTest {
    @Test
    fun `ships exactly the five M7 built-ins and no early android UI skill`() {
        val documents = BuiltInSkills.documents()

        assertEquals(
            listOf(
                "data-transform",
                "notification-digest",
                "organize-files-preview",
                "repo-inspection",
                "web-research",
            ),
            documents.map { it.catalogEntry.name },
        )
        assertTrue(documents.all { it.catalogEntry.source == SkillSource.BUILT_IN })
        assertTrue(documents.all { it.body.isNotBlank() })
        assertTrue(documents.all { it.metadata["helix.built-in-version"] == "1" })
        assertTrue(documents.all { it.license == "Apache-2.0" })
        assertTrue(documents.all { it.allowedTools == null })
        assertFalse(documents.any { it.catalogEntry.name == "android-ui-task" })
        assertNull(documents.single { it.catalogEntry.name == "notification-digest" }.compatibility)
    }
}

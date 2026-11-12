package com.helix.app.plugin

import com.helix.extensions.skills.SkillInvocationPolicy
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillListItem
import com.helix.extensions.skills.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExpertDependenciesTest {
    @Test fun uniqueNamesStayConciseWithoutChangingInvocationPolicy() {
        val skill = skill("notes", SkillSource.USER_IMPORTED, "a".repeat(64))
        val choice = expertSkillChoices(listOf(skill)).single()
        assertEquals("notes", choice.name)
        assertEquals(expertSkillReference(skill.key), choice.reference)
        assertFalse(choice.available)
    }

    @Test fun duplicateNamesShowSourceAndSnapshotButKeepFullIdentity() {
        val skills =
            listOf(
                skill("notes", SkillSource.USER_IMPORTED, "a".repeat(64)),
                skill("notes", SkillSource.USER_IMPORTED, "b".repeat(64)),
                skill("notes", SkillSource.PROJECT, "a".repeat(64)),
                skill("other", SkillSource.BUILT_IN, "c".repeat(64)),
            )
        val choices = expertSkillChoices(skills)
        assertEquals(
            listOf(
                "notes (USER_IMPORTED · aaaaaaaa)",
                "notes (USER_IMPORTED · bbbbbbbb)",
                "notes (PROJECT · aaaaaaaa)",
                "other",
            ),
            choices.map { it.name },
        )
        assertEquals(skills.map { expertSkillReference(it.key) }, choices.map { it.reference })
        assertEquals(4, choices.map { it.reference }.distinct().size)
    }

    private fun skill(
        name: String,
        source: SkillSource,
        hash: String,
    ) = SkillListItem(
        SkillKey(source, name, hash),
        "Untrusted description mentioning Browser",
        enabled = false,
        invocation = SkillInvocationPolicy(modelInvocable = false),
    )
}

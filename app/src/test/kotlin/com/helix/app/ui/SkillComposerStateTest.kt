package com.helix.app.ui

import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class SkillComposerStateTest {
    @Test fun discoveryAndSelectionPreserveExactIdentityWithoutChangingEnablement() =
        runBlocking {
            val repository = repository()
            val state = SkillComposerState(repository, "session", null)
            state.reload()
            assertFalse(state.failed)
            val key = state.rows.single().key
            val activation = state.prepare(skillSuggestionId(key))
            assertNotNull(activation)
            assertEquals(key.source.name, activation!!.source)
            assertEquals(key.name, activation.name)
            assertEquals(key.snapshotHash, activation.snapshotHash)
            assertEquals("", activation.arguments)
            assertEquals(true, repository.listSelectable("session").single().enabled)
            assertNull(state.prepare("skill:untrusted"))
        }

    @Test fun staleDiscoveryCannotActivateRevokedIndependentSource() =
        runBlocking {
            val repository = repository()
            val state = SkillComposerState(repository, "session", null)
            state.reload()
            val key = state.rows.single().key
            repository.setEnabled(key, false, SkillEnablementScope.SESSION, "session")
            val failure = runCatching { state.prepare(skillSuggestionId(key)) }
            assertEquals(true, failure.isFailure)
            assertEquals(false, repository.listSelectable("session").single().enabled)
            assertEquals(true, repository.listSelectable("other-session").single().enabled)
        }

    @Test fun noUserInvocationIsRejectedEvenWithAnExactKnownId() =
        runBlocking {
            val repository = repository("user-invocable: false\n")
            val state = SkillComposerState(repository, "session", null)
            state.reload()
            assertEquals(true, runCatching { state.prepare(skillSuggestionId(state.rows.single().key)) }.isFailure)
        }

    @Test fun equalContentFromDifferentPackagesHasDifferentSuggestionIdentity() {
        val key =
            com.helix.extensions.skills.SkillKey(
                com.helix.extensions.skills.SkillSource.USER_IMPORTED,
                "guide",
                "a".repeat(64),
            )
        assertNotEquals(skillSuggestionId(key), skillSuggestionId(key.copy(ownerId = "conn-first")))
        assertNotEquals(
            skillSuggestionId(key.copy(ownerId = "conn-first")),
            skillSuggestionId(key.copy(ownerId = "conn-second")),
        )
    }

    private fun repository(metadata: String = ""): SkillRepository {
        val root = Files.createTempDirectory("skill-composer-state")
        val document =
            SkillLoader().loadBuiltIn(
                "---\nname: compact\ndescription: Fixture skill sharing a built-in command name\n" +
                    "$metadata---\nFixture instructions",
                "compact",
            )
        return SkillRepository(root.resolve("skills"), root.resolve("state"), root.resolve("trash"), listOf(document))
    }
}

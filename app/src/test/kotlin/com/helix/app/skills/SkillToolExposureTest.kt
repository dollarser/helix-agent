package com.helix.app.skills

import com.helix.extensions.plugin.PluginContentContribution
import com.helix.extensions.plugin.presentationContributions
import com.helix.extensions.skills.NativeSkillPresentation
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillInvocationPolicy
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.SkillTools
import com.helix.extensions.skills.UserSkillActivation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class SkillToolExposureTest {
    @Test fun selectedAndAcceptedIdentitiesExposeOnlyReadersWithinTheirScope() {
        val root = Files.createTempDirectory("skill-exposure")

        fun document(name: String) = SkillLoader().loadBuiltIn("---\nname: $name\ndescription: Guide\n---\nBody", name)
        var accepted = emptyList<UserSkillActivation>()
        val repository =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns =
                    listOf(
                        document("help").copy(hostCatalogued = true),
                        document("selected"),
                        document("manual").copy(invocation = SkillInvocationPolicy(modelInvocable = false)),
                    ),
                userActivations = { session, turn -> if (session == "s" && turn == "t") accepted else emptyList() },
            )
        val native = NativeSkillPresentation(emptyList(), emptySet(), emptySet())

        fun contributions(
            session: String,
            turn: String,
        ): List<PluginContentContribution> {
            val activations = repository.presentationActivations(session, turn)
            return presentationContributions(emptyList(), repository.presentationSnapshot(session, activations, native))
        }
        val reads = setOf(SkillTools.READ, SkillTools.READ_RESOURCE)

        fun names(
            session: String,
            turn: String,
        ) = directSkillToolNames(contributions(session, turn))
        val selected = repository.list().single { it.key.name == "selected" }.key
        val manual = repository.list().single { it.key.name == "manual" }.key
        assertEquals(emptySet<String>(), names("s", "t"))
        repository.setEnabled(selected, true, SkillEnablementScope.SESSION, "s")
        assertEquals(reads, names("s", "t"))
        assertEquals(
            selected,
            contributions(
                "s",
                "t",
            ).filterIsInstance<PluginContentContribution.SkillCatalog>()
                .single {
                    it.selected
                }.key,
        )
        assertEquals(emptySet<String>(), names("other", "t"))
        repository.setEnabled(selected, false, SkillEnablementScope.SESSION, "s")
        repository.setEnabled(manual, true, SkillEnablementScope.SESSION, "s")
        assertEquals(emptySet<String>(), names("s", "t"))
        accepted = listOf(repository.prepareUserActivation(manual, "s", "arguments"))
        val active = contributions("s", "t").filterIsInstance<PluginContentContribution.ActivatedSkill>().single()
        assertEquals(manual, active.key)
        assertEquals(reads, directSkillToolNames(listOf(active)))
        assertEquals(emptySet<String>(), names("s", "other"))
    }
}

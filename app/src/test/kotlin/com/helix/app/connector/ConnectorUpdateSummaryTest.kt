package com.helix.app.connector

import com.helix.app.plugin.InstalledEndpoint
import com.helix.app.plugin.InstalledPlugin
import com.helix.app.plugin.PluginUpdateDiffEvaluator
import com.helix.extensions.plugin.PluginEndpoint
import com.helix.extensions.plugin.PluginPackage
import com.helix.extensions.plugin.PluginSkill
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorUpdateSummaryTest {
    private val endpoint = PluginEndpoint("main", "https://example.com/mcp", true, "auth-a")

    private fun current(endpoints: List<PluginEndpoint> = listOf(endpoint)) =
        InstalledPlugin(
            "conn-1",
            "sample",
            "CLAUDE",
            "old",
            endpoints.map { InstalledEndpoint("saved-${it.name}", it) },
            listOf(SkillKey(SkillSource.USER_IMPORTED, "git-ops", "a".repeat(64))),
            emptyList(),
            "local:sample",
            2,
            true,
            "1.0",
        )

    private fun incoming(endpoints: List<PluginEndpoint> = listOf(endpoint)) =
        PluginPackage(
            "sample",
            "CLAUDE",
            "new",
            endpoints,
            listOf("git-ops", "mr-review").map { name ->
                val body = "---\nname: $name\ndescription: Test instructions\n---\nRead only"
                PluginSkill(name, mapOf("SKILL.md" to body.toByteArray()))
            },
            emptyList(),
            "1.1",
        )

    @Test fun identicalBindingsPreserveConnectionAndReportAddedSkills() {
        val report = PluginUpdateDiffEvaluator.evaluate(current(), incoming())
        assertEquals("1.0", report.oldVersion)
        assertEquals("1.1", report.newVersion)
        assertEquals(listOf("mr-review"), report.addedSkills)
        assertTrue(report.removedSkills.isEmpty())
        assertTrue(report.changedEndpoints.isEmpty())
        assertFalse(report.requiresReconnection)
    }

    @Test fun changingOnlyUrlPathRequiresReconnection() {
        val report =
            PluginUpdateDiffEvaluator.evaluate(
                current(),
                incoming(listOf(endpoint.copy(url = "https://example.com/other"))),
            )
        assertEquals(listOf("main"), report.changedEndpoints)
        assertTrue(report.requiresReconnection)
    }

    @Test fun newEndpointRequiresConnection() {
        val report = PluginUpdateDiffEvaluator.evaluate(current(emptyList()), incoming())
        assertEquals(listOf("main"), report.addedEndpoints)
        assertTrue(report.requiresReconnection)
    }

    @Test fun authFingerprintAndCredentialRequirementFollowInstallerEquality() {
        listOf(endpoint.copy(authBindingHash = "auth-b"), endpoint.copy(needsCredential = false)).forEach { changed ->
            val report = PluginUpdateDiffEvaluator.evaluate(current(), incoming(listOf(changed)))
            assertEquals(listOf("main"), report.changedEndpoints)
            assertTrue(report.requiresReconnection)
        }
    }

    @Test fun removalsDoNotAskToReconnectUnaffectedEndpoints() {
        val report = PluginUpdateDiffEvaluator.evaluate(current(), incoming(emptyList()).copy(skills = emptyList()))
        assertEquals(listOf("main"), report.removedEndpoints)
        assertEquals(listOf("git-ops"), report.removedSkills)
        assertFalse(report.requiresReconnection)
    }

    @Test fun endpointNameIsPartOfBindingAndMissingVersionsStayUnknown() {
        val report =
            PluginUpdateDiffEvaluator.evaluate(
                current().copy(versionLabel = null),
                incoming(listOf(endpoint.copy(name = "renamed"))).copy(versionLabel = null),
            )
        assertEquals(listOf("main"), report.removedEndpoints)
        assertEquals(listOf("renamed"), report.addedEndpoints)
        assertEquals(null, report.oldVersion)
        assertEquals(null, report.newVersion)
    }
}

/** Exercised by the opt-in original-package tests without executing any package scripts. */
internal fun assertImportedUpdateSummary(
    bundle: PluginPackage,
    expectedSkills: Set<String>,
) {
    val previous =
        InstalledPlugin(
            "fixture",
            bundle.name,
            bundle.source,
            "old",
            emptyList(),
            emptyList(),
            emptyList(),
            "local:fixture",
            1,
            true,
        )
    val report = PluginUpdateDiffEvaluator.evaluate(previous, bundle)
    assertEquals(expectedSkills, report.addedSkills.toSet())
    assertEquals(bundle.endpoints.map { it.name }.sorted(), report.addedEndpoints)
}

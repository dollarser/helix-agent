package com.helix.app.connector

import com.helix.app.plugin.PluginUpdateDiffEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorUpdateSummaryTest {
    @Test
    fun `evaluate preserves credentials when endpoint bindings are identical`() {
        val current =
            PluginUpdateDiffEvaluator.PackageSnapshot(
                version = "1.0.0",
                changelog = "Initial release",
                skillKeys = setOf("skill:git-ops"),
                endpoints =
                    listOf(
                        PluginUpdateDiffEvaluator.EndpointBinding(
                            endpointId = "ep-main",
                            originUrl = "https://gitlab.example.com",
                            authType = "bearer",
                        ),
                    ),
            )

        val incoming =
            PluginUpdateDiffEvaluator.PackageSnapshot(
                version = "1.1.0",
                changelog = "Bug fixes and added skills",
                skillKeys = setOf("skill:git-ops", "skill:mr-review"),
                endpoints =
                    listOf(
                        PluginUpdateDiffEvaluator.EndpointBinding(
                            endpointId = "ep-main",
                            originUrl = "https://gitlab.example.com",
                            authType = "bearer",
                        ),
                    ),
            )

        val report = PluginUpdateDiffEvaluator.evaluate("conn-1", current, incoming)

        assertEquals("1.0.0", report.oldVersion)
        assertEquals("1.1.0", report.newVersion)
        assertEquals(listOf("skill:mr-review"), report.addedSkills)
        assertTrue(report.removedSkills.isEmpty())
        assertTrue(report.changedEndpoints.isEmpty())
        assertFalse("Same endpoint URL and auth must preserve credentials", report.requiresCredentialReconfiguration)
    }

    @Test
    fun `evaluate marks credential reconfiguration when origin url changes`() {
        val current =
            PluginUpdateDiffEvaluator.PackageSnapshot(
                version = "1.0.0",
                endpoints =
                    listOf(
                        PluginUpdateDiffEvaluator.EndpointBinding(
                            endpointId = "ep-main",
                            originUrl = "https://gitlab.old.com",
                            authType = "bearer",
                        ),
                    ),
            )

        val incoming =
            PluginUpdateDiffEvaluator.PackageSnapshot(
                version = "2.0.0",
                endpoints =
                    listOf(
                        PluginUpdateDiffEvaluator.EndpointBinding(
                            endpointId = "ep-main",
                            originUrl = "https://gitlab.new.com",
                            authType = "bearer",
                        ),
                    ),
            )

        val report = PluginUpdateDiffEvaluator.evaluate("conn-1", current, incoming)

        assertTrue(report.requiresCredentialReconfiguration)
        assertEquals(listOf("ep-main"), report.changedEndpoints)
    }

    @Test
    fun `evaluate marks credential reconfiguration when new endpoint is added`() {
        val current =
            PluginUpdateDiffEvaluator.PackageSnapshot(
                version = "1.0.0",
                endpoints = emptyList(),
            )

        val incoming =
            PluginUpdateDiffEvaluator.PackageSnapshot(
                version = "1.1.0",
                endpoints =
                    listOf(
                        PluginUpdateDiffEvaluator.EndpointBinding(
                            endpointId = "ep-auth",
                            originUrl = "https://auth.example.com",
                            authType = "oauth2",
                            oauthIssuer = "https://auth.example.com/issuer",
                        ),
                    ),
            )

        val report = PluginUpdateDiffEvaluator.evaluate("conn-1", current, incoming)

        assertTrue(report.requiresCredentialReconfiguration)
        assertEquals(listOf("ep-auth"), report.changedEndpoints)
    }
}

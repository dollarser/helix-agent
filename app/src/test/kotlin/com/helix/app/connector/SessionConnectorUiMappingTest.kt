package com.helix.app.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionConnectorUiMappingTest {
    private val gitlabPkg =
        SessionConnectorAvailabilityEvaluator.InstalledPackageSpec(
            connectorId = "conn-gitlab",
            isReady = true,
            providedTools = setOf("gitlab:create_issue", "gitlab:list_mr"),
            providedSkills = setOf("skill:git-ops", "skill:code-review"),
        )

    private val githubPkg =
        SessionConnectorAvailabilityEvaluator.InstalledPackageSpec(
            connectorId = "conn-github",
            isReady = true,
            providedTools = setOf("github:create_issue", "github:list_pr"),
            providedSkills = setOf("skill:git-ops"),
        )

    private val brokenPkg =
        SessionConnectorAvailabilityEvaluator.InstalledPackageSpec(
            connectorId = "conn-broken",
            isReady = false,
            providedTools = setOf("broken:ping"),
            providedSkills = emptySet(),
        )

    @Test
    fun `computeAvailableTools only includes ready tools from session-enabled packages`() {
        val packages = listOf(gitlabPkg, githubPkg, brokenPkg)
        val sessionEnabled = setOf("conn-gitlab", "conn-broken")
        val globallyDisabled = emptySet<String>()

        val available =
            SessionConnectorAvailabilityEvaluator.computeAvailableTools(
                sessionEnabledConnectorIds = sessionEnabled,
                packages = packages,
                globallyDisabledTools = globallyDisabled,
            )

        // gitlab tools are included
        assertTrue("gitlab:create_issue" in available)
        assertTrue("gitlab:list_mr" in available)
        // github tools are not included (not enabled in this session)
        assertFalse("github:create_issue" in available)
        // broken tools are not included (not ready)
        assertFalse("broken:ping" in available)
    }

    @Test
    fun `computeAvailableTools obeys globally disabled tools even if session enables package`() {
        val packages = listOf(gitlabPkg)
        val sessionEnabled = setOf("conn-gitlab")
        val globallyDisabled = setOf("gitlab:create_issue")

        val available =
            SessionConnectorAvailabilityEvaluator.computeAvailableTools(
                sessionEnabledConnectorIds = sessionEnabled,
                packages = packages,
                globallyDisabledTools = globallyDisabled,
            )

        assertFalse("gitlab:create_issue must be excluded by global disable", "gitlab:create_issue" in available)
        assertTrue("gitlab:list_mr remains available", "gitlab:list_mr" in available)
    }

    @Test
    fun `hasRemainingSkillSource detects remaining active source when one package is disabled`() {
        val packages = listOf(gitlabPkg, githubPkg)
        val sessionEnabled = setOf("conn-gitlab", "conn-github")

        // Both provide skill:git-ops. If user considers disabling gitlab, github still provides it
        val remaining =
            SessionConnectorAvailabilityEvaluator.hasRemainingSkillSource(
                skillKey = "skill:git-ops",
                disablingConnectorId = "conn-gitlab",
                sessionEnabledConnectorIds = sessionEnabled,
                packages = packages,
            )
        assertTrue("github should still supply skill:git-ops", remaining)

        // Only gitlab provides skill:code-review. Disabling gitlab leaves no remaining source
        val reviewRemaining =
            SessionConnectorAvailabilityEvaluator.hasRemainingSkillSource(
                skillKey = "skill:code-review",
                disablingConnectorId = "conn-gitlab",
                sessionEnabledConnectorIds = sessionEnabled,
                packages = packages,
            )
        assertFalse("No other connector provides skill:code-review", reviewRemaining)
    }

    @Test
    fun `hasRemainingSkillSource respects independent user installation`() {
        val packages = listOf(gitlabPkg)
        val sessionEnabled = setOf("conn-gitlab")

        val remaining =
            SessionConnectorAvailabilityEvaluator.hasRemainingSkillSource(
                skillKey = "skill:code-review",
                disablingConnectorId = "conn-gitlab",
                sessionEnabledConnectorIds = sessionEnabled,
                packages = packages,
                hasIndependentUserInstall = true,
            )
        assertTrue("Independent user install preserves availability regardless of package", remaining)
    }
}
